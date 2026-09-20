package com.youngsu.fieldshare

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val PrivateCategory = "내 자료"
internal const val DriveFileScope = "https://www.googleapis.com/auth/drive.file"
internal const val DriveMarker = "fieldshare-private-v1"

/** Identity is taken from the exact authorization token, never a display name or Firebase UID. */
internal fun authorizedAccountId(sdkId: String?, tokenSubject: String, emailVerified: Boolean, scopes: List<String>): String {
    require(DriveFileScope in scopes && emailVerified && tokenSubject.isNotBlank())
    require(sdkId == null || sdkId == tokenSubject)
    return tokenSubject
}

internal fun accountCacheKey(stableId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(stableId.toByteArray()).joinToString("") { "%02x".format(it) }

internal data class PrivateAttachment(val id: String, val mime: String) {
    fun json() = JSONObject().put("id", id).put("mime", mime)
}

internal enum class PrivateRemoteState { AVAILABLE, TRASHED, MISSING, LEGACY_UNVERIFIED }
/** Upload state is local-only: it describes the durable pending.json transaction, not Drive data. */
internal enum class PrivateSyncStatus { WAITING, SYNCING, FAILED, ACTION_REQUIRED, CANCELLED, COMPLETE }
internal fun workerMayStart(status: PrivateSyncStatus) = status !in setOf(PrivateSyncStatus.ACTION_REQUIRED, PrivateSyncStatus.CANCELLED)

/** Content-free durable evidence, separate from the user-editable metadata file. */
internal data class PrivateLineage(val ledgerId: String, val fileId: String, val documentId: String,
    val revision: String, val parents: List<String>, val created: Long, val modified: Long, val deleted: Boolean) {
    fun json() = JSONObject().put("schemaVersion", 2).put("ledgerId", ledgerId).put("fileId", fileId)
        .put("documentId", documentId).put("revision", revision).put("parents", JSONArray(parents))
        .put("created", created).put("modified", modified).put("deleted", deleted)
    fun placeholder() = PrivateDocument(documentId, revision, parents, "외부 삭제 / 복구 확인 필요", "", "", created, modified,
        deleted = deleted, fileId = fileId, schemaVersion = 2, lineageId = ledgerId, remoteState = PrivateRemoteState.MISSING)
    companion object {
        fun of(document: PrivateDocument) = PrivateLineage(document.lineageId, document.fileId, document.id,
            document.revision, document.parents, document.created, document.modified, document.deleted)
        fun parse(json: JSONObject) = PrivateLineage(json.getString("ledgerId"), json.getString("fileId"),
            json.getString("documentId"), json.getString("revision"), json.getJSONArray("parents").strings(),
            json.getLong("created"), json.getLong("modified"), json.getBoolean("deleted"))
    }
}

/** Immutable revisions preserve concurrent edits without relying on a read-then-write lock. */
internal data class PrivateDocument(
    val id: String,
    val revision: String,
    val parents: List<String>,
    val title: String,
    val content: String,
    val category: String,
    val created: Long,
    val modified: Long,
    val ocr: String = "",
    val attachments: List<PrivateAttachment> = emptyList(),
    val deleted: Boolean = false,
    val fileId: String = "",
    val schemaVersion: Int = 1,
    val lineageId: String = "",
    val remoteState: PrivateRemoteState = PrivateRemoteState.AVAILABLE,
    val syncStatus: PrivateSyncStatus = PrivateSyncStatus.COMPLETE,
    val pinned: Boolean = false,
    /** Drive's lightweight list thumbnail; never points at an attachment download. */
    val thumbnailUrl: String = "",
    /** False for an appProperties-only list item whose JSON is fetched later. */
    val detailsLoaded: Boolean = true
) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", schemaVersion).put("lineageId", lineageId).put("id", id)
        .put("revision", revision).put("parents", JSONArray(parents)).put("title", title)
        .put("content", content).put("category", category).put("created", created)
        .put("modified", modified).put("ocr", ocr).put("deleted", deleted).put("pinned", pinned)
        .put("format", if (attachments.isEmpty()) "TEXT" else "ATTACHMENT")
        .put("attachments", JSONArray(attachments.map { it.json() }))
        .put("thumbnailUrl", thumbnailUrl)

    companion object {
        fun parse(json: JSONObject, fileId: String): PrivateDocument {
            require(json.getInt("schemaVersion") in 1..2) { "지원하지 않는 개인 자료 버전입니다. 앱 업데이트가 필요합니다." }
            return PrivateDocument(json.getString("id"), json.getString("revision"),
                json.getJSONArray("parents").strings(), json.getString("title"), json.getString("content"),
                json.getString("category"), json.getLong("created"), json.getLong("modified"),
                json.optString("ocr"), (json.getJSONArray("attachments")).let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { PrivateAttachment(it.getString("id"), it.getString("mime")) } }
                }, json.optBoolean("deleted"), fileId, json.getInt("schemaVersion"), json.optString("lineageId"),
                pinned = json.optBoolean("pinned"), thumbnailUrl = json.optString("thumbnailUrl"), detailsLoaded = true)
        }
    }
}

