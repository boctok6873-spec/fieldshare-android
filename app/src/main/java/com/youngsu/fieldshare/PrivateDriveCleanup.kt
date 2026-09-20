package com.youngsu.fieldshare

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val PRIVATE_TOMBSTONE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
private const val CLEANUP_JOURNAL = "obsolete-cleanup.json"
private const val CLEANUP_LAST = "obsolete-cleanup.last"
private const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L

internal data class PrivateCleanupPlan(
    val candidates: Set<String>,
    val preservedTombstones: Int,
    val preservedIds: Set<String>
)

internal data class PrivateCleanupResult(
    val candidates: Int,
    val trashed: Int,
    val failed: Int,
    val preservedTombstones: Int,
    val localCacheRemoved: Int
)

private data class CleanupJournal(val remaining: LinkedHashSet<String>, val createdAt: Long) {
    fun json() = JSONObject().put("createdAt", createdAt).put("remaining", JSONArray(remaining.toList()))
}

private fun cleanupJournalFile(directory: File) = File(directory, CLEANUP_JOURNAL)
private fun cleanupLastFile(directory: File) = File(directory, CLEANUP_LAST)

private fun pendingDriveIds(directory: File): Set<String> {
    val ids = linkedSetOf<String>()
    fun collect(plan: JSONObject) {
        listOf("metadataId", "thumbnailId", "baseFileId").forEach { plan.optString(it).takeIf(String::isNotBlank)?.let(ids::add) }
        plan.optString("operation").takeIf(String::isNotBlank)?.let { }
        plan.optJSONArray("cleanup")?.strings()?.forEach(ids::add)
        plan.optJSONArray("attachments")?.let { entries -> repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            listOf("id", "sourceId").forEach { entry.optString(it).takeIf(String::isNotBlank)?.let(ids::add) }
        } }
        plan.optJSONObject("document")?.let { document ->
            document.optString("fileId").takeIf(String::isNotBlank)?.let(ids::add)
            document.optString("thumbnailId").takeIf(String::isNotBlank)?.let(ids::add)
            document.optJSONArray("attachments")?.let { entries -> repeat(entries.length()) { index ->
                entries.getJSONObject(index).optString("id").takeIf(String::isNotBlank)?.let(ids::add)
            } }
        }
    }
    privateQueueFiles(directory).forEach { file -> runCatching { collect(JSONObject(file.readText())) } }
    File(directory, "pending.json").takeIf(File::exists)?.let { runCatching { collect(JSONObject(it.readText())) } }
    return ids
}

internal fun planPrivateDriveCleanup(
    store: PrivateMetadataCache,
    now: Long = System.currentTimeMillis(),
    tombstoneRetentionMs: Long = PRIVATE_TOMBSTONE_RETENTION_MS
): PrivateCleanupPlan {
    val documents = store.revisions.values.toList()
    val activeHeads = privateHeads(documents).filter { !it.deleted }
    val retainedTombstones = privateHeads(documents).filter { it.deleted && now - it.modified < tombstoneRetentionMs }
    val retained = activeHeads + retainedTombstones
    val pending = pendingDriveIds(store.directory) + store.cleanup
    val preserved = linkedSetOf<String>().apply {
        addAll(pending)
        retained.forEach { document ->
            add(document.fileId)
            addAll(document.attachments.map { it.id })
            document.thumbnailId.takeIf(String::isNotBlank)?.let(::add)
            store.lineages[document.fileId]?.let { add(it.ledgerId) }
            document.lineageId.takeIf(String::isNotBlank)?.let(::add)
        }
    }
    val candidates = linkedSetOf<String>()
    documents.filter { it !in retained }.forEach { document ->
        listOfNotNull(document.fileId.takeIf(String::isNotBlank), document.thumbnailId.takeIf(String::isNotBlank),
            document.lineageId.takeIf(String::isNotBlank), store.lineages[document.fileId]?.ledgerId)
            .plus(document.attachments.map { it.id })
            .filter { it !in preserved }
            .forEach(candidates::add)
    }
    return PrivateCleanupPlan(candidates, retainedTombstones.size, preserved)
}

