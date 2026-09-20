package com.youngsu.fieldshare

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stored under noBackupFilesDir; atomic snapshots include checkpoint and metadata together. */
internal interface PrivateMetadataCache {
    val directory: File
    var checkpoint: String?
    var lastSync: Long
    var initialSyncComplete: Boolean
    val revisions: MutableMap<String, PrivateDocument>
    val versions: MutableMap<String, String>
    val cleanup: MutableSet<String>
    val lineages: MutableMap<String, PrivateLineage>
    fun save()
}

internal class PrivateDriveStore(override val directory: File) : PrivateMetadataCache {
    init { directory.mkdirs() }
    private val snapshot = AtomicFile(File(directory, "metadata.json"))
    override var checkpoint: String? = null
    override var lastSync: Long = 0
    override var initialSyncComplete: Boolean = false
    override val revisions = linkedMapOf<String, PrivateDocument>()
    override val versions = linkedMapOf<String, String>()
    override val cleanup = linkedSetOf<String>()
    override val lineages = linkedMapOf<String, PrivateLineage>()

    init {
        runCatching {
            val json = JSONObject(String(snapshot.readFully()))
            checkpoint = json.optString("checkpoint").takeIf { it.isNotBlank() }
            lastSync = json.optLong("lastSync")
            initialSyncComplete = json.optBoolean("initialSyncComplete", false)
            // Legacy checkpoints are not evidence that the changes replay ever finished.
            if (!json.has("initialSyncComplete")) checkpoint = null
            val a = json.getJSONArray("documents")
            repeat(a.length()) {
                val entry = a.getJSONObject(it)
                val id = entry.getString("fileId")
                revisions[id] = PrivateDocument.parse(entry.getJSONObject("document"), id).copy(
                    remoteState = runCatching { PrivateRemoteState.valueOf(entry.getString("remoteState")) }
                        .getOrDefault(PrivateRemoteState.LEGACY_UNVERIFIED))
                versions[id] = entry.optString("version")
            }
            cleanup.addAll(json.optJSONArray("cleanup")?.strings().orEmpty())
            json.optJSONArray("lineages")?.let { entries -> repeat(entries.length()) {
                val lineage = PrivateLineage.parse(entries.getJSONObject(it)); lineages[lineage.fileId] = lineage
            } }
        }.onFailure { checkpoint = null; initialSyncComplete = false; revisions.clear(); versions.clear(); lineages.clear() }
    }

    override fun save() {
        val json = JSONObject().put("checkpoint", checkpoint ?: "").put("lastSync", lastSync)
            .put("initialSyncComplete", initialSyncComplete)
            .put("lineages", JSONArray(lineages.values.map { it.json() }))
            .put("cleanup", JSONArray(cleanup)).put("documents", JSONArray(revisions.map { (id, doc) ->
                JSONObject().put("fileId", id).put("version", versions[id] ?: "").put("remoteState", doc.remoteState.name).put("document", doc.json())
            }))
        val output = snapshot.startWrite()
        try { output.write(json.toString().toByteArray()); snapshot.finishWrite(output) }
        catch (e: Exception) { snapshot.failWrite(output); throw e }
    }
}