/** Builds a safe, content-free list item from Drive appProperties without downloading JSON. */
internal fun privateSummaryFromProperties(fileId: String, properties: JSONObject, thumbnailUrl: String = ""): PrivateDocument? {
    if (properties.optString("app") != DriveMarker || properties.optString("kind") != "metadata") return null
    val id = properties.optString("listDocumentId", properties.optString("documentId"))
    val revision = properties.optString("listRevision")
    val title = properties.optString("listTitle")
    if (id.isBlank() || revision.isBlank() || title.isBlank()) return null
    return PrivateDocument(
        id = id,
        revision = revision,
        parents = emptyList(),
        title = title,
        content = "",
        category = properties.optString("listCategory", "기타"),
        created = properties.optLong("listCreated", properties.optLong("listModified")),
        modified = properties.optLong("listModified"),
        fileId = fileId,
        remoteState = PrivateRemoteState.LEGACY_UNVERIFIED,
        pinned = properties.optString("listPinned").toBoolean(),
        thumbnailUrl = thumbnailUrl.ifBlank { properties.optString("listThumbnail") },
        detailsLoaded = false
    )
}

internal data class PendingPrivateSave(val document: PrivateDocument, val status: PrivateSyncStatus, val error: String?)

internal fun privateQueueDirectory(directory: java.io.File) = directory.resolve("queue").apply { mkdirs() }
internal fun privateQueueFiles(directory: java.io.File) = privateQueueDirectory(directory).listFiles()
    ?.filter { it.extension == "json" }?.sortedBy { it.name }.orEmpty()
internal fun queuedPrivateSaves(directory: java.io.File): List<PendingPrivateSave> = privateQueueFiles(directory).mapNotNull { file ->
    runCatching {
        val plan = JSONObject(file.readText())
        val status = PrivateSyncStatus.valueOf(plan.optString("syncStatus", PrivateSyncStatus.WAITING.name))
        PendingPrivateSave(PrivateDocument.parse(plan.getJSONObject("document"), plan.optString("metadataId")).copy(syncStatus = status),
            status, plan.optString("syncError").takeIf { it.isNotBlank() })
    }.getOrNull()
}

/** Older journals are safely treated as uploads that were interrupted while in progress. */
internal fun pendingPrivateSave(directory: java.io.File): PendingPrivateSave? = runCatching {
    val plan = JSONObject(directory.resolve("pending.json").readText())
    val legacyIsDelete = plan.getJSONObject("document").optBoolean("deleted")
    if (plan.optString("operation", if (legacyIsDelete) "DELETE" else "SAVE") != "SAVE") return@runCatching null
    val status = runCatching { PrivateSyncStatus.valueOf(plan.optString("syncStatus", PrivateSyncStatus.SYNCING.name)) }
        .getOrDefault(PrivateSyncStatus.SYNCING)
    PendingPrivateSave(
        PrivateDocument.parse(plan.getJSONObject("document"), plan.getString("metadataId")).copy(syncStatus = status),
        status,
        plan.optString("syncError").takeIf { it.isNotBlank() }
    )
}.getOrNull()

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun privateHeads(revisions: Collection<PrivateDocument>): List<PrivateDocument> {
    val parents = revisions.flatMap { it.parents }.toSet()
    return revisions.filter { it.revision !in parents }.sortedByDescending { it.modified }
}

/** A pin is shown only for the current, fully saved document head. */
internal fun showsPinnedPrivateDocument(document: PrivateDocument): Boolean =
    document.pinned && document.syncStatus == PrivateSyncStatus.COMPLETE &&
        !document.deleted && (document.remoteState == PrivateRemoteState.AVAILABLE || !document.detailsLoaded)

internal fun searchPrivateDocuments(documents: List<PrivateDocument>, query: String): List<PrivateDocument> {
    fun normalize(value: String) = value.replace(Regex("\\s+"), " ").trim()
    val term = normalize(query)
    return documents.filter { (!it.deleted || it.remoteState != PrivateRemoteState.AVAILABLE) && (term.isEmpty() ||
        listOf(it.title, it.content, it.category, it.ocr).any { value -> normalize(value).contains(term, true) }) }
        .sortedWith(compareByDescending(::showsPinnedPrivateDocument).thenByDescending { it.modified })
}

internal class DriveFailure(val status: Int, val reason: String = "") : Exception(when {
    status == 401 -> "Drive 권한을 다시 확인해 주세요. 내 정보에서 다시 연결할 수 있습니다."
    reason == "storageQuotaExceeded" -> "Google Drive 저장 공간이 부족합니다. 공간을 확보한 뒤 재시도해 주세요."
    status == 429 || reason.contains("RateLimit", true) -> "Drive 요청 한도에 도달했습니다. 잠시 후 재시도해 주세요."
    status == 403 -> "Drive 접근 권한이 없습니다. 권한 또는 Google Cloud 설정을 확인해 주세요."
    status == 404 -> "Drive에서 원본이 삭제되었거나 접근할 수 없습니다. 지금 동기화를 실행해 주세요."
    status == 409 -> "다른 기기의 변경이 있습니다. 동기화한 뒤 내용을 확인해 주세요."
    else -> "Drive 요청을 완료하지 못했습니다. 인터넷 연결을 확인하고 재시도해 주세요."
})
