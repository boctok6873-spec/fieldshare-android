package com.youngsu.fieldshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.AtomicFile

internal data class PrivateThumbnailMigrationTarget(
    val metadataId: String,
    val documentId: String,
    val revision: String,
    val attachmentId: String
)

private data class MigrationJournalEntry(
    val metadataId: String,
    val documentId: String,
    val revision: String,
    val attachmentId: String,
    val thumbnailId: String = ""
) {
    fun json() = JSONObject().put("metadataId", metadataId).put("documentId", documentId)
        .put("revision", revision).put("attachmentId", attachmentId).put("thumbnailId", thumbnailId)

    companion object {
        fun parse(json: JSONObject) = MigrationJournalEntry(
            json.getString("metadataId"), json.getString("documentId"),
            json.getString("revision"), json.getString("attachmentId"), json.optString("thumbnailId")
        )
    }
}

private const val THUMBNAIL_MIGRATION_JOURNAL = "thumbnail-migration.json"

/** Only legacy, active image heads are eligible; summaries/details and tombstones are untouched. */
internal fun legacyThumbnailMigrationTargets(store: PrivateMetadataCache): List<PrivateThumbnailMigrationTarget> {
    val protected = pendingPrivateDriveIds(store.directory) + store.cleanup
    return privateHeads(store.revisions.values).asSequence()
        .filter { !it.deleted && it.detailsLoaded && it.thumbnailId.isBlank() }
        .mapNotNull { document ->
            val attachment = document.attachments.firstOrNull { it.mime.startsWith("image/") }
                ?: return@mapNotNull null
            if (document.fileId in protected || attachment.id in protected) return@mapNotNull null
            PrivateThumbnailMigrationTarget(document.fileId, document.id, document.revision, attachment.id)
        }.toList()
}

internal fun encodePrivateListThumbnail(bytes: ByteArray): ByteArray? {
    val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
    val scale = minOf(320f / source.width, 320f / source.height, 1f)
    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source,
        (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
    return try {
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 76, output)) null else output.toByteArray()
        }
    } finally {
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
    }
}

internal suspend fun readPrivateThumbnail(
    api: PrivateDriveApi,
    directory: File,
    thumbnailId: String,
    reads: Semaphore,
    cacheLock: Any? = null,
    isCurrent: () -> Boolean = { true }
): File = reads.withPermit {
    fun cachedFile(): File? {
        check(isCurrent()) { "Drive 계정 또는 캐시 대상이 변경되었습니다." }
        val file = File(directory, "thumbnails/${accountCacheKey(thumbnailId)}.jpg")
        return file.takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }
    }
    val cached = if (cacheLock == null) cachedFile() else synchronized(cacheLock) { cachedFile() }
    if (cached != null) return@withPermit cached
    check(isCurrent()) { "Drive 계정 또는 캐시 대상이 변경되었습니다." }
    val bytes = api.read(thumbnailId)
    val write = {
        check(isCurrent()) { "Drive 계정 또는 캐시 대상이 변경되었습니다." }
        val file = File(directory, "thumbnails/${accountCacheKey(thumbnailId)}.jpg")
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }
    if (cacheLock == null) write() else synchronized(cacheLock) { write() }
}

internal fun selectPrivateThumbnailStore(
    activeSyncStore: PrivateDriveStore?,
    currentStore: PrivateDriveStore?,
    accountKey: String?
): PrivateDriveStore? {
    val selected = activeSyncStore ?: currentStore
    return selected?.takeIf { !accountKey.isNullOrBlank() && it.directory.name == accountKey }
}

