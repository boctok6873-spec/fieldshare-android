package com.youngsu.fieldshare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

private val accountQueueMonitors = ConcurrentHashMap<String, Any>()

/** Coordinates repository controls and WorkManager across threads/processes for one account. */
internal fun <T> withAccountQueueLock(directory: File, action: () -> T): T {
    val canonical = directory.canonicalPath
    val monitor = accountQueueMonitors.computeIfAbsent(canonical) { Any() }
    synchronized(monitor) {
        val lockFile = File(directory, "queue.lock").toPath()
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return action() }
        }
    }
}

private fun queueAccountDirectory(planFile: File) = planFile.parentFile?.parentFile
internal fun ensureQueueActive(planFile: File) {
    val status = runCatching { JSONObject(planFile.readText()).optString("syncStatus") }.getOrDefault("")
    if (status == PrivateSyncStatus.CANCELLED.name) throw CancellationException("개인 자료 동기화가 취소되었습니다.")
}

/** Never let a stale uploader snapshot replace a cancellation that was committed meanwhile. */
internal fun writeActiveQueuePlan(planFile: File, plan: JSONObject) {
    val account = queueAccountDirectory(planFile) ?: return writePrivatePlan(planFile, plan)
    withAccountQueueLock(account) {
        val latest = JSONObject(planFile.readText())
        if (latest.optString("syncStatus") == PrivateSyncStatus.CANCELLED.name) {
            throw CancellationException("개인 자료 동기화가 취소되었습니다.")
        }
        plan.put("syncStatus", latest.optString("syncStatus", PrivateSyncStatus.SYNCING.name))
        writePrivatePlan(planFile, plan)
    }
}

/** Atomically retain retry state and the last user-actionable failure alongside the upload plan. */
internal fun writePendingPlan(directory: File, plan: JSONObject) {
    writePrivatePlan(File(directory, "pending.json"), plan)
}

internal fun writePrivatePlan(target: File, plan: JSONObject) {
    val temp = File(target.parentFile, "${target.name}.write")
    temp.outputStream().use { output -> output.write(plan.toString().toByteArray()); output.fd.sync() }
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

internal fun updatePendingSyncState(directory: File, status: PrivateSyncStatus, error: String? = null) {
    val file = File(directory, "pending.json")
    if (!file.exists()) return
    val plan = JSONObject(file.readText()).put("syncStatus", status.name)
    if (error.isNullOrBlank()) plan.remove("syncError") else plan.put("syncError", error)
    writePendingPlan(directory, plan)
}

/** Never derive a recursive-delete target from an absent or malformed journal field. */
internal fun cleanupCompletedQueueAttachments(planFile: File, plan: JSONObject) {
    val jobId = plan.optString("jobId")
    if (!jobId.matches(Regex("[0-9a-fA-F-]{16,}"))) return
    val queue = planFile.parentFile?.canonicalFile ?: return
    if (queue.name != "queue") return
    val job = File(queue, jobId).canonicalFile
    if (job.parentFile != queue || !job.isDirectory) return
    job.deleteRecursively()
}

internal fun privateFileMetadata(folder: String, documentId: String, kind: String, name: String) = JSONObject()
    .put("name", name).put("parents", JSONArray(listOf(folder)))
    .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", kind).put("documentId", documentId))

/** Upload the persisted transaction; never publish metadata before every attachment has succeeded. */
internal class PrivateDriveUploads(private val api: PrivateDriveApi, private val local: PrivateMetadataCache) {
    suspend fun uploadPending(file: File = File(local.directory, "pending.json")) {
        val plan = JSONObject(file.readText())
        val folder = plan.getString("folder")
        var document = PrivateDocument.parse(plan.getJSONObject("document"), plan.getString("metadataId"))
        if (document.lineageId.isBlank()) {
            ensureQueueActive(file)
            document = document.copy(schemaVersion = 2, lineageId = api.generateId())
            plan.put("document", document.json())
            writeActiveQueuePlan(file, plan)
        }
        val attachments = plan.getJSONArray("attachments")
        repeat(attachments.length()) { index ->
            ensureQueueActive(file)
            val entry = attachments.getJSONObject(index)
            val metadata = privateFileMetadata(folder, document.id, "attachment", "${document.id}-$index")
            if (entry.has("sourceId")) api.copy(entry.getString("sourceId"), entry.getString("id"), metadata)
            else api.create(entry.getString("id"), metadata,
                File(file.parentFile, entry.getString("local")).readBytes(), entry.getString("mime"))
        }
        val lineage = PrivateLineage.of(document)
        ensureQueueActive(file)
        api.create(document.fileId, privateFileMetadata(folder, document.id, "metadata", "${document.id}-${document.revision}.json"), document.json().toString().toByteArray())
        // The commit receipt is written last: a cancelled, never-published revision must not
        // suppress its parent. Neither upload alone is reported as a completed save.
        ensureQueueActive(file)
        api.create(lineage.ledgerId, privateFileMetadata(folder, document.id, "lineage", "${document.id}-${document.revision}.lineage.json"), lineage.json().toString().toByteArray())
        local.lineages[document.fileId] = lineage
        local.revisions[document.fileId] = document
        local.cleanup.addAll(plan.optJSONArray("cleanup")?.strings().orEmpty())
        local.save()
        if (plan.optString("operation") == "DELETE") {
            // The worker now performs cleanup. Keep this durable marker so a process death does
            // not turn a partially deleted document into a completed operation.
            plan.put("deleteCommitted", true)
            writeActiveQueuePlan(file, plan)
            return
        }
        check(file.delete()) { "저장 완료 기록 정리가 필요합니다. 재시도해 주세요." }
        repeat(attachments.length()) { attachments.getJSONObject(it).optString("local").takeIf { name -> name.isNotBlank() }
            ?.let { name -> File(file.parentFile, name).delete() } }
        cleanupCompletedQueueAttachments(file, plan)
    }
}