internal class PrivateDriveSync(private val api: PrivateDriveApi, private val store: PrivateMetadataCache) {
    private val appQuery = "appProperties has { key='app' and value='$DriveMarker' }"
    suspend fun folder(): String {
        val folders = mutableListOf<String>()
        var page: String? = null
        do {
            val result = api.list("trashed=false and mimeType='application/vnd.google-apps.folder' and $appQuery", page)
            folders += result.files.map { it.getString("id") }; page = result.next
        } while (page != null)
        if (folders.isNotEmpty()) return folders.sorted().first()
        // Journal the generated folder ID before the request, for lost-response retries.
        val journal = File(store.directory, "folder-id")
        val id = if (journal.exists()) journal.readText() else api.generateId().also { journal.writeText(it) }
        api.create(id, JSONObject().put("name", "FieldShare 내 자료").put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "folder")))
        journal.delete()
        return id
    }

    suspend fun sync(progress: () -> Unit) {
        try {
            if (store.checkpoint == null) full(progress) else incremental(progress)
        } catch (e: DriveFailure) {
            if (e.status != 410) throw e
            store.checkpoint = null
            store.initialSyncComplete = false
            store.save(); progress()
            full(progress)
        }
        val previousComplete = store.initialSyncComplete
        val previousTime = store.lastSync
        store.initialSyncComplete = true
        store.lastSync = System.currentTimeMillis()
        try { store.save() } catch (e: Exception) {
            store.initialSyncComplete = previousComplete; store.lastSync = previousTime; throw e
        }
        progress()
    }
    private suspend fun accept(file: JSONObject) {
        val id = file.getString("id")
        val properties = file.optJSONObject("appProperties") ?: return
        if (properties.optString("app") != DriveMarker) return
        if (properties.optString("kind") == "lineage") {
            // Include trashed ledgers as evidence while Drive still makes them readable.
            val lineage = PrivateLineage.parse(JSONObject(String(api.read(id))))
            require(lineage.ledgerId == id && lineage.documentId == properties.getString("documentId"))
            store.lineages[lineage.fileId] = lineage
            store.revisions.putIfAbsent(lineage.fileId, lineage.placeholder())
            return
        }
        if (properties.optString("kind") != "metadata") return
        if (file.optBoolean("trashed")) {
            if (!store.revisions.containsKey(id)) {
                try { store.revisions[id] = PrivateDocument.parse(JSONObject(String(api.read(id))), id) }
                catch (e: DriveFailure) { if (e.status != 404) throw e }
            }
            markMissing(id, PrivateRemoteState.TRASHED); return
        }
        val version = file.optString("version")
        if (store.versions[id] == version && store.revisions[id]?.remoteState in
            listOf(PrivateRemoteState.AVAILABLE, PrivateRemoteState.LEGACY_UNVERIFIED)) return
        val document = PrivateDocument.parse(JSONObject(String(api.read(id))), id)
        require(document.id == properties.getString("documentId")) { "개인 자료 메타데이터가 일치하지 않습니다." }
        store.revisions[id] = document.copy(remoteState = PrivateRemoteState.LEGACY_UNVERIFIED); store.versions[id] = version
    }
    private fun markMissing(id: String, state: PrivateRemoteState) {
        val document = store.revisions[id] ?: store.lineages[id]?.placeholder() ?: return
        store.revisions[id] = document.copy(remoteState = state)
        store.versions.remove(id)
    }
    private fun reconcileEvidence() {
        for ((id, doc) in store.revisions.toMap()) {
            if (doc.remoteState == PrivateRemoteState.TRASHED || doc.remoteState == PrivateRemoteState.MISSING) continue
            val evidence = store.lineages[id]
            val verified = evidence != null && evidence == PrivateLineage.of(doc) && doc.schemaVersion == 2
            store.revisions[id] = doc.copy(remoteState = if (verified) PrivateRemoteState.AVAILABLE else PrivateRemoteState.LEGACY_UNVERIFIED)
        }
    }
    private suspend fun full(progress: () -> Unit) {
        store.initialSyncComplete = false
        store.checkpoint = null
        store.save(); progress()
        // Capture before listing so changes made during pagination are replayed, never skipped.
        val start = api.startToken()
        val seen = mutableSetOf<String>()
        var page: String? = null
        do {
            val result = api.list("$appQuery and (appProperties has { key='kind' and value='metadata' } or appProperties has { key='kind' and value='lineage' })", page)
            for (file in result.files) { accept(file); seen += file.getString("id") }
            store.save(); progress(); page = result.next
        } while (page != null)
        store.revisions.keys.filter { it !in seen }.forEach { markMissing(it, PrivateRemoteState.MISSING) }
        store.lineages.entries.removeAll { it.value.ledgerId !in seen }
        reconcileEvidence()
        store.checkpoint = start; store.save()
        incremental(progress)
    }
    private suspend fun incremental(progress: () -> Unit) {
        var page = checkNotNull(store.checkpoint)
        do {
            val result = api.changes(page)
            for (change in result.files) {
                val id = change.getString("fileId")
                if (change.optBoolean("removed")) {
                    markMissing(id, PrivateRemoteState.MISSING)
                    store.lineages.entries.removeAll { it.value.ledgerId == id }
                }
                else change.optJSONObject("file")?.let { accept(it) }
                // Originals are fetched on demand; a Drive edit invalidates only that cached original.
                File(store.directory, "originals/${accountCacheKey(id)}").delete()
            }
            reconcileEvidence()
            check(result.next != null || !result.checkpoint.isNullOrBlank()) { "변경 내역의 마지막 체크포인트가 없습니다." }
            store.checkpoint = result.next ?: result.checkpoint ?: page
            store.save(); progress()
            page = result.next ?: break
        } while (true)
    }

    suspend fun retryCleanup() {
        for (id in store.cleanup.toList()) { api.trash(id); store.cleanup.remove(id); store.save() }
    }
}
