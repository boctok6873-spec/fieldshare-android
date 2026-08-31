package com.youngsu.fieldshare

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Stores this installation's opt-in FCM token under the signed-in user's private device list. */
class PushNotificationManager(
    context: Context,
    private val userId: String?,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun areNotificationsEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false) && systemNotificationsAllowed()

    suspend fun enable(): Result<Unit> = withStateTransitionLock { enableLocked() }

    suspend fun disable(): Result<Unit> = withStateTransitionLock { disableLocked() }

    suspend fun refreshRegistration(): Result<Unit> = withStateTransitionLock { refreshRegistrationLocked() }

    /** Called by FirebaseMessagingService; rechecks all opt-in state while holding the global lock. */
    suspend fun updateTokenIfEligible(token: String): Result<Unit> = withStateTransitionLock {
        updateTokenIfEligibleLocked(token)
    }

    private suspend fun enableLocked(): Result<Unit> = runCatching {
        check(systemNotificationsAllowed()) { "기기 알림 권한을 허용해 주세요." }
        saveRegistration(FirebaseMessaging.getInstance().token.await())
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, true)
            clearPendingCleanup()
        }
    }.mapErrorToUserMessage()

    private suspend fun disableLocked(): Result<Unit> {
        // Persist opt-out and cleanup targets first so a concurrent token refresh cannot relink this device.
        val cleanup = prepareNotificationCleanup(
            existing = pendingCleanup(),
            currentUserId = userId,
            currentInstallationId = preferences.getString(KEY_INSTALLATION_ID, null)
        )
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, false)
            writePendingCleanup(cleanup)
        }
        return retryPendingCleanupLocked().mapErrorToUserMessage()
    }

    private suspend fun refreshRegistrationLocked(): Result<Unit> {
        val cleanupResult = retryPendingCleanupLocked()
        if (!preferences.getBoolean(KEY_ENABLED, false)) return cleanupResult.mapErrorToUserMessage()
        cleanupResult.exceptionOrNull()?.let { return Result.failure<Unit>(it).mapErrorToUserMessage() }
        if (!systemNotificationsAllowed()) return disableLocked()
        return runCatching {
            saveRegistration(FirebaseMessaging.getInstance().token.await())
        }.mapErrorToUserMessage()
    }

    private suspend fun updateTokenIfEligibleLocked(token: String): Result<Unit> {
        if (!shouldStoreFcmToken(
                notificationsOptedIn = preferences.getBoolean(KEY_ENABLED, false),
                systemNotificationsAllowed = systemNotificationsAllowed(),
                hasAuthenticatedUser = !userId.isNullOrBlank(),
                token = token
            )
        ) return Result.success(Unit)
        retryPendingCleanupLocked().exceptionOrNull()?.let { return Result.failure<Unit>(it).mapErrorToUserMessage() }
        return runCatching { saveRegistration(token) }.mapErrorToUserMessage()
    }

    private suspend fun saveRegistration(token: String) {
        require(token.isNotBlank()) { "알림 토큰을 받지 못했습니다." }
        deviceDocument().set(
            mapOf(
                "token" to token,
                "notificationsEnabled" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private fun deviceDocument() = firestore.collection("users")
        .document(requireNotNull(userId) { "로그인 정보를 확인하지 못했습니다." })
        .collection("devices")
        .document(installationId())

    private fun installationId(): String = preferences.getString(KEY_INSTALLATION_ID, null)
        ?: UUID.randomUUID().toString().also { id ->
            preferences.edit { putString(KEY_INSTALLATION_ID, id) }
        }

    /** Must be called while [stateTransitionMutex] is held. */
    private suspend fun retryPendingCleanupLocked(): Result<Unit> {
        val cleanup = pendingCleanup()
        if (!cleanup.hasWork) return Result.success(Unit)

        val errors = mutableListOf<Throwable>()
        var remoteDeletionSucceeded: Boolean? = null
        var tokenDeletionSucceeded: Boolean? = null
        if (cleanup.needsRemoteDeletion) {
            val cleanupUserId = cleanup.userId
            val cleanupInstallationId = cleanup.installationId
            if (cleanupUserId.isNullOrBlank() || cleanupInstallationId.isNullOrBlank()) {
                errors += IllegalStateException("보류된 알림 기기 정보를 확인하지 못했습니다.")
            } else {
                runCatching {
                    firestore.collection("users").document(cleanupUserId)
                        .collection("devices").document(cleanupInstallationId)
                        .delete().await()
                }.onSuccess {
                    remoteDeletionSucceeded = true
                }.onFailure { error ->
                    errors += error
                }
            }
        }
        if (cleanup.needsTokenDeletion) {
            // This must still run when Firestore cleanup failed.
            runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
                .onSuccess { tokenDeletionSucceeded = true }
                .onFailure { error -> errors += error }
        }

        val updatedCleanup = applyNotificationCleanupResults(
            cleanup,
            remoteDeletionSucceeded = remoteDeletionSucceeded,
            tokenDeletionSucceeded = tokenDeletionSucceeded
        )
        preferences.edit(commit = true) { writePendingCleanup(updatedCleanup) }
        return if (errors.isEmpty() && !updatedCleanup.hasWork) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("알림 해제 정리를 완료하지 못했습니다.", errors.firstOrNull()))
        }
    }

    private fun pendingCleanup() = PendingNotificationCleanup(
        userId = preferences.getString(KEY_PENDING_CLEANUP_UID, null),
        installationId = preferences.getString(KEY_PENDING_CLEANUP_INSTALLATION_ID, null),
        needsRemoteDeletion = preferences.getBoolean(KEY_PENDING_REMOTE_DELETION, false),
        needsTokenDeletion = preferences.getBoolean(KEY_PENDING_TOKEN_DELETION, false)
    )

    private fun android.content.SharedPreferences.Editor.writePendingCleanup(cleanup: PendingNotificationCleanup) {
        if (cleanup.hasWork) {
            putString(KEY_PENDING_CLEANUP_UID, cleanup.userId)
            putString(KEY_PENDING_CLEANUP_INSTALLATION_ID, cleanup.installationId)
            putBoolean(KEY_PENDING_REMOTE_DELETION, cleanup.needsRemoteDeletion)
            putBoolean(KEY_PENDING_TOKEN_DELETION, cleanup.needsTokenDeletion)
        } else {
            clearPendingCleanup()
            remove(KEY_INSTALLATION_ID)
        }
    }

    private fun android.content.SharedPreferences.Editor.clearPendingCleanup() {
        remove(KEY_PENDING_CLEANUP_UID)
        remove(KEY_PENDING_CLEANUP_INSTALLATION_ID)
        remove(KEY_PENDING_REMOTE_DELETION)
        remove(KEY_PENDING_TOKEN_DELETION)
    }

    private fun systemNotificationsAllowed(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun <T> Result<T>.mapErrorToUserMessage(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            val message = when ((error as? FirebaseFirestoreException)?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "알림 설정 권한이 없습니다. Firebase Console에서 최신 Firestore 규칙을 Publish해 주세요."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "로그인 정보를 확인하지 못했습니다. 앱을 다시 연 뒤 시도해 주세요."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "네트워크에 연결할 수 없습니다. 연결 후 다시 시도해 주세요."
                else -> "알림 설정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."
            }
            Result.failure(IllegalStateException(message, error))
        }
    )

    companion object {
        // Shared by every manager instance, including FirebaseMessagingService-created instances.
        val stateTransitionMutex = Mutex()

        internal suspend fun <T> withStateTransitionLock(block: suspend () -> T): T =
            stateTransitionMutex.withLock { block() }

        const val PREFERENCES_NAME = "fieldshare_notifications"
        const val KEY_ENABLED = "enabled"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_PENDING_CLEANUP_UID = "pending_cleanup_uid"
        const val KEY_PENDING_CLEANUP_INSTALLATION_ID = "pending_cleanup_installation_id"
        const val KEY_PENDING_REMOTE_DELETION = "pending_remote_deletion"
        const val KEY_PENDING_TOKEN_DELETION = "pending_token_deletion"
    }
}
