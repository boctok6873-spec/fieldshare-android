package com.youngsu.fieldshare

import android.accounts.Account
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.lifecycle.Observer
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class DriveUiState(
    val email: String? = null,
    val accountKey: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val syncError: String? = null,
    val complete: Boolean = false,
    val initialListReady: Boolean = false,
    val lastSync: Long = 0,
    val documents: List<PrivateDocument> = emptyList(),
    val message: String? = null,
    val messageIsError: Boolean = false,
    val pendingSave: Boolean = false,
    val pendingSyncStatus: PrivateSyncStatus? = null,
    val pendingSyncError: String? = null,
    val deleting: Boolean = false,
    val deleteSyncError: String? = null,
    val deleteRequiresReauthorization: Boolean = false,
    val verified: Boolean = false,
    val pendingCleanup: Int = 0
)

/** Small, testable gate used so repeated lifecycle/recomposition callbacks cannot start two scans. */
internal class BackgroundSyncGate {
    private var active = false
    @Synchronized fun tryAcquire(): Boolean = if (active) false else { active = true; true }
    @Synchronized fun release() { active = false }
}

/** An in-flight sync snapshot is authoritative over a store reloaded by a queue observer. */
internal fun authoritativePrivateStore(activeSync: PrivateDriveStore?, current: PrivateDriveStore?): PrivateDriveStore? =
    activeSync ?: current

/** This component intentionally has no Firebase, profile, push, presence, or analytics dependencies. */
internal class DriveConnectionRepository(private val context: Context, apiOverride: PrivateDriveApi? = null) {
    private val root = File(context.noBackupFilesDir, "private-drive").apply { mkdirs() }
    private val viewCache = File(context.cacheDir, "private-drive-view")
    private val identityFile = AtomicFile(File(root, "connection.json"))
    private val authorization = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()
    private val observedQueueKeys = mutableSetOf<String>()
    private var stableId: String? = null
    private var accessToken: String? = null
    private var tokenTime = 0L
    private var store: PrivateDriveStore? = null
    /** Non-null while a foreground/background sync owns the in-memory snapshot. */
    @Volatile private var activeSyncStore: PrivateDriveStore? = null
    private val mutable = MutableStateFlow(DriveUiState())
    val state = mutable.asStateFlow()
    private val api = apiOverride ?: DriveApi { freshToken() }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundSyncGate = BackgroundSyncGate()
    @Volatile private var backgroundSyncJob: Job? = null
    @Volatile private var cleanupJob: Job? = null

    init {
        runCatching {
            val identity = JSONObject(String(identityFile.readFully()))
            stableId = identity.getString("id")
            val key = accountCacheKey(checkNotNull(stableId))
            store = PrivateDriveStore(File(root, key))
            mutable.value = DriveUiState(email = identity.getString("email"), accountKey = key,
                message = "Drive 권한 확인 대기 · 동기화된 자료는 오프라인 검색 가능")
            publish(store)
            // A journal survives process death. Silent authorization, when still granted, lets
            // a restarted app resume it without making the user reopen the registration screen.
            observeQueue(key); enqueueQueueWork(key)
            startBackgroundSync()
        }
    }

