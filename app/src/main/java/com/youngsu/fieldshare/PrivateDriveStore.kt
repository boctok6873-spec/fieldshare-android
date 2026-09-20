package com.youngsu.fieldshare

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal data class PrivateSyncMetricsSnapshot(
    val listPages: Int = 0,
    val listedFiles: Int = 0,
    val readRequests: Int = 0,
    val successfulReads: Int = 0,
    val changes: Int = 0,
    val revisions: Int = 0,
    val heads: Int = 0,
    val visibleDocuments: Int = 0,
    val summaryListingMs: Long = 0,
    val metadataHydrationMs: Long = 0,
    val lineageVerificationMs: Long = 0,
    val changesReplayMs: Long = 0,
    val totalFullSyncMs: Long = 0,
    val maxConcurrentReads: Int = 0
)

/** Non-sensitive, testable timings for the initial sync pipeline. */
internal class PrivateSyncMetrics {
    private val listPages = AtomicInteger(0)
    private val listedFiles = AtomicInteger(0)
    private val readRequests = AtomicInteger(0)
    private val successfulReads = AtomicInteger(0)
    private val changes = AtomicInteger(0)
    @Volatile private var revisions = 0
    @Volatile private var heads = 0
    @Volatile private var visibleDocuments = 0
    @Volatile var summaryListingMs = 0L
    @Volatile var metadataHydrationMs = 0L
    @Volatile var lineageVerificationMs = 0L
    @Volatile var changesReplayMs = 0L
    @Volatile var totalFullSyncMs = 0L
    private val activeReads = AtomicInteger(0)
    private val maxReads = AtomicInteger(0)
    private val metadataWorkNanos = AtomicLong(0)
    private val lineageWorkNanos = AtomicLong(0)

    fun recordList(fileCount: Int) { listPages.incrementAndGet(); listedFiles.addAndGet(fileCount) }
    fun recordChanges(fileCount: Int) { changes.addAndGet(fileCount) }
    fun recordInventory(revisionCount: Int, headCount: Int, visibleCount: Int) {
        revisions = revisionCount; heads = headCount; visibleDocuments = visibleCount
    }
    /** Sum of target read/parse work; totalFullSyncMs remains wall-clock duration. */
    fun recordMetadataWork(nanos: Long) { metadataHydrationMs = metadataWorkNanos.addAndGet(nanos) / 1_000_000 }
    fun recordLineageWork(nanos: Long) { lineageVerificationMs = lineageWorkNanos.addAndGet(nanos) / 1_000_000 }

    suspend fun <T> read(action: suspend () -> T): T {
        val active = activeReads.incrementAndGet()
        maxReads.updateAndGet { maxOf(it, active) }
        readRequests.incrementAndGet()
        return try { action().also { successfulReads.incrementAndGet() } } finally { activeReads.decrementAndGet() }
    }

    fun snapshot() = PrivateSyncMetricsSnapshot(listPages.get(), listedFiles.get(), readRequests.get(),
        successfulReads.get(), changes.get(), revisions, heads, visibleDocuments, summaryListingMs,
        metadataHydrationMs, lineageVerificationMs, changesReplayMs, totalFullSyncMs, maxReads.get())
}

/** Stored under noBackupFilesDir; atomic snapshots include checkpoint and metadata together. */
internal interface PrivateMetadataCache {
    val directory: File
    var checkpoint: String?
    var lastSync: Long
    var initialSyncComplete: Boolean
    var initialListReady: Boolean
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
    override var initialListReady: Boolean = false
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
            initialListReady = persistedInitialListReady(json, initialSyncComplete)
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
        }.onFailure { checkpoint = null; initialSyncComplete = false; initialListReady = false; revisions.clear(); versions.clear(); lineages.clear() }
    }

    override fun save() {
        val json = JSONObject().put("checkpoint", checkpoint ?: "").put("lastSync", lastSync)
            .put("initialSyncComplete", initialSyncComplete)
            .put("initialListReady", initialListReady)
            .put("lineages", JSONArray(lineages.values.map { it.json() }))
            .put("cleanup", JSONArray(cleanup)).put("documents", JSONArray(revisions.map { (id, doc) ->
                JSONObject().put("fileId", id).put("version", versions[id] ?: "").put("remoteState", doc.remoteState.name).put("document", doc.json())
            }))
        val output = snapshot.startWrite()
        try { output.write(json.toString().toByteArray()); snapshot.finishWrite(output) }
        catch (e: Exception) { snapshot.failWrite(output); throw e }
    }
}

