package com.youngsu.fieldshare

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PrivateDriveRecoveryTest {
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "recovery-test-${UUID.randomUUID()}").apply { mkdirs() }

    @Test fun initialCompletionSurvivesEveryFailureAndRestart() = runBlocking {
        for (failure in listOf("list1", "replay0", "replay1", "replay2")) {
            val dir = directory()
            try {
                val remote = Remote().apply { add("a"); add("b"); fail = failure }
                val store = PrivateDriveStore(dir)
                assertTrue(failure, runCatching { PrivateDriveSync(remote, store).sync {} }.isFailure)
                assertFalse(store.initialSyncComplete); assertEquals(0L, store.lastSync)
                val restarted = PrivateDriveStore(dir)
                assertFalse(restarted.initialSyncComplete); assertEquals(0L, restarted.lastSync)
                assertTrue(restarted.revisions.isNotEmpty())
                remote.fail = null
                PrivateDriveSync(remote, restarted).sync {}
                val successful = PrivateDriveStore(dir)
                assertTrue(successful.initialSyncComplete); assertTrue(successful.initialListReady); assertTrue(successful.lastSync > 0)
                assertEquals("stable", successful.checkpoint)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun expiredTokenResyncAndRecentFailureDoNotDestroyCacheOrLastSuccess() = runBlocking {
        val dir = directory()
        try {
            val remote = Remote().apply { add("a"); add("b") }
            var store = PrivateDriveStore(dir)
            PrivateDriveSync(remote, store).sync {}
            val last = store.lastSync
            remote.fail = "stable"
            assertTrue(runCatching { PrivateDriveSync(remote, store).sync {} }.isFailure)
            store = PrivateDriveStore(dir)
            assertTrue(store.initialSyncComplete); assertEquals(last, store.lastSync)
            assertTrue(searchPrivateDocuments(privateHeads(store.revisions.values), "내용").isNotEmpty())
            store.checkpoint = "expired"; store.save(); remote.fail = "replay1"
            assertTrue(runCatching { PrivateDriveSync(remote, store).sync {} }.isFailure)
            store = PrivateDriveStore(dir)
            assertFalse(store.initialSyncComplete); assertEquals(last, store.lastSync)
            remote.fail = null
            PrivateDriveSync(remote, store).sync {}
            assertTrue(PrivateDriveStore(dir).initialSyncComplete)
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacyCheckpointIsNotCompletionEvidence() {
        val dir = directory()
        try {
            val store = PrivateDriveStore(dir).apply { checkpoint = "old"; lastSync = 123; initialSyncComplete = true; save() }
            assertTrue(PrivateDriveStore(dir).initialListReady)
            val file = File(dir, "metadata.json")
            val json = JSONObject(file.readText()).apply { remove("initialSyncComplete") }
            file.writeText(json.toString())
            val restarted = PrivateDriveStore(store.directory)
            assertFalse(restarted.initialSyncComplete); assertNull(restarted.checkpoint); assertEquals(123L, restarted.lastSync)
        } finally { dir.deleteRecursively() }
    }

    @Test fun deletedLatestNeverPromotesParentOnRestartFullSyncOrNewDevice() = runBlocking {
        for (trash in listOf(true, false)) {
            val dir = directory(); val freshDir = directory()
            try {
                val remote = Remote().apply { add("a"); add("b", listOf("a")) }
                var store = PrivateDriveStore(dir)
                PrivateDriveSync(remote, store).sync {}
                assertEquals(listOf("b"), privateHeads(store.revisions.values).map { it.revision })
                remote.removeMetadata("b", trash)
                PrivateDriveSync(remote, store).sync {}
                store = PrivateDriveStore(dir)
                assertMissingHead(store, trash)
                store.checkpoint = null; store.save()
                PrivateDriveSync(remote, store).sync {}; assertMissingHead(store, trash)
                val fresh = PrivateDriveStore(freshDir)
                PrivateDriveSync(remote, fresh).sync {}; assertMissingHead(fresh, trash)
                assertEquals(0, remote.trashCalls)
            } finally { dir.deleteRecursively(); freshDir.deleteRecursively() }
        }
    }

    @Test fun survivingSiblingAndConcurrentAppDeletionStayVisibleAsConflict() = runBlocking {
        val dir = directory()
        try {
            val remote = Remote().apply { add("a"); add("b", listOf("a")); add("c", listOf("a")); removeMetadata("b", false) }
            val store = PrivateDriveStore(dir)
            PrivateDriveSync(remote, store).sync {}
            var heads = privateHeads(store.revisions.values)
            assertEquals(setOf("b", "c"), heads.map { it.revision }.toSet())
            assertEquals(PrivateRemoteState.AVAILABLE, heads.single { it.revision == "c" }.remoteState)
            remote.add("deleted", listOf("c"), deleted = true)
            remote.add("edited", listOf("c"))
            store.checkpoint = null
            PrivateDriveSync(remote, store).sync {}
            heads = privateHeads(store.revisions.values)
            assertTrue(heads.any { it.revision == "edited" && !it.deleted })
            assertTrue(heads.any { it.deleted })
            assertEquals(0, remote.trashCalls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknowableLegacyHistoryIsRecoveryNotNormalLatest() = runBlocking {
        val dir = directory()
        try {
            val remote = Remote().apply { add("old", legacy = true) }
            val store = PrivateDriveStore(dir)
            PrivateDriveSync(remote, store).sync {}
            assertEquals(PrivateRemoteState.LEGACY_UNVERIFIED, privateHeads(store.revisions.values).single().remoteState)
            assertEquals(0, remote.trashCalls)
        } finally { dir.deleteRecursively() }
    }

    private fun assertMissingHead(store: PrivateDriveStore, trash: Boolean) {
        val heads = privateHeads(store.revisions.values)
        assertEquals(listOf("b"), heads.map { it.revision })
        assertEquals(if (trash) PrivateRemoteState.TRASHED else PrivateRemoteState.MISSING, heads.single().remoteState)
    }

    /** Real remote file inventory, separate metadata/ledger bytes, pagination and removal events. */
    private class Remote : PrivateDriveApi {
        val files = linkedMapOf<String, Pair<JSONObject, ByteArray>>()
        val events = mutableListOf<JSONObject>()
        var fail: String? = null
        var trashCalls = 0
        fun add(revision: String, parents: List<String> = emptyList(), deleted: Boolean = false, legacy: Boolean = false) {
            val doc = PrivateDocument("document", revision, parents, revision, "내용", "냉장고", 1, 2,
                deleted = deleted, fileId = revision, schemaVersion = if (legacy) 1 else 2, lineageId = if (legacy) "" else "ledger-$revision")
            fun put(id: String, kind: String, body: JSONObject) {
                val info = JSONObject().put("id", id).put("version", "1").put("trashed", false)
                    .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", kind).put("documentId", "document"))
                files[id] = info to body.toString().toByteArray()
            }
            put(revision, "metadata", doc.json())
            if (!legacy) put(doc.lineageId, "lineage", PrivateLineage.of(doc).json())
        }
        fun removeMetadata(id: String, trash: Boolean) {
            if (trash) { files.getValue(id).first.put("trashed", true); events += JSONObject().put("fileId", id).put("file", files.getValue(id).first) }
            else { files.remove(id); events += JSONObject().put("fileId", id).put("removed", true) }
        }
        override suspend fun list(query: String, page: String?): DrivePage {
            val index = page?.toInt() ?: 0
            if (fail == "list$index") throw DriveFailure(503)
            val all = files.values.map { it.first }
            // Ensure that explicit trashed=false would reproduce the missing-trash defect.
            val visible = if (query.contains("trashed=false")) all.filterNot { it.optBoolean("trashed") } else all
            return DrivePage(visible.drop(index).take(1), if (index + 1 < visible.size) "${index + 1}" else null)
        }
        override suspend fun startToken() = "replay0"
        override suspend fun changes(page: String): DrivePage {
            if (page == "expired") throw DriveFailure(410)
            if (fail == page) throw DriveFailure(503)
            return when (page) {
                "replay0" -> DrivePage(emptyList(), "replay1")
                "replay1" -> DrivePage(emptyList(), "replay2")
                else -> DrivePage(events.toList(), null, "stable")
            }
        }
        override suspend fun read(id: String) = files[id]?.second ?: throw DriveFailure(404)
        override suspend fun generateId() = UUID.randomUUID().toString()
        override suspend fun create(id: String, metadata: JSONObject, bytes: ByteArray?, mime: String) { error("Sync must not write remote files") }
        override suspend fun copy(sourceId: String, id: String, metadata: JSONObject) { error("Sync must not copy originals") }
        override suspend fun trash(id: String) { trashCalls++ }
    }
}