    fun request(selectAccount: Boolean): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setOptOutIncludingGrantedScopes(true)
            .setRequestedScopes(listOf(Scope(DriveFileScope), Scope("openid"), Scope("email")))
        if (selectAccount) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else state.value.email?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }

    suspend fun beginAuthorization(selectAccount: Boolean): AuthorizationResult {
        check(!state.value.busy) { "현재 작업이 끝난 뒤 다시 시도해 주세요." }
        mutable.value = state.value.copy(busy = true, message = "Google Drive 권한 확인 중…")
        return authorization.authorize(request(selectAccount)).await()
    }
    fun authorizationCancelled() { mutable.value = state.value.copy(busy = false, message = "Drive 연결이 취소되었습니다. 공유 자료는 계속 사용할 수 있습니다.") }
    fun authorizationFailed() { mutable.value = state.value.copy(busy = false, message = "Drive 연결을 완료하지 못했습니다. 권한과 Google Cloud 설정을 확인해 주세요.", messageIsError = true) }

    @Suppress("DEPRECATION")
    suspend fun finishAuthorization(result: AuthorizationResult) {
        mutex.withLock {
            try {
            require(!result.hasResolution() && DriveFileScope in result.grantedScopes)
            val token = checkNotNull(result.accessToken)
            val info = DriveApi { token }.userInfo()
            // AuthorizationResult may omit user information. Its SDK ID, when present, must agree
            // with Google's stable OpenID subject returned for the exact Drive token.
            val sdkId = authorizedAccountId(result.toGoogleSignInAccount()?.id,
                info.getString("sub"), info.optBoolean("email_verified"), result.grantedScopes)
            val email = info.getString("email")
            require(info.optBoolean("email_verified"))
            withContext(Dispatchers.IO) {
                if (stableId != sdkId) {
                    val previousStore = store
                    check(previousStore == null || (privateQueueFiles(previousStore.directory).isEmpty() && !File(previousStore.directory, "pending.json").exists())) {
                        "동기화되지 않은 내 자료가 있어 계정을 변경할 수 없습니다. 동기화를 완료하거나 취소해 주세요."
                    }
                    stableId?.let { PrivateRegistrationContext.clearInactiveAccount(context, accountCacheKey(it)) }
                    store?.directory?.deleteRecursively()
                    viewCache.deleteRecursively()
                    mutable.value = DriveUiState(busy = true)
                }
                stableId = sdkId; accessToken = token; tokenTime = System.currentTimeMillis()
                val key = accountCacheKey(sdkId)
                store = PrivateDriveStore(File(root, key))
                val output = identityFile.startWrite()
                try { output.write(JSONObject().put("id", sdkId).put("email", email).toString().toByteArray()); identityFile.finishWrite(output) }
                catch (e: Exception) { identityFile.failWrite(output); throw e }
                mutable.value = DriveUiState(email = email, accountKey = key, verified = true)
                val resumedDelete = resumeDeleteCleanup(store!!)
                publish(store); observeQueue(key)
                if (resumedDelete) enqueueDeleteWork(key) else enqueueQueueWork(key)
            }
            } finally { mutable.value = state.value.copy(busy = false) }
        }
        // Authentication is complete as soon as the token has been validated and the
        // connection record is durable. The potentially large Drive scan is independent.
        startBackgroundSync()
    }

    /** Starts at most one full/incremental scan. It survives recomposition and is safe to call on re-entry. */
    fun startBackgroundSync() {
        if (state.value.accountKey == null) return
        if (!backgroundSyncGate.tryAcquire()) return
        synchronized(this) {
            if (backgroundSyncJob?.isActive == true) { backgroundSyncGate.release(); return }
            mutable.value = state.value.copy(syncing = true, syncError = null)
            backgroundSyncJob = backgroundScope.launch {
                try { mutex.withLock {
                    try {
                        val local = store ?: throw DriveFailure(401)
                        activeSyncStore = local
                        runDiagnosedSync(local, DriveSyncOrigin.AUTOMATIC)
                        mutable.value = state.value.copy(verified = true, syncError = null)
                        publish(local)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is DriveFailure && (e.status == 401 || e.status == 403)) {
                            accessToken?.let { runCatching { authorization.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() } }
                            accessToken = null
                            mutable.value = state.value.copy(verified = false)
                        }
                        val message = if (e is DriveFailure && (e.status == 401 || e.status == 403)) {
                            "Drive 권한 확인이 필요합니다. 다시 인증해 주세요."
                        } else {
                            e.message ?: "Drive 자료 동기화에 실패했습니다. 다시 동기화해 주세요."
                        }
                        mutable.value = state.value.copy(syncError = message)
                    } finally {
                        activeSyncStore = null
                        mutable.value = state.value.copy(syncing = false)
                    }
                } } finally { backgroundSyncGate.release() }
            }
        }
    }

    private suspend fun freshToken(): String {
        if (accessToken != null && System.currentTimeMillis() - tokenTime < 45 * 60_000L) return checkNotNull(accessToken)
        if (state.value.email == null) throw DriveFailure(401)
        val result = try { authorization.authorize(request(false)).await() }
        catch (e: ApiException) { throw DriveFailure(if (e.statusCode == 7) 503 else 401) }
        if (result.hasResolution() || DriveFileScope !in result.grantedScopes) throw DriveFailure(401)
        val token = result.accessToken ?: throw DriveFailure(401)
        // Validate the identity bound to the exact token, including silent authorization after restart.
        if (DriveApi { token }.userInfo().getString("sub") != stableId) throw DriveFailure(401)
        accessToken = token; tokenTime = System.currentTimeMillis()
        return token
    }

    private fun publish(explicit: PrivateDriveStore? = null) {
        val local = authoritativePrivateStore(explicit ?: activeSyncStore, store) ?: return
        val pending = queuedPrivateSaves(local.directory)
        val deletePlan = privateQueueFiles(local.directory).asSequence().mapNotNull { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()
        }.firstOrNull { it.optString("operation") == "DELETE" }
        val deleteStatus = deletePlan?.let { runCatching { PrivateSyncStatus.valueOf(it.optString("syncStatus")) }.getOrNull() }
        val legacy = pendingPrivateSave(local.directory)?.let(::listOf).orEmpty()
        val displayed = local.revisions.values.toMutableList().apply { addAll(pending.map { it.document }); addAll(legacy.map { it.document }) }
        val foreground = (pending + legacy).maxByOrNull { it.document.modified }
        mutable.value = state.value.copy(documents = privateHeads(displayed),
            complete = local.initialSyncComplete, lastSync = local.lastSync,
            initialListReady = local.initialListReady,
            pendingSave = pending.isNotEmpty() || legacy.isNotEmpty() || File(local.directory, "pending.json").exists(),
            pendingSyncStatus = foreground?.status, pendingSyncError = foreground?.error,
            deleting = deletePlan != null,
            deleteSyncError = deletePlan?.optString("syncError")?.takeIf { it.isNotBlank() && deleteStatus in setOf(PrivateSyncStatus.FAILED, PrivateSyncStatus.ACTION_REQUIRED) },
            deleteRequiresReauthorization = deleteStatus == PrivateSyncStatus.ACTION_REQUIRED)
        mutable.value = state.value.copy(pendingCleanup = local.cleanup.size)
    }
    private suspend fun <T> operation(syncing: Boolean = false, action: suspend (PrivateDriveStore) -> T): Result<T> = mutex.withLock {
        mutable.value = state.value.copy(busy = true, syncing = syncing, message = null, messageIsError = false,
            syncError = if (syncing) null else state.value.syncError)
        var operationStore: PrivateDriveStore? = null
        try {
            val local = store ?: throw DriveFailure(401)
            operationStore = local
            val value = withContext(Dispatchers.IO) { action(local) }
            publish(local); Result.success(value)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is DriveFailure && (e.status == 401 || e.status == 403)) {
                accessToken?.let { runCatching { authorization.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() } }
                accessToken = null
                mutable.value = state.value.copy(verified = false)
            }
            publish(operationStore)
            val message = if (e is DriveFailure) e.message else "인터넷 연결 또는 개인 자료 파일을 확인하고 재시도해 주세요. 저장은 완료되지 않았습니다."
            mutable.value = state.value.copy(message = message, messageIsError = true)
            Result.failure(IllegalStateException(message))
        } finally { mutable.value = state.value.copy(busy = false, syncing = if (syncing) false else state.value.syncing) }
    }

    suspend fun sync(): Result<Unit> {
        if (backgroundSyncJob?.isActive == true) return Result.success(Unit)
        return operation(syncing = true) { local ->
            activeSyncStore = local
            try { runDiagnosedSync(local, DriveSyncOrigin.MANUAL) }
            finally { activeSyncStore = null }
            mutable.value = state.value.copy(verified = true)
        }
    }

    /** Keeps automatic and user-requested scans comparable in Logcat without exposing private data. */
    private suspend fun runDiagnosedSync(local: PrivateDriveStore, origin: DriveSyncOrigin) {
        val diagnosedApi = DiagnosticPrivateDriveApi(api, origin)
        val metrics = PrivateSyncMetrics()
        var success = false
        try {
            PrivateDriveSync(diagnosedApi, local, metrics).apply { sync { publish(local) }; retryCleanup() }
            success = true
            scheduleObsoleteCleanup(local, diagnosedApi)
        } finally {
            val heads = privateHeads(local.revisions.values)
            val snapshot = metrics.snapshot()
            diagnosedApi.finish(success, snapshot, local.revisions.size, heads.size, searchPrivateDocuments(heads, "").size)
        }
    }

    /** Cleanup is independent of list readiness and serialized with all store mutations. */
    private fun scheduleObsoleteCleanup(local: PrivateDriveStore, cleanupApi: PrivateDriveApi) {
        synchronized(this) {
            if (cleanupJob?.isActive == true) return
            cleanupJob = backgroundScope.launch {
                try {
                    mutex.withLock {
                        cleanupObsoletePrivateDrive(cleanupApi, local)
                        publish(local)
                    }
                } finally { cleanupJob = null }
            }
        }
    }

    suspend fun disconnect() {
        check(!state.value.pendingSave) { "미완료 저장을 재시도하거나 취소한 뒤 연결을 해제해 주세요." }
        backgroundSyncJob?.cancel()
        backgroundSyncJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        mutex.withLock {
            check(!state.value.pendingSave) { "미완료 저장을 재시도하거나 취소한 뒤 연결을 해제해 주세요." }
            val previousKey = state.value.accountKey
            accessToken?.let { runCatching { authorization.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() } }
            accessToken = null; stableId = null
            mutable.value = DriveUiState(message = "Drive 연결 해제됨 · 원본 자료는 Drive에 유지됩니다.")
            withContext(Dispatchers.IO) {
                store?.directory?.deleteRecursively(); identityFile.delete(); viewCache.deleteRecursively()
                previousKey?.let { PrivateRegistrationContext.clearInactiveAccount(context, it) }
            }
            store = null
        }
    }

    suspend fun save(document: FieldDocument, ocr: String, original: PrivateDocument? = null,
        resolveHeads: Set<String>? = null, pinned: Boolean? = null): Result<Unit> {
        val result = operation { local ->
        // This section deliberately has no Drive API calls. It is the user-visible save boundary.
        if (original != null && resolveHeads == null && local.revisions[original.fileId] != original) throw DriveFailure(409)
        if (original != null && (original.remoteState == PrivateRemoteState.MISSING || original.remoteState == PrivateRemoteState.TRASHED ||
            (original.remoteState != PrivateRemoteState.AVAILABLE && resolveHeads == null))) throw DriveFailure(409)
        val jobId = UUID.randomUUID().toString()
        val jobDirectory = privateQueueDirectory(local.directory).resolve(jobId).apply { mkdirs() }
        val id = original?.id ?: UUID.randomUUID().toString()
        val fileId = "local-meta-$jobId"
        val attachmentPlans = JSONArray()
        // Each immutable revision owns its originals. Drive performs copies without downloading
        // bytes, so a concurrent deletion cannot remove another revision's attachments.
        val attachments = if (original != null) original.attachments.map { attachment ->
            val copyId = "local-${UUID.randomUUID()}"
            attachmentPlans.put(JSONObject().put("id", copyId).put("sourceId", attachment.id).put("mime", attachment.mime))
            PrivateAttachment(copyId, attachment.mime)
        } else buildList {
            val uris = document.imageUris.ifEmpty { listOfNotNull(document.imageUri) } + listOfNotNull(document.pdfUri)
            for ((index, uriString) in uris.withIndex()) {
                val uri = Uri.parse(uriString)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val attachmentId = "local-${UUID.randomUUID()}"
                val file = File(jobDirectory, "attachment-$index")
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                require(file.length() <= 25 * 1024 * 1024) { "첨부파일은 25MB 이하로 등록해 주세요." }
                add(PrivateAttachment(attachmentId, mime))
                attachmentPlans.put(JSONObject().put("id", attachmentId).put("mime", mime).put("local", "$jobId/${file.name}"))
            }
        }
        val now = System.currentTimeMillis()
        val revision = PrivateDocument(id, UUID.randomUUID().toString(), resolveHeads?.toList() ?: listOfNotNull(original?.revision), document.title,
            document.content, document.category, original?.created ?: now, now, original?.ocr ?: ocr, attachments, fileId = fileId,
            pinned = pinned ?: original?.pinned ?: false)
        val plan = JSONObject().put("jobId", jobId).put("accountKey", state.value.accountKey).put("folder", "")
            .put("metadataId", "").put("document", revision.json()).put("attachments", attachmentPlans)
        // From this point the attachment copies and immutable revision are durable locally.
        // Saving therefore succeeds even if Drive is offline; upload is queued below.
        plan.put("operation", "SAVE").put("syncStatus", PrivateSyncStatus.WAITING.name)
            .put("baseRevisions", JSONArray(resolveHeads?.toList() ?: listOfNotNull(original?.revision))).put("baseFileId", original?.fileId ?: "")
        try { writePrivatePlan(privateQueueDirectory(local.directory).resolve("$jobId.json"), plan) }
        catch (e: Exception) { jobDirectory.deleteRecursively(); throw e }
        }
        if (result.isSuccess) {
            enqueueQueueWork(checkNotNull(state.value.accountKey))
            mutable.value = state.value.copy(message = null, messageIsError = false)
        }
        return result
    }

    /** Loads one summary-only row on demand when the user opens it before background hydrate finishes. */
    suspend fun hydrateDetails(document: PrivateDocument): Result<Unit> = operation { local ->
        val current = local.revisions[document.fileId] ?: document
        if (current.detailsLoaded) return@operation
        val loaded = PrivateDocument.parse(JSONObject(String(api.read(document.fileId))), document.fileId)
        require(loaded.id == document.id) { "개인 자료 메타데이터가 일치하지 않습니다." }
        local.revisions[document.fileId] = loaded.copy(
            thumbnailId = current.thumbnailId,
            remoteState = PrivateRemoteState.LEGACY_UNVERIFIED,
            detailsLoaded = true
        )
    }

    /** Pinning is an immutable metadata revision, queued identically to an offline text edit. */
    suspend fun togglePin(document: PrivateDocument): Result<Unit> =
        save(document.field(), document.ocr, original = document, pinned = !document.pinned)

    private suspend fun uploadPending(local: PrivateDriveStore) {
        PrivateDriveUploads(api, local).uploadPending()
        publish(local)
    }
    private fun enqueueQueueWork(accountKey: String) {
        val request = OneTimeWorkRequestBuilder<PrivateDriveUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(PrivateDriveUploadWorker.KEY_ACCOUNT to accountKey)).build()
        // A request enqueued while another consumer is running is chained behind it. This closes
        // the final-scan race without allowing two consumers for the same account.
        WorkManager.getInstance(context).enqueueUniqueWork("private-drive-upload-$accountKey", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    private fun enqueueDeleteWork(accountKey: String) {
        val request = OneTimeWorkRequestBuilder<PrivateDriveUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(PrivateDriveUploadWorker.KEY_ACCOUNT to accountKey, PrivateDriveUploadWorker.KEY_DELETE_ONLY to true)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("private-drive-upload-$accountKey", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    private fun observeQueue(accountKey: String) {
        context.mainExecutor.execute {
            if (!observedQueueKeys.add(accountKey)) return@execute
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData("private-drive-upload-$accountKey")
                .observeForever(Observer {
                    if (state.value.accountKey == accountKey) {
                        backgroundScope.launch {
                            mutex.withLock {
                                if (state.value.accountKey != accountKey) return@withLock
                                val active = authoritativePrivateStore(activeSyncStore, null)
                                if (active != null) {
                                    // The sync owner remains authoritative while it is active.
                                    publish(active)
                                } else {
                                    val current = store ?: return@withLock
                                    val refreshed = PrivateDriveStore(current.directory)
                                    store = refreshed
                                    publish(refreshed)
                                }
                            }
                        }
                    }
                })
        }
    }
    suspend fun retrySave() = operation { local ->
        withAccountQueueLock(local.directory) { privateQueueFiles(local.directory).forEach { file ->
            val plan = JSONObject(file.readText())
            if (plan.optString("syncStatus") in setOf(PrivateSyncStatus.FAILED.name, PrivateSyncStatus.ACTION_REQUIRED.name)) {
                plan.put("syncStatus", PrivateSyncStatus.WAITING.name).remove("syncError")
                writePrivatePlan(file, plan)
            }
        } }
        enqueueQueueWork(checkNotNull(state.value.accountKey))
    }
    /** Resume the exact DELETE journal and its persisted cleanup targets; do not create an upload. */
    suspend fun retryDelete(): Result<Unit> {
        val result = operation { local ->
            withAccountQueueLock(local.directory) { privateQueueFiles(local.directory).forEach { file ->
                val plan = JSONObject(file.readText())
                if (resumeDeletePlan(plan)) writePrivatePlan(file, plan)
            } }
        }
        if (result.isSuccess) enqueueDeleteWork(checkNotNull(state.value.accountKey))
        return result
    }
    private fun resumeDeleteCleanup(local: PrivateDriveStore): Boolean {
        var resumed = false
        withAccountQueueLock(local.directory) { privateQueueFiles(local.directory).forEach { file ->
            val plan = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            if (resumeDeletePlan(plan)) { writePrivatePlan(file, plan); resumed = true }
        } }
        return resumed
    }
    suspend fun cancelSave() = operation { local ->
        val queueFiles = privateQueueFiles(local.directory)
        if (queueFiles.isNotEmpty()) {
            val key = checkNotNull(state.value.accountKey)
            WorkManager.getInstance(context).cancelUniqueWork("private-drive-upload-$key")
            withAccountQueueLock(local.directory) { privateQueueFiles(local.directory).forEach { queueFile ->
                val plan = JSONObject(queueFile.readText())
                plan.put("syncStatus", PrivateSyncStatus.CANCELLED.name).remove("syncError")
                writePrivatePlan(queueFile, plan)
            } }
            // A fresh single consumer performs only safe local cancellation cleanup.
            enqueueQueueWork(key)
            return@operation
        }
        val file = File(local.directory, "pending.json")
        if (file.exists()) {
            val plan = JSONObject(file.readText())
            // A lost response may have committed the metadata. Synchronize before deciding to cancel.
            PrivateDriveSync(api, local).sync { publish(local) }
            val published = local.revisions[plan.getString("metadataId")]
            if (published == null || published.remoteState == PrivateRemoteState.MISSING || published.remoteState == PrivateRemoteState.TRASHED) {
                val entries = plan.getJSONArray("attachments")
                repeat(entries.length()) { local.cleanup += entries.getJSONObject(it).getString("id") }
                local.save()
            } else {
                // A metadata response was lost: finish its content-free commit receipt first.
                uploadPending(local)
                local.cleanup.addAll(plan.optJSONArray("cleanup")?.strings().orEmpty())
                local.save()
            }
            file.delete()
            local.directory.listFiles()?.filter { it.name.startsWith("upload-") }?.forEach { it.delete() }
            PrivateDriveSync(api, local).retryCleanup()
        }
    }

    suspend fun delete(document: PrivateDocument): Result<Unit> {
        val result = operation { local ->
        check(!File(local.directory, "pending.json").exists())
        if (local.revisions[document.fileId] != document) throw DriveFailure(409)
        if (document.remoteState != PrivateRemoteState.AVAILABLE) throw DriveFailure(409)
        val targets = local.revisions.values.filter { it.id == document.id }
        // The local journal is the user-visible deletion boundary. Remote IDs and folder lookup
        // happen later in the worker, so this survives offline navigation and process death.
        val jobId = UUID.randomUUID().toString()
        val tombstoneId = "local-meta-$jobId"
        val tombstone = PrivateDocument(document.id, UUID.randomUUID().toString(), listOf(document.revision),
            "", "", "", document.created, System.currentTimeMillis(), deleted = true, fileId = tombstoneId)
        val cleanup = targets.flatMap { it.attachments.map { a -> a.id } }.distinct() + targets.map { it.fileId }
        val plan = JSONObject().put("jobId", jobId).put("accountKey", state.value.accountKey).put("folder", "")
            .put("metadataId", "").put("document", tombstone.json()).put("attachments", JSONArray())
            .put("cleanup", JSONArray(cleanup)).put("operation", "DELETE").put("syncStatus", PrivateSyncStatus.WAITING.name)
        writePrivatePlan(privateQueueDirectory(local.directory).resolve("$jobId.json"), plan)
        }
        if (result.isSuccess) enqueueQueueWork(checkNotNull(state.value.accountKey))
        return result
    }

    private suspend fun cachedOriginal(local: PrivateDriveStore, attachment: PrivateAttachment): File {
        // New queue entries own a durable local copy, so listing/detail works before Drive exists.
        privateQueueFiles(local.directory).forEach { planFile ->
            val localCopy = runCatching {
                val plan = JSONObject(planFile.readText())
                val entries = plan.getJSONArray("attachments")
                (0 until entries.length()).map { entries.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == attachment.id }
                    ?.optString("local")?.takeIf { it.isNotBlank() }
                    ?.let { File(planFile.parentFile, it) }
            }.getOrNull()
            if (localCopy?.isFile == true) return localCopy
        }
        val file = File(local.directory, "originals/${accountCacheKey(attachment.id)}")
        if (!file.exists()) {
            val bytes = api.read(attachment.id)
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
        }
        // Bounded on-demand cache: keep the opened file and remove older originals above 100 MB.
        file.setLastModified(System.currentTimeMillis())
        var total = file.parentFile!!.listFiles().orEmpty().sumOf { it.length() }
        for (old in file.parentFile!!.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if (total <= 100L * 1024 * 1024) break
            if (old != file) { total -= old.length(); old.delete() }
        }
        return file
    }

    /** List thumbnails share the bounded original cache but do not change the foreground operation state. */
    suspend fun thumbnail(attachment: PrivateAttachment): Result<File> = mutex.withLock {
        runCatching {
            require(attachment.mime.startsWith("image/"))
            val local = store ?: throw DriveFailure(401)
            withContext(Dispatchers.IO) { cachedOriginal(local, attachment) }
        }
    }

    /** Fetches only the app-owned small JPEG. It is authenticated through DriveApi's Bearer token. */
    suspend fun thumbnail(document: PrivateDocument): Result<File> = mutex.withLock {
        runCatching {
            require(document.thumbnailId.isNotBlank())
            val local = store ?: throw DriveFailure(401)
            val file = File(local.directory, "thumbnails/${accountCacheKey(document.thumbnailId)}.jpg")
            if (!file.exists()) {
                val bytes = api.read(document.thumbnailId)
                file.parentFile!!.mkdirs()
                val atomic = AtomicFile(file); val output = atomic.startWrite()
                try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
            }
            file.setLastModified(System.currentTimeMillis())
            file
        }
    }

    suspend fun original(attachment: PrivateAttachment): Result<File> = operation { local ->
        val file = cachedOriginal(local, attachment)
        val viewFile = File(viewCache, "${state.value.accountKey}/${accountCacheKey(attachment.id)}")
        viewFile.parentFile!!.mkdirs()
        viewFile.parentFile!!.listFiles()?.filter { it != viewFile }?.forEach { it.delete() }
        file.copyTo(viewFile, overwrite = true)
        viewFile
    }
}

/** Mutates only a failed DELETE journal, retaining remote IDs, commit phase, and cleanup targets. */
internal fun resumeDeletePlan(plan: JSONObject): Boolean {
    if (plan.optString("operation") != "DELETE" || plan.optString("syncStatus") !in setOf(
            PrivateSyncStatus.FAILED.name, PrivateSyncStatus.ACTION_REQUIRED.name
        )) return false
    plan.put("syncStatus", PrivateSyncStatus.WAITING.name).remove("syncError")
    return true
}