internal suspend fun readPrivateDocumentThumbnail(
    api: PrivateDriveApi,
    document: PrivateDocument,
    activeSyncStore: PrivateDriveStore?,
    currentStore: PrivateDriveStore?,
    accountKey: String?,
    reads: Semaphore,
    cacheLock: Any? = null,
    snapshotIsCurrent: (PrivateDriveStore) -> Boolean = { true }
): Result<File> = runCatching {
    require(document.thumbnailId.isNotBlank())
    val selected = selectPrivateThumbnailStore(activeSyncStore, currentStore, accountKey)
        ?: throw DriveFailure(401)
    readPrivateThumbnail(api, selected.directory, document.thumbnailId, reads,
        cacheLock = cacheLock,
        isCurrent = { snapshotIsCurrent(selected) })
}

/**
 * Low-priority migration. The journal records a generated Drive ID before create(), so a
 * process death after create or PATCH retries the same file instead of generating another one.
 */
internal class PrivateDriveThumbnailMigration(
    private val api: PrivateDriveApi,
    private val directory: File,
    private val targets: suspend () -> List<PrivateThumbnailMigrationTarget>,
    private val commit: suspend (target: PrivateThumbnailMigrationTarget, thumbnailId: String) -> Unit,
    private val ready: suspend () -> Boolean = { true },
    private val encode: (ByteArray) -> ByteArray? = ::encodePrivateListThumbnail
) {
    private val reads = Semaphore(2)
    private val journalFile get() = File(directory, THUMBNAIL_MIGRATION_JOURNAL)

    private fun readJournal(): MutableList<MigrationJournalEntry> = runCatching {
        val json = JSONObject(journalFile.readText())
        val entries = json.optJSONArray("entries") ?: JSONArray()
        MutableList(entries.length()) { index -> MigrationJournalEntry.parse(entries.getJSONObject(index)) }
    }.getOrDefault(mutableListOf())

    private fun writeJournal(entries: List<MigrationJournalEntry>) {
        if (entries.isEmpty()) {
            journalFile.delete()
            return
        }
        writePrivatePlan(journalFile, JSONObject().put("entries", JSONArray(entries.map { it.json() })))
    }

    suspend fun run() {
        if (!ready()) return
        val current = targets()
        val currentByMetadata = current.associateBy { it.metadataId }
        val journal = readJournal()
        val entries: MutableList<MigrationJournalEntry> = if (journal.isEmpty()) current.map {
            MigrationJournalEntry(it.metadataId, it.documentId, it.revision, it.attachmentId)
        }.toMutableList() else journal.filter { currentByMetadata.containsKey(it.metadataId) }.toMutableList()
        if (entries.isEmpty()) {
            journalFile.delete()
            return
        }
        writeJournal(entries)
        for (original in entries.toList()) {
            try {
                if (!ready()) return
                val currentTarget = targets().firstOrNull { it.metadataId == original.metadataId }
                if (currentTarget == null || currentTarget.documentId != original.documentId ||
                    currentTarget.revision != original.revision || currentTarget.attachmentId != original.attachmentId) {
                    entries.remove(original); writeJournal(entries); continue
                }
                var entry = original
                val thumbnailId = if (entry.thumbnailId.isNotBlank()) entry.thumbnailId else api.generateId().also {
                    entry = entry.copy(thumbnailId = it)
                    entries[entries.indexOf(original)] = entry
                    writeJournal(entries)
                }
                val bytes = reads.withPermit { api.read(entry.attachmentId) }
                val thumbnail = encode(bytes) ?: throw IllegalArgumentException("이미지 썸네일을 생성할 수 없습니다.")
                api.create(thumbnailId,
                    JSONObject().put("name", "${entry.documentId}-${entry.revision}.thumb.jpg")
                        .put("mimeType", "image/jpeg")
                        .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "thumbnail")
                            .put("documentId", truncateDriveProperty(entry.documentId))),
                    thumbnail, "image/jpeg")
                api.patchAppProperties(entry.metadataId, JSONObject().put("listThumbnailId", thumbnailId))
                commit(currentTarget, thumbnailId)
                entries.remove(original); writeJournal(entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep this entry and continue. One broken legacy attachment must not block the rest.
            }
        }
        writeJournal(entries)
    }
}
