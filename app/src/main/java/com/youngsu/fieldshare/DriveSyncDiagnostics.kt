package com.youngsu.fieldshare

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emits only operational Drive sync information.  It deliberately excludes account, token,
 * query, file ID, filename, and document content so production logs can be shared safely.
 */
internal enum class DriveSyncOrigin { AUTOMATIC, MANUAL }

internal class DiagnosticPrivateDriveApi(
    private val delegate: PrivateDriveApi,
    private val origin: DriveSyncOrigin
) : PrivateDriveApi {
    private val listRequests = AtomicInteger(0)
    private val listedFiles = AtomicInteger(0)
    private val readRequests = AtomicInteger(0)
    private val successfulReads = AtomicInteger(0)
    private val changeRequests = AtomicInteger(0)

    private suspend fun <T> request(name: String, action: suspend () -> T): T = try {
        action().also { Log.i(TAG, "origin=$origin request=$name result=success http=2xx") }
    } catch (failure: Exception) {
        val status = (failure as? DriveFailure)?.status?.toString() ?: "unavailable"
        Log.w(TAG, "origin=$origin request=$name result=failure http=$status type=${failure::class.java.simpleName}")
        throw failure
    }

    override suspend fun list(query: String, page: String?): DrivePage = request("files.list") {
        delegate.list(query, page).also { result ->
            val pageNumber = listRequests.incrementAndGet()
            val total = listedFiles.addAndGet(result.files.size)
            Log.i(TAG, "origin=$origin request=files.list page=$pageNumber files=${result.files.size} listedFiles=$total")
        }
    }
    override suspend fun startToken(): String = request("changes.startPageToken") { delegate.startToken() }
    override suspend fun changes(page: String): DrivePage = request("changes.list") {
        delegate.changes(page).also { result ->
            val pageNumber = changeRequests.incrementAndGet()
            Log.i(TAG, "origin=$origin request=changes.list page=$pageNumber changes=${result.files.size}")
        }
    }
    override suspend fun read(id: String): ByteArray = request("files.get.media") {
        readRequests.incrementAndGet()
        delegate.read(id).also { successfulReads.incrementAndGet() }
    }
    override suspend fun generateId(): String = request("files.generateIds") { delegate.generateId() }
    override suspend fun create(id: String, metadata: org.json.JSONObject, bytes: ByteArray?, mime: String) =
        request("files.create") { delegate.create(id, metadata, bytes, mime) }
    override suspend fun trash(id: String) = request("files.trash") { delegate.trash(id) }
    override suspend fun copy(sourceId: String, id: String, metadata: org.json.JSONObject) =
        request("files.copy") { delegate.copy(sourceId, id, metadata) }

    fun finish(success: Boolean, metrics: PrivateSyncMetricsSnapshot, revisions: Int, heads: Int, visibleDocuments: Int) {
        Log.i(TAG, "origin=$origin syncResult=${if (success) "success" else "failure"} " +
            "listPages=${metrics.listPages} listedFiles=${metrics.listedFiles} listRequests=${listRequests.get()} " +
            "readRequests=${metrics.readRequests} successfulReads=${metrics.successfulReads} " +
            "apiSuccessfulReads=${successfulReads.get()} changeRequests=${changeRequests.get()} " +
            "changes=${metrics.changes} revisions=$revisions heads=$heads visibleDocuments=$visibleDocuments " +
            "summaryWallMs=${metrics.summaryListingMs} metadataWorkMs=${metrics.metadataHydrationMs} " +
            "lineageWorkMs=${metrics.lineageVerificationMs} changesWallMs=${metrics.changesReplayMs} " +
            "totalWallMs=${metrics.totalFullSyncMs} maxConcurrentReads=${metrics.maxConcurrentReads}")
    }

    private companion object { const val TAG = "FieldShareDrive" }
}
