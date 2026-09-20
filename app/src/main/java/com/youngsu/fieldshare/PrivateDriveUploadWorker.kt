package com.youngsu.fieldshare

import android.accounts.Account
import android.content.Context
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

/** Durable, account-scoped queue consumer. WorkManager supplies network gating and process recovery. */
internal class PrivateDriveUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val root = File(applicationContext.noBackupFilesDir, "private-drive")
        val identity = runCatching { JSONObject(String(AtomicFile(File(root, "connection.json")).readFully())) }.getOrNull()
            ?: return Result.success()
        if (accountCacheKey(identity.optString("id")) != key) return Result.success()
        val store = PrivateDriveStore(File(root, key))
        withAccountQueueLock(store.directory) { migrateLegacy(store) }
        return consume(store, identity, key, inputData.getBoolean(KEY_DELETE_ONLY, false))
    }

    private suspend fun consume(store: PrivateDriveStore, identity: JSONObject, key: String, deleteOnly: Boolean): Result {
        val api = DriveApi { token(identity, key) }
        for (file in privateQueueFiles(store.directory).filter { file ->
            !deleteOnly || runCatching { JSONObject(file.readText()).optString("operation") == "DELETE" }.getOrDefault(false)
        }) {
            val plan = withAccountQueueLock(store.directory) { claim(file) }
            // A corrupt transaction is evidence, not disposable cache. Preserve it for recovery.
            if (plan == null) continue
            try {
                if (plan.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) {
                    reconcileCancelledUpload(api, store, file, plan); continue
                }
                if (plan.optString("operation") == "DELETE" && plan.optBoolean("deleteCommitted")) {
                    PrivateDriveSync(api, store).retryCleanup()
                    completeDeleteQueue(file)
                    continue
                }
                hydrate(api, store, file, plan)
                PrivateDriveUploads(api, store).uploadPending(file)
                if (plan.optString("operation") == "DELETE") {
                    PrivateDriveSync(api, store).retryCleanup()
                    completeDeleteQueue(file)
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: DriveFailure) {
                if (plan.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) {
                    recordCancelledCleanupFailure(file, failure)
                    if (failure.status == 401 || failure.status == 403) continue
                    return Result.retry()
                }
                if (failure.status == 401 || failure.status == 403 || failure.status == 409) {
                    mark(file, PrivateSyncStatus.ACTION_REQUIRED, failure.message)
                    continue
                }
                mark(file, PrivateSyncStatus.FAILED, failure.message)
                return Result.retry()
            } catch (failure: Exception) {
                if (plan.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) {
                    recordCancelledCleanupFailure(file, failure)
                    return Result.retry()
                }
                mark(file, PrivateSyncStatus.FAILED, "인터넷 연결을 확인한 뒤 다시 시도해 주세요.")
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun token(identity: JSONObject, key: String): String {
        val request = AuthorizationRequest.builder().setOptOutIncludingGrantedScopes(true)
            .setAccount(Account(identity.getString("email"), "com.google"))
            .setRequestedScopes(listOf(Scope(DriveFileScope), Scope("openid"), Scope("email"))).build()
        val result = try { Identity.getAuthorizationClient(applicationContext).authorize(request).await() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw DriveFailure(401) }
        if (result.hasResolution() || DriveFileScope !in result.grantedScopes) throw DriveFailure(401)
        val token = result.accessToken ?: throw DriveFailure(401)
        if (accountCacheKey(DriveApi { token }.userInfo().getString("sub")) != key) throw DriveFailure(401)
        return token
    }

    private suspend fun hydrate(api: PrivateDriveApi, store: PrivateDriveStore, file: File, plan: JSONObject) {
        val sync = PrivateDriveSync(api, store)
        // Re-read Drive before publishing an edit. A changed head is surfaced as user action,
        // never silently overwritten by an offline revision.
        sync.sync {}
        val bases = plan.optJSONArray("baseRevisions")?.strings()?.toSet()
            ?: plan.optString("baseRevision").takeIf { it.isNotBlank() }?.let(::setOf).orEmpty()
        if (bases.isNotEmpty()) {
            val documentId = plan.getJSONObject("document").getString("id")
            val heads = privateHeads(store.revisions.values).filter { it.id == documentId }.map { it.revision }.toSet()
            val queuedRevision = plan.getJSONObject("document").getString("revision")
            if (queuedRevision !in heads && heads != bases) throw DriveFailure(409)
        }
        if (plan.optString("folder").isBlank()) plan.put("folder", sync.folder())
        var document = PrivateDocument.parse(plan.getJSONObject("document"), plan.optString("metadataId"))
        if (plan.optString("metadataId").isBlank()) {
            val id = api.generateId(); plan.put("metadataId", id); document = document.copy(fileId = id)
        }
        val attachmentPlans = plan.getJSONArray("attachments")
        val attachments = document.attachments.toMutableList()
        repeat(attachmentPlans.length()) { index ->
            val entry = attachmentPlans.getJSONObject(index)
            if (entry.optString("id").startsWith("local-")) {
                val id = api.generateId(); entry.put("id", id); attachments[index] = attachments[index].copy(id = id)
            }
        }
        document = document.copy(attachments = attachments)
        plan.put("document", document.json())
        writeActiveQueuePlan(file, plan)
    }

    private fun claim(file: File): JSONObject? {
        val plan = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val status = runCatching { PrivateSyncStatus.valueOf(plan.optString("syncStatus")) }.getOrDefault(PrivateSyncStatus.WAITING)
        if (status == PrivateSyncStatus.ACTION_REQUIRED) return null
        if (status != PrivateSyncStatus.CANCELLED) {
            plan.put("syncStatus", PrivateSyncStatus.SYNCING).remove("syncError")
            writePrivatePlan(file, plan)
        }
        return plan
    }

    private fun mark(file: File, status: PrivateSyncStatus, error: String? = null) {
        // hydrate/uploader may have persisted IDs after this worker read its initial plan.
        val account = file.parentFile?.parentFile ?: return
        withAccountQueueLock(account) {
            val latest = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withAccountQueueLock
            if (latest.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) return@withAccountQueueLock
            latest.put("syncStatus", status.name)
            if (error.isNullOrBlank()) latest.remove("syncError") else latest.put("syncError", error)
            writePrivatePlan(file, latest)
        }
    }

    private fun migrateLegacy(store: PrivateDriveStore) {
        val legacy = File(store.directory, "pending.json")
        if (!legacy.exists()) return
        val plan = runCatching { JSONObject(legacy.readText()) }.getOrNull() ?: return
        if (plan.optString("operation", "SAVE") != "SAVE" || plan.getJSONObject("document").optBoolean("deleted")) return
        val document = plan.getJSONObject("document")
        val job = plan.optString("jobId").ifBlank {
            "legacy-" + accountCacheKey("${document.optString("id")}:${document.optString("revision")}:${plan.optString("metadataId")}")
        }
        val target = privateQueueDirectory(store.directory).resolve("$job.json")
        // A previous run may have committed the queue journal and then died before removing
        // pending.json. The committed journal is authoritative: never overwrite it.
        if (target.exists()) {
            finishExistingMigration(store, legacy, plan, target)
            return
        }
        val jobDirectory = privateQueueDirectory(store.directory).resolve(job).apply { mkdirs() }
        val attachments = plan.optJSONArray("attachments")
        val legacyCopies = mutableListOf<File>()
        if (attachments != null) repeat(attachments.length()) { index ->
            val entry = attachments.getJSONObject(index)
            entry.optString("local").takeIf { it.isNotBlank() }?.let { name ->
                val old = File(store.directory, name)
                if (old.isFile) {
                    val moved = File(jobDirectory, "attachment-$index")
                    old.copyTo(moved, overwrite = true)
                    legacyCopies += old
                    entry.put("local", "$job/${moved.name}")
                }
            }
        }
        plan.put("jobId", job)
            .put("syncStatus", plan.optString("syncStatus", PrivateSyncStatus.WAITING.name))
        writePrivatePlan(target, plan)
        // Only remove old evidence after queue journal and every attachment copy are durable.
        legacyCopies.forEach { it.delete() }
        legacy.delete()
    }

    private fun finishExistingMigration(store: PrivateDriveStore, legacy: File, oldPlan: JSONObject, target: File) {
        val queued = runCatching { JSONObject(target.readText()) }.getOrNull() ?: return
        val oldAttachments = oldPlan.optJSONArray("attachments") ?: run { legacy.delete(); return }
        val queuedAttachments = queued.optJSONArray("attachments") ?: return
        if (oldAttachments.length() != queuedAttachments.length()) return
        val obsolete = mutableListOf<File>(); var requiredCopies = 0
        repeat(oldAttachments.length()) { index ->
            val old = oldAttachments.getJSONObject(index).optString("local")
            val queuedLocal = queuedAttachments.getJSONObject(index).optString("local")
            if (old.isBlank()) return@repeat
            requiredCopies++
            if (queuedLocal.isBlank()) return
            val source = File(store.directory, old)
            val destination = File(target.parentFile, queuedLocal)
            if (!destination.exists() && source.isFile) {
                destination.parentFile?.mkdirs(); source.copyTo(destination, overwrite = false)
            }
            if (destination.isFile) obsolete += source
        }
        // Every local attachment must have a durable queue counterpart before old evidence goes.
        if (obsolete.size != requiredCopies) return
        obsolete.filter { it.isFile }.forEach { it.delete() }
        legacy.delete()
    }

    companion object {
        const val KEY_ACCOUNT = "accountKey"
        const val KEY_DELETE_ONLY = "deleteOnly"
    }
}

/** Delete journals remain until their Drive cleanup is confirmed, making retries process-safe. */
internal fun completeDeleteQueue(file: File) {
    val account = file.parentFile?.parentFile ?: return
    withAccountQueueLock(account) {
        val latest = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withAccountQueueLock
        if (latest.optString("operation") == "DELETE" && latest.optBoolean("deleteCommitted")) file.delete()
    }
}

/** A cancelled upload is deleted only after Drive has conclusively answered about metadata. */
internal suspend fun reconcileCancelledUpload(api: PrivateDriveApi, store: PrivateMetadataCache, file: File, plan: JSONObject) {
    val metadataId = plan.optString("metadataId")
    val published = try {
        metadataId.isNotBlank() && api.read(metadataId).isNotEmpty()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: DriveFailure) {
        if (e.status == 404) false else throw e
    } catch (e: Exception) { throw e }
    if (published) {
        val document = PrivateDocument.parse(plan.getJSONObject("document"), metadataId)
        val lineage = PrivateLineage.of(document)
        api.create(lineage.ledgerId, privateFileMetadata(plan.getString("folder"), document.id, "lineage", "${document.id}-${document.revision}.lineage.json"), lineage.json().toString().toByteArray())
        store.lineages[metadataId] = lineage; store.revisions[metadataId] = document; store.save()
    }
    withAccountQueueLock(store.directory) {
        val latest = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (latest?.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) {
            cleanupCompletedQueueAttachments(file, latest); file.delete()
        }
    }
}

/** Keep cancellation authoritative while exposing why its remote confirmation cannot proceed. */
internal fun recordCancelledCleanupFailure(file: File, failure: Exception) {
    val account = file.parentFile?.parentFile ?: return
    withAccountQueueLock(account) {
        val latest = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withAccountQueueLock
        if (latest.optString("syncStatus") != PrivateSyncStatus.CANCELLED.name) return@withAccountQueueLock
        latest.put("syncError", failure.message ?: "취소 정리를 다시 시도해 주세요.")
        if (failure is DriveFailure && (failure.status == 401 || failure.status == 403)) {
            latest.put("cleanupActionRequired", true)
        }
        writePrivatePlan(file, latest)
    }
}