private fun readCleanupJournal(directory: File): CleanupJournal? = runCatching {
    val json = JSONObject(cleanupJournalFile(directory).readText())
    CleanupJournal(LinkedHashSet(json.optJSONArray("remaining")?.strings().orEmpty()), json.optLong("createdAt"))
}.getOrNull()

private fun writeCleanupJournal(directory: File, journal: CleanupJournal) {
    val target = cleanupJournalFile(directory)
    val temp = File(directory, "$CLEANUP_JOURNAL.write")
    temp.outputStream().use { output ->
        output.write(journal.json().toString().toByteArray()); output.fd.sync()
    }
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private fun cleanupCacheFiles(directory: File, preservedIds: Set<String>): Int {
    val preservedOriginals = preservedIds.map(::accountCacheKey).toSet()
    val preservedThumbnails = preservedIds.map { "${accountCacheKey(it)}.jpg" }.toSet()
    var removed = 0
    listOf("originals" to preservedOriginals, "thumbnails" to preservedThumbnails).forEach { (name, preserved) ->
        val dir = File(directory, name).canonicalFile
        val root = directory.canonicalFile
        if (dir.parentFile != root || !dir.isDirectory) return@forEach
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.canonicalFile.parentFile != dir) return@forEach
            if (file.name !in preserved && file.delete()) removed++
        }
    }
    return removed
}

private fun logCleanup(message: String) { runCatching { Log.i("FieldShareDriveCleanup", message) } }

/** Trashes obsolete Drive evidence; Drive permanent deletion is deliberately never used. */
internal suspend fun cleanupObsoletePrivateDrive(
    api: PrivateDriveApi,
    store: PrivateMetadataCache,
    now: Long = System.currentTimeMillis()
): PrivateCleanupResult {
    val plan = planPrivateDriveCleanup(store, now)
    val journalFile = cleanupJournalFile(store.directory)
    val last = cleanupLastFile(store.directory).takeIf(File::exists)?.readText()?.toLongOrNull() ?: 0L
    var journal = readCleanupJournal(store.directory)
    if (journal == null && now - last < CLEANUP_INTERVAL_MS) {
        return PrivateCleanupResult(0, 0, 0, plan.preservedTombstones, 0)
    }
    journal = journal ?: CleanupJournal(LinkedHashSet(plan.candidates), now)
    journal.remaining.retainAll(plan.candidates + pendingDriveIds(store.directory) + store.cleanup)
    writeCleanupJournal(store.directory, journal)
    val initial = journal.remaining.size
    var trashed = 0
    var failed = 0
    for (id in journal.remaining.toList()) {
        try {
            api.trash(id)
            journal.remaining.remove(id)
            writeCleanupJournal(store.directory, journal)
            trashed++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
            writeCleanupJournal(store.directory, journal)
            break
        }
    }
    if (journal.remaining.isNotEmpty()) {
        logCleanup("candidates=$initial trashed=$trashed failed=$failed preservedTombstones=${plan.preservedTombstones} localCacheRemoved=0")
        return PrivateCleanupResult(initial, trashed, failed, plan.preservedTombstones, 0)
    }
    val obsoleteMetadata = store.revisions.values.filter { it !in privateHeads(store.revisions.values) || it.deleted }
        .filter { it.fileId !in plan.preservedIds && it.fileId.isNotBlank() }
        .map { it.fileId }.toSet()
    obsoleteMetadata.forEach { store.revisions.remove(it); store.versions.remove(it); store.lineages.remove(it) }
    val removedCache = cleanupCacheFiles(store.directory, plan.preservedIds + pendingDriveIds(store.directory) + store.cleanup)
    store.save()
    cleanupJournalFile(store.directory).delete()
    cleanupLastFile(store.directory).writeText(now.toString())
    logCleanup("candidates=$initial trashed=$trashed failed=0 preservedTombstones=${plan.preservedTombstones} localCacheRemoved=$removedCache")
    return PrivateCleanupResult(initial, trashed, 0, plan.preservedTombstones, removedCache)
}
