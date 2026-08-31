package com.youngsu.fieldshare

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

data class UserProfile(
    val uid: String,
    val displayName: String,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val isRegistered: Boolean get() = UserProfileValidation.normalize(displayName) != null
}

/** Shared validation so every profile write follows the same display-name contract. */
object UserProfileValidation {
    const val MaxDisplayNameLength = 20

    fun normalize(displayName: String): String? = displayName.trim()
        .takeIf { it.isNotEmpty() && it.length <= MaxDisplayNameLength }
}

/** Owns reads and writes to the authenticated user's users/{uid} document. */
class UserProfileRepository(
    private val currentUserId: String,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val profileDocument = firestore.collection("users").document(currentUserId)

    suspend fun getProfile(): Result<UserProfile?> = runCatching {
        val snapshot = profileDocument.get().await()
        if (!snapshot.exists()) return@runCatching null
        UserProfile(
            uid = currentUserId,
            displayName = snapshot.getString("displayName").orEmpty(),
            createdAt = snapshot.getTimestamp("createdAt"),
            updatedAt = snapshot.getTimestamp("updatedAt")
        )
    }

    suspend fun saveDisplayName(displayName: String): Result<UserProfile> = runCatching {
        val normalizedName = UserProfileValidation.normalize(displayName)
            ?: throw UserProfileException("사용자 이름은 공백을 제외하고 1~20자로 입력해 주세요.")
        val existingProfile = profileDocument.get().await()
        if (existingProfile.exists()) {
            profileDocument.update(
                mapOf(
                    "displayName" to normalizedName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            profileDocument.set(
                mapOf(
                    "displayName" to normalizedName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        UserProfile(uid = currentUserId, displayName = normalizedName)
    }.mapErrorToUserMessage()

    private fun <T> Result<T>.mapErrorToUserMessage(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            Result.failure(
                when ((error as? FirebaseFirestoreException)?.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        UserProfileException("프로필 접근 권한이 없습니다. Firebase 보안 규칙을 확인해 주세요.", error)
                    FirebaseFirestoreException.Code.UNAVAILABLE ->
                        UserProfileException("네트워크에 연결할 수 없습니다. 연결 후 다시 시도해 주세요.", error)
                    else -> if (error is UserProfileException) error
                    else UserProfileException("프로필 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.", error)
                }
            )
        }
    )
}

class UserProfileException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