internal fun persistedInitialListReady(json: JSONObject, initialSyncComplete: Boolean): Boolean =
    if (json.has("initialListReady")) json.optBoolean("initialListReady", false) else initialSyncComplete

private sealed class VerificationTarget {
    data class Metadata(val file: JSONObject) : VerificationTarget()
    data class Lineage(val file: JSONObject) : VerificationTarget()
}

private data class VerificationResult(val target: VerificationTarget,
    val metadata: PrivateDriveSync.HydratedMetadata? = null,
    val lineage: PrivateDriveSync.HydratedLineage? = null, val error: Throwable? = null)

internal class PrivateDriveSync(
    private val api: PrivateDriveApi,
    private val store: PrivateMetadataCache,
    private val metrics: PrivateSyncMetrics = PrivateSyncMetrics()
) {
    internal fun metricsSnapshot() = metrics.snapshot()
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

    internal data class HydratedMetadata(val fileId: String, val version: String, val document: PrivateDocument)
    internal data class HydratedLineage(val fileId: String, val lineage: PrivateLineage)
    private class InitialVerificationFailure(count: Int) : Exception("Drive 자료 검증에 실패한 항목 ${count}건이 있습니다. 재시도해 주세요.")

    private suspend fun <T> captureRead(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun readMetadata(file: JSONObject): HydratedMetadata? {
        val id = file.getString("id")
        val properties = file.optJSONObject("appProperties") ?: return null
        if (properties.optString("kind") != "metadata") return null
        val version = file.optString("version")
        val current = store.revisions[id]
        if (!file.optBoolean("trashed") && current?.detailsLoaded == true) return null
        val document = metrics.read {
            val document = PrivateDocument.parse(JSONObject(String(api.read(id))), id)
            require(document.id == properties.getString("documentId")) { "개인 자료 메타데이터가 일치하지 않습니다." }
            document
        }
        return HydratedMetadata(id, version, document.copy(
            remoteState = if (file.optBoolean("trashed")) PrivateRemoteState.TRASHED else PrivateRemoteState.LEGACY_UNVERIFIED,
            thumbnailId = properties.optString("listThumbnailId"), detailsLoaded = true
        ))
    }

    private suspend fun readLineage(file: JSONObject): HydratedLineage? {
        val id = file.getString("id")
        val properties = file.optJSONObject("appProperties") ?: return null
        if (properties.optString("kind") != "lineage") return null
        val lineage = metrics.read {
            val value = PrivateLineage.parse(JSONObject(String(api.read(id))))
            require(value.ledgerId == id && value.documentId == properties.getString("documentId"))
            value
        }
        return HydratedLineage(lineage.fileId, lineage)
    }

    private suspend fun hydrateInBatches(
        metadataFiles: List<JSONObject>,
        lineageFiles: List<JSONObject>,
        progress: () -> Unit
    ) {
        val failures = mutableListOf<Throwable>()
        var applied = 0
        val heads = privateHeads(store.revisions.values).filter { !it.deleted }.map { it.id }.toSet()
        fun documentId(target: VerificationTarget) = when (target) {
            is VerificationTarget.Metadata -> target.file.optJSONObject("appProperties")?.optString("documentId")
            is VerificationTarget.Lineage -> target.file.optJSONObject("appProperties")?.optString("documentId")
        }
        fun revision(target: VerificationTarget) = (target as? VerificationTarget.Metadata)?.file
            ?.optJSONObject("appProperties")?.optString("listRevision")
        fun targetRevision(target: VerificationTarget) = when (target) {
            is VerificationTarget.Metadata -> target.file.optJSONObject("appProperties")?.optString("listRevision").orEmpty()
            is VerificationTarget.Lineage -> target.file.optJSONObject("appProperties")?.optString("listRevision").orEmpty()
        }
        val headRevisionByDocument = privateHeads(store.revisions.values)
            .filter { !it.deleted }
            .associate { it.id to it.revision }
        val allTargets = metadataFiles.map { VerificationTarget.Metadata(it) } +
            lineageFiles.map { VerificationTarget.Lineage(it) }
        fun priority(target: VerificationTarget): Int {
            val id = documentId(target)
            val headRevision = id?.let(headRevisionByDocument::get)
            return when {
                headRevision == null -> 3
                target is VerificationTarget.Metadata && revision(target) == headRevision -> 0
                target is VerificationTarget.Lineage && targetRevision(target) == headRevision -> 1
                target is VerificationTarget.Lineage && targetRevision(target).isBlank() -> 2
                else -> 2
            }
        }
        val targets = allTargets.sortedWith(compareBy<VerificationTarget> { priority(it) }
            .thenByDescending { documentId(it) in heads })
        if (targets.isEmpty()) {
            metrics.metadataHydrationMs = 0
            metrics.lineageVerificationMs = 0
            store.save(); progress()
            return
        }
        coroutineScope {
            val work = Channel<VerificationTarget>(capacity = 4)
            val results = Channel<VerificationResult>(capacity = 4)
            val priorityTargets = targets.filter { priority(it) < 2 }
            val deferredTargets = targets.filter { priority(it) >= 2 }
            val priorityFinished = Channel<Unit>(capacity = priorityTargets.size)
            val workers = List(minOf(4, targets.size)) {
                launch(Dispatchers.IO) {
                    for (target in work) {
                        val targetStarted = System.nanoTime()
                        val result = try {
                            when (target) {
                                is VerificationTarget.Metadata -> VerificationResult(target, metadata = captureRead { readMetadata(target.file) }.getOrThrow())
                                is VerificationTarget.Lineage -> VerificationResult(target, lineage = captureRead { readLineage(target.file) }.getOrThrow())
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            VerificationResult(target, error = failure)
                        } finally {
                            val elapsed = System.nanoTime() - targetStarted
                            if (target is VerificationTarget.Metadata) metrics.recordMetadataWork(elapsed)
                            else metrics.recordLineageWork(elapsed)
                        }
                        if (priority(target) < 2) priorityFinished.send(Unit)
                        results.send(result)
                    }
                }
            }
            val feeder = launch {
                priorityTargets.forEach { work.send(it) }
                repeat(priorityTargets.size) { priorityFinished.receive() }
                deferredTargets.forEach { work.send(it) }
                work.close()
            }
            repeat(targets.size) {
                val result = results.receive()
                result.error?.let { failures += it }
                result.metadata?.let { value ->
                    store.revisions[value.fileId] = value.document
                    store.versions[value.fileId] = value.version
                }
                result.lineage?.let { value ->
                    store.lineages[value.fileId] = value.lineage
                    store.revisions.putIfAbsent(value.fileId, value.lineage.placeholder())
                }
                applied++
                if (applied % 10 == 0) { store.save(); progress() }
            }
            feeder.join()
            workers.joinAll()
            results.close()
        }
        store.save(); progress()
        if (failures.isNotEmpty()) throw InitialVerificationFailure(failures.size)
    }
    private suspend fun accept(file: JSONObject, summaryOnly: Boolean = false) {
        val id = file.getString("id")
        val properties = file.optJSONObject("appProperties") ?: return
        if (properties.optString("app") != DriveMarker) return
        if (properties.optString("kind") == "lineage") {
            if (summaryOnly) return
            // Include trashed ledgers as evidence while Drive still makes them readable.
            val lineage = PrivateLineage.parse(JSONObject(String(api.read(id))))
            require(lineage.ledgerId == id && lineage.documentId == properties.getString("documentId"))
            store.lineages[lineage.fileId] = lineage
            store.revisions.putIfAbsent(lineage.fileId, lineage.placeholder())
            return
        }
        if (properties.optString("kind") != "metadata") return
        if (file.optBoolean("trashed")) {
            if (summaryOnly) {
                privateSummaryFromProperties(id, properties)?.let {
                    store.revisions[id] = it.copy(deleted = true, remoteState = PrivateRemoteState.TRASHED)
                }
                return
            }
            if (!store.revisions.containsKey(id)) {
                try { store.revisions[id] = PrivateDocument.parse(JSONObject(String(api.read(id))), id) }
                catch (e: DriveFailure) { if (e.status != 404) throw e }
            }
            markMissing(id, PrivateRemoteState.TRASHED); return
        }
        val version = file.optString("version")
        privateSummaryFromProperties(id, properties).let { summary ->
            if (summary != null) {
                val current = store.revisions[id]
                if (current != null && store.versions[id] == version && current.detailsLoaded) {
                    store.revisions[id] = current.copy(
                        title = summary.title, category = summary.category, modified = summary.modified,
                        pinned = summary.pinned, thumbnailId = summary.thumbnailId, parents = summary.parents
                    )
                } else {
                    store.revisions[id] = summary
                }
                store.versions[id] = version
                return
            }
        }
        if (summaryOnly) return
        if (store.versions[id] == version && store.revisions[id]?.detailsLoaded == true && store.revisions[id]?.remoteState in
            listOf(PrivateRemoteState.AVAILABLE, PrivateRemoteState.LEGACY_UNVERIFIED)) return
        val document = PrivateDocument.parse(JSONObject(String(api.read(id))), id)
        require(document.id == properties.getString("documentId")) { "개인 자료 메타데이터가 일치하지 않습니다." }
        store.revisions[id] = document.copy(remoteState = PrivateRemoteState.LEGACY_UNVERIFIED, detailsLoaded = true); store.versions[id] = version
    }

    /** Enriches a summary after it has already been published to the list. */
    private suspend fun hydrate(file: JSONObject) {
        val id = file.getString("id")
        val properties = file.optJSONObject("appProperties") ?: return
        if (properties.optString("kind") != "metadata" || file.optBoolean("trashed")) return
        val current = store.revisions[id] ?: return
        if (current.detailsLoaded) return
        val document = PrivateDocument.parse(JSONObject(String(api.read(id))), id)
        require(document.id == properties.getString("documentId")) { "개인 자료 메타데이터가 일치하지 않습니다." }
        store.revisions[id] = document.copy(remoteState = PrivateRemoteState.LEGACY_UNVERIFIED,
            thumbnailId = properties.optString("listThumbnailId"), detailsLoaded = true)
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
        val fullStarted = System.nanoTime()
        val start = api.startToken()
        try {
        store.initialSyncComplete = false
        store.initialListReady = false
        store.checkpoint = null
        store.save(); progress()
        // Capture before listing so changes made during pagination are replayed, never skipped.
        val seen = mutableSetOf<String>()
        val metadataFiles = mutableListOf<JSONObject>()
        val lineageFiles = mutableListOf<JSONObject>()
        val listingStarted = System.nanoTime()
        var page: String? = null
            try {
                do {
                    val result = api.list("$appQuery and (appProperties has { key='kind' and value='metadata' } or appProperties has { key='kind' and value='lineage' })", page)
                    metrics.recordList(result.files.size)
                    for (file in result.files) {
                        seen += file.getString("id")
                        when (file.optJSONObject("appProperties")?.optString("kind")) {
                            "metadata" -> { metadataFiles += file; accept(file, summaryOnly = true) }
                            "lineage" -> lineageFiles += file
                        }
                    }
                    // One durable write and one UI publish per Drive page.
                    store.save(); progress()
                    page = result.next
                } while (page != null)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // Preserve the old partial-cache behavior for legacy metadata if pagination
                // itself fails, without delaying the normal summary listing path.
                runCatching { hydrateInBatches(metadataFiles, emptyList(), progress) }
                throw failure
            }
        store.revisions.keys.filter { it !in seen }.forEach { markMissing(it, PrivateRemoteState.MISSING) }
        store.lineages.entries.removeAll { it.value.ledgerId !in seen }
        metrics.summaryListingMs = (System.nanoTime() - listingStarted) / 1_000_000
        store.initialListReady = true
        store.save(); progress()

        // Summary rows are now visible. JSON and lineage reads happen afterwards, four at a time.
        hydrateInBatches(metadataFiles, lineageFiles, progress)
        reconcileEvidence()
        store.save(); progress()
        store.checkpoint = start; store.save()
        incremental(progress)
        } finally {
            metrics.totalFullSyncMs = (System.nanoTime() - fullStarted) / 1_000_000
        }
    }
    private suspend fun incremental(progress: () -> Unit) {
        val replayStarted = System.nanoTime()
        var page = checkNotNull(store.checkpoint)
        do {
            val result = api.changes(page)
            metrics.recordChanges(result.files.size)
            for (change in result.files) {
                val id = change.getString("fileId")
                if (change.optBoolean("removed")) {
                    markMissing(id, PrivateRemoteState.MISSING)
                    store.lineages.entries.removeAll { it.value.ledgerId == id }
                }
                else change.optJSONObject("file")?.let {
                    accept(it); store.save(); progress(); hydrate(it)
                }
                // Originals are fetched on demand; a Drive edit invalidates only that cached original.
                File(store.directory, "originals/${accountCacheKey(id)}").delete()
            }
            reconcileEvidence()
            check(result.next != null || !result.checkpoint.isNullOrBlank()) { "변경 내역의 마지막 체크포인트가 없습니다." }
            store.checkpoint = result.next ?: result.checkpoint ?: page
            store.save(); progress()
            page = result.next ?: break
        } while (true)
        val heads = privateHeads(store.revisions.values)
        metrics.recordInventory(store.revisions.size, heads.size, searchPrivateDocuments(heads, "").size)
        metrics.changesReplayMs = (System.nanoTime() - replayStarted) / 1_000_000
    }

    suspend fun retryCleanup() {
        for (id in store.cleanup.toList()) { api.trash(id); store.cleanup.remove(id); store.save() }
    }
}
