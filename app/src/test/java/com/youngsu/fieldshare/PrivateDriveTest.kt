package com.youngsu.fieldshare

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PrivateDriveTest {
    private fun doc(id: String = "one", revision: String = id, parents: List<String> = emptyList()) = PrivateDocument(
        id, revision, parents, "냉장고 RF85A", "필터  교체\nhttps://example.com/123", "냉장고", 1, 2, "모델 AB1234 점검")

    @Test fun privateSyncButtonTracksQueuedUploadAndPreventsDuplicateSync() {
        val waiting = DriveUiState(pendingSave = true, pendingSyncStatus = PrivateSyncStatus.WAITING)
        val syncing = DriveUiState(pendingSave = true, pendingSyncStatus = PrivateSyncStatus.SYNCING)
        assertEquals("드라이브 저장중", privateSyncButtonLabel(waiting)); assertFalse(privateSyncButtonEnabled(waiting))
        assertEquals("드라이브 저장중", privateSyncButtonLabel(syncing)); assertFalse(privateSyncButtonEnabled(syncing))
        assertEquals("지금 동기화", privateSyncButtonLabel(DriveUiState()))
        assertTrue(privateSyncButtonEnabled(DriveUiState()))
        assertFalse(privateSyncButtonEnabled(DriveUiState(busy = true)))
        assertFalse(privateSyncButtonEnabled(DriveUiState(syncing = true)))
    }

    @Test fun privateDeleteReturnsToListBeforeBackgroundJournalWork() {
        val events = mutableListOf<String>()
        startPrivateDelete(navigateToList = { events += "list" }, continueDeletion = { events += "journal" })
        assertEquals(listOf("list", "journal"), events)
    }

    @Test fun appPropertiesCreateLightweightListSummaryWithoutDetailPayload() {
        val summary = privateSummaryFromProperties(
            "drive-file",
            JSONObject().put("app", DriveMarker).put("kind", "metadata")
                .put("documentId", "doc-1").put("listDocumentId", "doc-1")
                .put("listRevision", "rev-3").put("listTitle", "냉장고 점검")
                .put("listCategory", "냉장고").put("listCreated", "10")
                .put("listModified", "30").put("listPinned", "true")
                .put("listDeleted", "false").put("listThumbnailId", "thumb-1").put("listParents", "old-revision")
        )
        assertNotNull(summary)
        assertEquals("냉장고 점검", summary!!.title)
        assertEquals("냉장고", summary.category)
        assertTrue(summary.pinned)
        assertEquals("thumb-1", summary.thumbnailId)
        assertEquals(listOf("old-revision"), summary.parents)
        assertFalse(summary.detailsLoaded)
        assertTrue(summary.content.isEmpty())
    }

    @Test fun drivePropertiesAreUtf8BoundedWithoutSplittingKoreanCharacters() {
        val title = "냉장고 필터 교체 안내 ".repeat(30)
        val shortened = truncateDriveProperty(title)
        assertTrue(shortened.toByteArray(Charsets.UTF_8).size <= 124)
        assertTrue(title.startsWith(shortened))
        assertFalse(shortened.endsWith("\uFFFD"))
        assertTrue(summarizeDriveParents(listOf("parent-1", "parent-2")) == "parent-1|parent-2")
    }

    @Test fun summaryAndHydratedRevisionKeepSameHeadPinAndListKey() {
        val summary = doc(id = "doc", revision = "new", parents = listOf("old"))
            .copy(fileId = "meta", pinned = true, detailsLoaded = false, remoteState = PrivateRemoteState.LEGACY_UNVERIFIED)
        val hydrated = summary.copy(content = "상세", detailsLoaded = true, remoteState = PrivateRemoteState.AVAILABLE)
        assertEquals(listOf("new"), privateHeads(listOf(summary, doc(id = "doc", revision = "old"))).map { it.revision })
        assertEquals(listOf("new"), privateHeads(listOf(hydrated, doc(id = "doc", revision = "old"))).map { it.revision })
        assertTrue(showsPinnedPrivateDocument(summary))
        assertTrue(showsPinnedPrivateDocument(hydrated))
        assertEquals(privateDocumentListKey(summary), privateDocumentListKey(hydrated))
    }

    @Test fun backgroundSyncGateAllowsOnlyOneActiveScan() {
        val gate = BackgroundSyncGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    @Test fun activeSyncStoreWinsOverQueueObserverReload() {
        val active = PrivateDriveStore(Files.createTempDirectory("active-store").toFile())
        val refreshed = PrivateDriveStore(Files.createTempDirectory("refreshed-store").toFile())
        assertSame(active, authoritativePrivateStore(active, refreshed))
        assertSame(refreshed, authoritativePrivateStore(null, refreshed))
    }

    @Test fun cleanupPreservesHeadAndRecentTombstoneButPlansOldEvidence() {
        val cache = MemoryCache().apply { initialSyncComplete = true }
        val now = System.currentTimeMillis()
        val head = doc(id = "doc", revision = "head", parents = listOf("old")).copy(
            fileId = "meta-head", lineageId = "ledger-head", schemaVersion = 2,
            attachments = listOf(PrivateAttachment("attachment-head", "image/jpeg")), thumbnailId = "thumbnail-head",
            modified = now)
        val old = doc(id = "doc", revision = "old").copy(
            fileId = "meta-old", lineageId = "ledger-old", schemaVersion = 2,
            attachments = listOf(PrivateAttachment("attachment-old", "image/jpeg")), thumbnailId = "thumbnail-old",
            modified = now - 1)
        val tombstone = doc(id = "deleted", revision = "tombstone").copy(
            fileId = "meta-tombstone", lineageId = "ledger-tombstone", schemaVersion = 2,
            deleted = true, modified = now - 1_000)
        cache.revisions[head.fileId] = head; cache.revisions[old.fileId] = old; cache.revisions[tombstone.fileId] = tombstone
        cache.lineages[head.fileId] = PrivateLineage.of(head)
        cache.lineages[old.fileId] = PrivateLineage.of(old)
        cache.lineages[tombstone.fileId] = PrivateLineage.of(tombstone)
        val plan = planPrivateDriveCleanup(cache, now)
        assertTrue(plan.candidates.containsAll(setOf("meta-old", "ledger-old", "attachment-old", "thumbnail-old")))
        assertFalse(plan.candidates.any { it in setOf("meta-head", "ledger-head", "attachment-head", "thumbnail-head") })
        assertFalse(plan.candidates.contains("meta-tombstone")); assertEquals(1, plan.preservedTombstones)
    }

    @Test fun cleanupFailureKeepsJournalAndRetryTrashesOnlyRemainingIds() = runBlocking {
        val cache = MemoryCache().apply { initialSyncComplete = true }; val old = doc(id = "doc", revision = "old").copy(fileId = "meta-old", lineageId = "ledger-old")
        val head = doc(id = "doc", revision = "head", parents = listOf("old")).copy(fileId = "meta-head", lineageId = "ledger-head")
        cache.revisions[old.fileId] = old; cache.revisions[head.fileId] = head
        cache.lineages[old.fileId] = PrivateLineage.of(old); cache.lineages[head.fileId] = PrivateLineage.of(head)
        val api = FakeApi().apply { failTrash = "ledger-old" }
        val first = cleanupObsoletePrivateDrive(api, cache)
        assertTrue(first.failed > 0); assertTrue(File(cache.directory, "obsolete-cleanup.json").exists())
        api.failTrash = null
        val second = cleanupObsoletePrivateDrive(api, cache)
        assertEquals(0, second.failed); assertFalse(File(cache.directory, "obsolete-cleanup.json").exists())
        assertTrue(api.trashed.containsAll(listOf("meta-old", "ledger-old")))
    }

    @Test fun cleanupKeepsPendingIdsAndOnlyRemovesUnreferencedLocalCaches() = runBlocking {
        val cache = MemoryCache().apply { initialSyncComplete = true }; val head = doc(id = "doc", revision = "head").copy(
            fileId = "meta-head", thumbnailId = "thumb-head", attachments = listOf(PrivateAttachment("att-head", "image/jpeg")))
        cache.revisions[head.fileId] = head
        val originals = File(cache.directory, "originals").apply { mkdirs() }
        val thumbnails = File(cache.directory, "thumbnails").apply { mkdirs() }
        val keepOriginal = File(originals, accountCacheKey("att-head")); keepOriginal.writeText("keep")
        val keepThumbnail = File(thumbnails, "${accountCacheKey("thumb-head")}.jpg"); keepThumbnail.writeText("keep")
        val staleOriginal = File(originals, "stale"); staleOriginal.writeText("remove")
        val staleThumbnail = File(thumbnails, "stale.jpg"); staleThumbnail.writeText("remove")
        privateQueueDirectory(cache.directory).resolve("pending.json").writeText(
            JSONObject().put("metadataId", "pending-meta").put("cleanup", JSONArray(listOf("pending-att"))).toString())
        cleanupObsoletePrivateDrive(FakeApi(), cache)
        assertTrue(keepOriginal.exists()); assertTrue(keepThumbnail.exists())
        assertFalse(staleOriginal.exists()); assertFalse(staleThumbnail.exists())
        val plan = planPrivateDriveCleanup(cache)
        assertTrue("pending IDs are retained by planning", plan.preservedIds.containsAll(listOf("pending-meta", "pending-att")))
    }

    @Test fun cleanupProtectsPendingLineageAndSkipsBeforeInitialSync() = runBlocking {
        val notReady = MemoryCache()
        val noOpApi = FakeApi()
        cleanupObsoletePrivateDrive(noOpApi, notReady)
        assertTrue(noOpApi.listQueries.isEmpty()); assertTrue(noOpApi.trashed.isEmpty())

        val cache = MemoryCache().apply { initialSyncComplete = true }
        val old = doc(id = "doc", revision = "old").copy(fileId = "meta-old", lineageId = "ledger-old")
        val head = doc(id = "doc", revision = "head", parents = listOf("old")).copy(fileId = "meta-head")
        cache.revisions[old.fileId] = old; cache.revisions[head.fileId] = head
        privateQueueDirectory(cache.directory).resolve("pending.json").writeText(JSONObject()
            .put("metadataId", "pending-meta").put("document", old.json()).toString())
        val plan = planPrivateDriveCleanup(cache)
        assertTrue(plan.preservedIds.contains("ledger-old"))
        val api = FakeApi()
        cleanupObsoletePrivateDrive(api, cache)
        assertTrue("pending metadata must remain in local revisions", cache.revisions.containsKey(old.fileId))
        assertFalse("pending lineage must never be trashed", api.trashed.contains("ledger-old"))
    }

    @Test fun cleanupTrashesOrphanAttachmentAndThumbnailInventory() = runBlocking {
        val cache = MemoryCache().apply { initialSyncComplete = true }
        val head = doc(id = "doc", revision = "head").copy(fileId = "meta-head", attachments = emptyList(), thumbnailId = "")
        cache.revisions[head.fileId] = head
        val api = FakeApi().apply {
            pages[null] = DrivePage(listOf(
                JSONObject().put("id", "orphan-att").put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "attachment")),
                JSONObject().put("id", "orphan-thumb").put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "thumbnail"))
            ), null)
        }
        cleanupObsoletePrivateDrive(api, cache)
        assertTrue(api.trashed.containsAll(listOf("orphan-att", "orphan-thumb")))
        assertTrue(api.listQueries.single().contains("trashed=false"))
    }

    @Test fun legacyImageThumbnailMigrationStartsAfterListReadyAndUpdatesOnlySummaryProperty() = runBlocking {
        val cache = MemoryCache().apply { initialListReady = true; initialSyncComplete = true }
        val legacy = doc().copy(fileId = "meta-legacy", revision = "rev-legacy",
            attachments = listOf(PrivateAttachment("image-legacy", "image/jpeg")))
        cache.revisions[legacy.fileId] = legacy
        val api = FakeApi().apply { bodies["image-legacy"] = byteArrayOf(1, 2, 3) }
        val events = mutableListOf<String>(); var firstReadyCheck = true
        val migration = PrivateDriveThumbnailMigration(api, cache.directory,
            ready = { if (firstReadyCheck) { events += "migration"; firstReadyCheck = false }; cache.initialListReady && cache.initialSyncComplete },
            targets = { legacyThumbnailMigrationTargets(cache) },
            commit = { target, thumbnailId ->
                events += "commit"
                cache.revisions[target.metadataId] = cache.revisions.getValue(target.metadataId).copy(thumbnailId = thumbnailId)
            },
            encode = { it })
        events += "summary"
        migration.run()
        assertEquals(listOf("summary", "migration", "commit"), events)
        assertEquals("generated", cache.revisions.getValue("meta-legacy").thumbnailId)
        assertEquals("generated", api.patchedProperties["meta-legacy"]?.optString("listThumbnailId"))
        assertEquals("thumbnail", api.createdMetadata["generated"]?.optJSONObject("appProperties")?.optString("kind"))
        assertEquals("rev-legacy", cache.revisions.getValue("meta-legacy").revision)
    }

    @Test fun thumbnailMigrationResumesSameGeneratedIdAfterCreateFailure() = runBlocking {
        val cache = MemoryCache().apply { initialListReady = true; initialSyncComplete = true }
        val legacy = doc().copy(fileId = "meta-retry", revision = "rev-retry",
            attachments = listOf(PrivateAttachment("image-retry", "image/jpeg")))
        cache.revisions[legacy.fileId] = legacy
        val api = FakeApi().apply { bodies["image-retry"] = byteArrayOf(7); failCreate = "generated" }
        fun migration() = PrivateDriveThumbnailMigration(api, cache.directory,
            targets = { legacyThumbnailMigrationTargets(cache) },
            commit = { target, thumbnailId -> cache.revisions[target.metadataId] = cache.revisions.getValue(target.metadataId).copy(thumbnailId = thumbnailId) },
            encode = { it })
        migration().run()
        assertTrue(File(cache.directory, "thumbnail-migration.json").exists())
        api.failCreate = null
        migration().run()
        assertFalse(File(cache.directory, "thumbnail-migration.json").exists())
        assertEquals(listOf("generated"), api.generatedIds)
        assertEquals("generated", cache.revisions.getValue("meta-retry").thumbnailId)
    }

    @Test fun thumbnailMigrationPatchesAfterRetryAndCommitsLocalIdOnlyAfterPatch() = runBlocking {
        val cache = MemoryCache().apply { initialListReady = true; initialSyncComplete = true }
        val legacy = doc().copy(fileId = "meta-patch", revision = "rev-patch",
            attachments = listOf(PrivateAttachment("image-patch", "image/jpeg")))
        cache.revisions[legacy.fileId] = legacy
        val api = FakeApi().apply {
            bodies["image-patch"] = byteArrayOf(9)
            failPatch = "meta-patch"
        }
        fun migration() = PrivateDriveThumbnailMigration(api, cache.directory,
            targets = { legacyThumbnailMigrationTargets(cache) },
            commit = { target, thumbnailId -> cache.revisions[target.metadataId] = cache.revisions.getValue(target.metadataId).copy(thumbnailId = thumbnailId) },
            encode = { it })
        migration().run()
        assertTrue(cache.revisions.getValue("meta-patch").thumbnailId.isBlank())
        api.failPatch = null
        migration().run()
        assertEquals("generated", cache.revisions.getValue("meta-patch").thumbnailId)
        assertEquals(listOf("generated"), api.generatedIds)
        assertFalse(File(cache.directory, "thumbnail-migration.json").exists())
    }

    @Test fun repositoryThumbnailSnapshotUsesActiveStoreDuringLongFullSync() = runBlocking {
        val accountKey = "account-key"
        val api = FakeApi().apply {
            bodies["full-sync-read"] = byteArrayOf()
            readDelayById["full-sync-read"] = 500
        }
        val root = Files.createTempDirectory("thumbnail-read").toFile()
        val active = PrivateDriveStore(File(root, accountKey))
        val refreshed = PrivateDriveStore(File(root, "other-account"))
        val cached = File(active.directory, "thumbnails/${accountCacheKey("thumb-fast")}.jpg").apply {
            parentFile!!.mkdirs(); writeText("cached")
        }
        val fullSync = launch { api.read("full-sync-read") }
        assertSame(active, selectPrivateThumbnailStore(active, refreshed, accountKey))
        assertEquals(cached, withTimeout(1_000) {
            readPrivateDocumentThumbnail(api, doc().copy(thumbnailId = "thumb-fast"), active, refreshed,
                accountKey, Semaphore(2)).getOrThrow()
        })
        fullSync.join()
    }

    @Test fun legacyThumbnailMigrationSkipsNonImageDeletedThumbnailedAndPendingDocuments() {
        val cache = MemoryCache().apply { initialListReady = true; initialSyncComplete = true }
        cache.revisions["text"] = doc(id = "text").copy(fileId = "meta-text")
        cache.revisions["deleted"] = doc(id = "deleted").copy(fileId = "meta-deleted", deleted = true,
            attachments = listOf(PrivateAttachment("image-deleted", "image/jpeg")))
        cache.revisions["thumb"] = doc(id = "thumb").copy(fileId = "meta-thumb", thumbnailId = "thumb-id",
            attachments = listOf(PrivateAttachment("image-thumb", "image/jpeg")))
        cache.revisions["pending"] = doc(id = "pending").copy(fileId = "meta-pending",
            attachments = listOf(PrivateAttachment("image-pending", "image/jpeg")))
        File(cache.directory, "pending.json").writeText(JSONObject().put("metadataId", "meta-pending").toString())
        assertTrue(legacyThumbnailMigrationTargets(cache).isEmpty())
    }

    @Test fun thumbnailSnapshotRejectsAccountDirectoryMismatchWithoutReadingDrive() = runBlocking {
        val api = FakeApi()
        val root = Files.createTempDirectory("thumbnail-account").toFile()
        val store = PrivateDriveStore(File(root, "account-a"))
        val result = readPrivateDocumentThumbnail(api, doc().copy(thumbnailId = "thumb"), store, null,
            "account-b", Semaphore(2))
        assertTrue(result.isFailure)
        assertTrue(api.reads.isEmpty())
    }

    @Test fun pinnedIconPresentationKeepsPinnedLogicWithRotatedRedStyle() {
        assertTrue(showsPinnedPrivateDocument(doc().copy(pinned = true)))
        assertEquals(-35f, PRIVATE_PIN_ROTATION_DEGREES)
        assertEquals(0xFFC62828, PRIVATE_PIN_COLOR_ARGB)
    }

    @Test fun fullSyncPublishesFirstSummaryBeforeLaterDrivePages() = runBlocking {
        val cache = MemoryCache()
        val first = doc(id = "first", revision = "r1").copy(fileId = "first")
        val second = doc(id = "second", revision = "r2").copy(fileId = "second")
        val api = FakeApi().apply {
            pages[null] = DrivePage(listOf(summaryFile(first)), "next")
            pages["next"] = DrivePage(listOf(summaryFile(second)), null)
            bodies["first"] = first.json().toString().toByteArray()
            bodies["second"] = second.json().toString().toByteArray()
        }
        val publishedSizes = mutableListOf<Int>()
        PrivateDriveSync(api, cache).sync { publishedSizes += cache.revisions.size }
        assertTrue("first page must be visible before the second page completes", publishedSizes.contains(1))
        assertEquals(2, cache.revisions.size)
    }

    @Test fun summaryListBecomesReadyBeforeInitialVerificationCompletes() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val first = doc(id = "first", revision = "r1").copy(fileId = "first")
        api.pages[null] = DrivePage(listOf(summaryFile(first)), null)
        api.bodies["first"] = first.json().toString().toByteArray()
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        PrivateDriveSync(api, cache).sync { states += cache.initialListReady to cache.initialSyncComplete }
        assertTrue(states.any { ready -> ready.first && !ready.second })
        assertTrue(cache.initialListReady); assertTrue(cache.initialSyncComplete)
    }

    @Test fun summaryStageHidesOldRevisionAndDeletedTombstone() = runBlocking {
        val cache = MemoryCache(); val current = doc(id = "active", revision = "new", parents = listOf("old"))
            .copy(fileId = "meta-new", pinned = true)
        val old = doc(id = "active", revision = "old").copy(fileId = "meta-old")
        val deleted = doc(id = "removed", revision = "gone", parents = emptyList()).copy(fileId = "meta-gone", deleted = true)
        val api = FakeApi().apply {
            pages[null] = DrivePage(listOf(summaryFile(old), summaryFile(current), summaryFile(deleted)), null)
            bodies[old.fileId] = old.json().toString().toByteArray()
            bodies[current.fileId] = current.json().toString().toByteArray()
            bodies[deleted.fileId] = deleted.json().toString().toByteArray()
        }
        var visibleDuringSummary = emptyList<PrivateDocument>()
        PrivateDriveSync(api, cache).sync {
            if (cache.initialListReady.not()) visibleDuringSummary = searchPrivateDocuments(privateHeads(cache.revisions.values), "")
        }
        assertEquals(listOf("new"), visibleDuringSummary.map { it.revision })
        assertEquals(listOf("new"), searchPrivateDocuments(privateHeads(cache.revisions.values), "").map { it.revision })
    }

    @Test fun initialSummaryHydrationUsesAtMostFourConcurrentReadsAndBatchesSaves() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi().apply { readDelayMs = 5 }
        val files = (0 until 12).map { index ->
            doc(id = "doc-$index", revision = "rev-$index").copy(fileId = "meta-$index")
        }
        api.pages[null] = DrivePage(files.map(::summaryFile), null)
        files.forEach { document -> api.bodies[document.fileId] = document.json().toString().toByteArray() }
        val metrics = PrivateSyncMetrics()
        PrivateDriveSync(api, cache, metrics).sync {}
        assertTrue(metrics.snapshot().maxConcurrentReads <= 4)
        assertTrue(cache.saveCount < files.size)
        assertTrue(cache.initialSyncComplete)
    }

    @Test fun workerPoolStartsNextTargetBeforeSlowReadFinishes() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val documents = (0 until 5).map { index ->
            doc(id = if (index == 0) "slow" else "fast-$index", revision = "rev-$index")
                .copy(fileId = "meta-$index", modified = if (index == 0) 100 else 1)
        }
        api.pages[null] = DrivePage(documents.map(::summaryFile), null)
        documents.forEach { api.bodies[it.fileId] = it.json().toString().toByteArray() }
        api.readDelayById["meta-0"] = 250
        val metrics = PrivateSyncMetrics()
        PrivateDriveSync(api, cache, metrics).sync {}
        val slowFinished = api.readEvents.indexOf("finish:meta-0")
        val fifthStarted = api.readEvents.indexOf("start:meta-4")
        assertTrue("a completed fast worker must take the fifth target before the slow read ends",
            fifthStarted >= 0 && slowFinished >= 0 && fifthStarted < slowFinished)
        assertTrue(metrics.snapshot().maxConcurrentReads <= 4)
    }

    @Test fun currentHeadMetadataAndLineageStartBeforeNonHeadTargets() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val head = doc(id = "active", revision = "head", parents = listOf("old"))
            .copy(fileId = "meta-head", schemaVersion = 2, lineageId = "ledger-head", modified = 100, pinned = true)
        val old = doc(id = "active", revision = "old").copy(fileId = "meta-old", schemaVersion = 2, lineageId = "ledger-old")
        val deleted = doc(id = "deleted", revision = "tombstone")
            .copy(fileId = "meta-deleted", schemaVersion = 2, lineageId = "ledger-deleted", deleted = true)
        val hidden = doc(id = "hidden", revision = "hidden", parents = emptyList()).copy(
            fileId = "meta-hidden", schemaVersion = 2, lineageId = "ledger-hidden", deleted = true)
        val files = listOf(
            summaryFile(old), lineageFile(PrivateLineage.of(old)), summaryFile(deleted),
            lineageFile(PrivateLineage.of(hidden)), summaryFile(head), lineageFile(PrivateLineage.of(head)),
            summaryFile(hidden), lineageFile(PrivateLineage.of(deleted))
        )
        val parsedSummaries = files.mapNotNull { file ->
            privateSummaryFromProperties(file.getString("id"), file.getJSONObject("appProperties"))
        }
        assertEquals("head", privateHeads(parsedSummaries).first { !it.deleted }.revision)
        assertEquals("head", privateHeads(listOf(old, head, deleted, hidden)).first { !it.deleted }.revision)
        assertEquals("active", PrivateLineage.of(head).documentId)
        api.pages[null] = DrivePage(files, null)
        listOf(head, old, deleted, hidden).forEach { document ->
            api.bodies[document.fileId] = document.json().toString().toByteArray()
            api.bodies[document.lineageId] = PrivateLineage.of(document).json().toString().toByteArray()
        }

        val metrics = PrivateSyncMetrics()
        PrivateDriveSync(api, cache, metrics).sync {}
        val startPositions = api.readEvents.withIndex().filter { it.value.startsWith("start:") }
            .associate { it.value.removePrefix("start:") to it.index }
        val headTargets = listOf("meta-head", "ledger-head")
        val nonHeadTargets = listOf("meta-old", "ledger-old", "meta-deleted", "ledger-deleted", "meta-hidden", "ledger-hidden")
        headTargets.forEach { headId ->
            nonHeadTargets.forEach { otherId ->
                assertTrue("$headId must start before $otherId events=${api.readEvents}",
                    startPositions.getValue(headId) < startPositions.getValue(otherId))
            }
        }
        assertTrue(metrics.snapshot().maxConcurrentReads <= 4)
    }

    @Test fun completedLegacySnapshotImpliesInitialListReady() {
        assertTrue(persistedInitialListReady(JSONObject().put("initialSyncComplete", true), true))
        assertFalse(persistedInitialListReady(JSONObject().put("initialSyncComplete", false), false))
        assertFalse(persistedInitialListReady(JSONObject().put("initialSyncComplete", true).put("initialListReady", false), true))
    }

    @Test fun privateDeleteSyncButtonDisablesUntilCleanupAndRestoresAfterward() {
        val deleting = DriveUiState(deleting = true, pendingSave = true, pendingSyncStatus = PrivateSyncStatus.WAITING)
        assertEquals("자료 삭제중", privateSyncButtonLabel(deleting)); assertFalse(privateSyncButtonEnabled(deleting))
        assertEquals("지금 동기화", privateSyncButtonLabel(DriveUiState()))
        assertTrue(privateSyncButtonEnabled(DriveUiState()))
    }

    @Test fun privateDeleteFailureRetainsErrorAndRetryEligibility() {
        val failed = DriveUiState(deleting = true, deleteSyncError = "네트워크 오류", pendingSyncStatus = PrivateSyncStatus.FAILED)
        assertEquals("자료 삭제중", privateSyncButtonLabel(failed)); assertFalse(privateSyncButtonEnabled(failed))
        assertEquals("네트워크 오류", failed.deleteSyncError)
        assertEquals(PrivateSyncStatus.FAILED, failed.pendingSyncStatus)
        assertTrue(deleteRetryVisible(failed))
        assertFalse(deleteRetryVisible(DriveUiState(deleting = true)))
    }

    @Test fun deleteRetryReusesExistingJournalAndCleanupTargets() {
        val plan = JSONObject().put("operation", "DELETE").put("syncStatus", PrivateSyncStatus.FAILED.name)
            .put("metadataId", "existing-meta").put("deleteCommitted", true).put("cleanup", JSONArray(listOf("a", "b")))
            .put("syncError", "네트워크 오류")
        assertTrue(resumeDeletePlan(plan))
        assertEquals(PrivateSyncStatus.WAITING.name, plan.getString("syncStatus"))
        assertEquals("existing-meta", plan.getString("metadataId")); assertTrue(plan.getBoolean("deleteCommitted"))
        assertEquals(listOf("a", "b"), plan.getJSONArray("cleanup").strings())
        assertFalse(plan.has("syncError"))
    }

    @Test fun authorizationRequiredDeleteJournalResumesCleanupWithoutUploadPlan() {
        val plan = JSONObject().put("operation", "DELETE").put("syncStatus", PrivateSyncStatus.ACTION_REQUIRED.name)
            .put("metadataId", "existing-meta").put("deleteCommitted", true).put("cleanup", JSONArray(listOf("attachment")))
        assertTrue(resumeDeletePlan(plan))
        assertEquals(PrivateSyncStatus.WAITING.name, plan.getString("syncStatus"))
        assertTrue(plan.getBoolean("deleteCommitted")); assertFalse(plan.has("attachments"))
    }

    @Test fun privateDeleteJournalSurvivesMetadataCommitUntilDriveCleanupCompletes() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        val tombstone = doc().copy(fileId = "meta", schemaVersion = 2, lineageId = "ledger", deleted = true)
        val plan = JSONObject().put("jobId", jobId).put("folder", "folder").put("metadataId", "meta")
            .put("document", tombstone.json()).put("attachments", JSONArray()).put("cleanup", JSONArray(listOf("old-meta")))
            .put("operation", "DELETE").put("syncStatus", PrivateSyncStatus.SYNCING.name)
        writePrivatePlan(file, plan)

        PrivateDriveUploads(api, cache).uploadPending(file)
        assertTrue(file.exists()); assertTrue(JSONObject(file.readText()).getBoolean("deleteCommitted"))
        assertTrue("old-meta" in cache.cleanup)

        completeDeleteQueue(file)
        assertFalse(file.exists())
    }

    @Test fun categoryOrderAndLocalSubstringSearch() {
        assertEquals(listOf("전체", "내 자료", "냉장고"), homeCategories.take(3))
        for (query in listOf("필터 교체", "  rf85  ", "b123", "123", "example.com", "장고")) {
            assertEquals(query, 1, searchPrivateDocuments(listOf(doc()), query).size)
        }
        assertTrue(searchPrivateDocuments(listOf(doc()), "없는 검색").isEmpty())
        assertTrue(searchPrivateDocuments(listOf(doc().copy(deleted = true)), "").isEmpty())
    }

    @Test fun schemaRoundTripContainsNoFirebaseOrAccountFields() {
        val document = doc().copy(attachments = listOf(PrivateAttachment("file", "application/pdf")))
        assertEquals(document, PrivateDocument.parse(document.json(), ""))
        val keys = document.json().keys().asSequence().toList()
        assertTrue(keys.none { it.contains("uid", true) || it.contains("email", true) || it.contains("token", true) || it.contains("account", true) })
    }

    @Test fun pinnedStatePersistsInMetadataAndOrdersOnlyCompletedValidHeadsFirst() {
        val pinned = doc(id = "pinned", revision = "p").copy(modified = 1, pinned = true)
        val recent = doc(id = "recent", revision = "r").copy(modified = 9)
        val queuedPinned = doc(id = "queued", revision = "q").copy(modified = 20, pinned = true, syncStatus = PrivateSyncStatus.WAITING)
        val deletedPinned = doc(id = "deleted", revision = "d").copy(modified = 30, pinned = true, deleted = true)
        assertTrue(PrivateDocument.parse(pinned.json(), "meta").pinned)
        assertEquals(listOf("pinned", "queued", "recent"), searchPrivateDocuments(listOf(recent, queuedPinned, pinned, deletedPinned), "").map { it.id })
    }

    @Test fun pinnedOrderingIsRetainedForSearchAndRecentMode() {
        val pinnedOld = doc(id = "pin", revision = "p").copy(title = "냉장고 고정", modified = 2, pinned = true)
        val normalNew = doc(id = "normal", revision = "n").copy(title = "냉장고 일반", modified = 9)
        val other = doc(id = "other", revision = "o").copy(title = "세탁기", content = "세탁기 점검", category = "세탁기", ocr = "세탁기", modified = 20, pinned = true)
        val all = searchPrivateDocuments(listOf(normalNew, other, pinnedOld), "")
        assertEquals(listOf("other", "pin", "normal"), all.map { it.id })
        assertEquals(listOf("pin", "normal"), searchPrivateDocuments(all, "냉장고").map { it.id })
        assertEquals(listOf("other", "pin"), all.take(2).map { it.id })
    }

    @Test fun pinnedListIndicatorUsesTheSameValidHeadConditionAsSorting() {
        val pinned = doc(id = "pinned", revision = "p").copy(title = "냉장고 고정", modified = 2, pinned = true)
        val normal = doc(id = "normal", revision = "n").copy(title = "냉장고 일반", modified = 9)
        val pending = doc(id = "pending", revision = "q").copy(title = "냉장고 대기", modified = 20, pinned = true,
            syncStatus = PrivateSyncStatus.WAITING)
        val deleted = doc(id = "deleted", revision = "d").copy(title = "냉장고 삭제", pinned = true, deleted = true)
        val unverified = doc(id = "unverified", revision = "c").copy(title = "냉장고 미확인", pinned = true,
            remoteState = PrivateRemoteState.LEGACY_UNVERIFIED)

        assertTrue(showsPinnedPrivateDocument(pinned))
        assertFalse(showsPinnedPrivateDocument(normal))
        assertFalse(showsPinnedPrivateDocument(pending))
        assertFalse(showsPinnedPrivateDocument(deleted))
        assertFalse(showsPinnedPrivateDocument(unverified))
        assertEquals(listOf("pinned", "pending", "normal"),
            searchPrivateDocuments(listOf(normal, pending, pinned, deleted, unverified), "냉장고").take(3).map { it.id })
        assertFalse(showsPinnedPrivateDocument(pinned.copy(pinned = false)))
    }

    @Test fun detailTracksNewestLogicalRevisionAfterPinToggleAndEdit() {
        val original = doc(revision = "original").copy(fileId = "old-file", lineageId = "lineage", modified = 1)
        val pinned = original.copy(revision = "pinned", parents = listOf("original"), fileId = "new-file", modified = 2, pinned = true,
            syncStatus = PrivateSyncStatus.WAITING)
        val edited = pinned.copy(revision = "edited", parents = listOf("pinned"), fileId = "edited-file", modified = 3, title = "수정된 제목")
        assertEquals(pinned, resolvePrivateDetailDocument(original, listOf(pinned)))
        assertTrue(resolvePrivateDetailDocument(original, listOf(pinned)).pinned)
        assertEquals(edited, resolvePrivateDetailDocument(original, listOf(edited)))
        assertEquals("수정된 제목", resolvePrivateDetailDocument(original, listOf(edited)).title)
    }

    @Test fun detailShowsMissingOnlyWhenNoCurrentHeadOrTheHeadIsDeletedOrUnavailable() {
        val original = doc().copy(fileId = "old", lineageId = "lineage")
        val available = original.copy(fileId = "new", revision = "new", modified = 2, pinned = true)
        assertEquals(PrivateRemoteState.AVAILABLE, resolvePrivateDetailDocument(original, listOf(available)).remoteState)
        assertEquals(PrivateRemoteState.MISSING, resolvePrivateDetailDocument(original, emptyList()).remoteState)
        assertEquals(PrivateRemoteState.MISSING, resolvePrivateDetailDocument(original, listOf(available.copy(deleted = true))).remoteState)
        assertEquals(PrivateRemoteState.MISSING, resolvePrivateDetailDocument(original,
            listOf(available.copy(remoteState = PrivateRemoteState.MISSING))).remoteState)
    }

    @Test fun stableAccountKeyDoesNotUseDisplayNameAndIsolatesAccounts() {
        assertEquals(accountCacheKey("google-sub-A"), accountCacheKey("google-sub-A"))
        assertNotEquals(accountCacheKey("google-sub-A"), accountCacheKey("google-sub-B"))
        assertFalse(accountCacheKey("google-sub-A").contains("google"))
    }

    @Test fun displayedIdentityMustMatchAuthorizedAccountAndGrantedDriveScope() {
        assertEquals("subject", authorizedAccountId("subject", "subject", true, listOf(DriveFileScope)))
        assertEquals("subject", authorizedAccountId(null, "subject", true, listOf(DriveFileScope)))
        assertTrue(runCatching { authorizedAccountId("other", "subject", true, listOf(DriveFileScope)) }.isFailure)
        assertTrue(runCatching { authorizedAccountId("subject", "subject", true, emptyList()) }.isFailure)
        assertTrue(runCatching { authorizedAccountId("subject", "subject", false, listOf(DriveFileScope)) }.isFailure)
    }

    @Test fun concurrentRevisionsPreserveBothEdits() {
        val root = doc(revision = "root")
        val a = doc(revision = "a", parents = listOf("root"))
        val b = doc(revision = "b", parents = listOf("root"))
        assertEquals(setOf("a", "b"), privateHeads(listOf(root, a, b)).map { it.revision }.toSet())
        val merged = doc(revision = "merged", parents = listOf("a", "b"))
        assertEquals(listOf(merged), privateHeads(listOf(root, a, b, merged)))
    }

    @Test fun deleteRacingWithEditRemainsAnExplicitConflict() {
        val deleted = doc(revision = "deleted", parents = listOf("root")).copy(deleted = true, content = "", ocr = "", attachments = emptyList())
        val edited = doc(revision = "edited", parents = listOf("root"))
        val heads = privateHeads(listOf(deleted, edited))
        assertEquals(2, heads.size)
        assertEquals(listOf(edited), searchPrivateDocuments(heads, ""))
        assertTrue(heads.any { it.deleted })
    }

    @Test fun revisionAttachmentsAreCopiedOnDriveWithoutOriginalDownloads() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val document = doc().copy(attachments = listOf(PrivateAttachment("copy", "image/jpeg")))
        File(cache.directory, "pending.json").writeText(JSONObject().put("folder", "folder").put("metadataId", "meta")
            .put("document", document.json()).put("attachments", JSONArray(listOf(JSONObject().put("id", "copy")
                .put("sourceId", "original").put("mime", "image/jpeg")))).toString())
        PrivateDriveUploads(api, cache).uploadPending()
        assertEquals(listOf("copy", "meta", "generated"), api.created)
        assertTrue(api.reads.isEmpty()); assertTrue(api.trashed.isEmpty())
    }

    @Test fun homeMigrationRunsOnceForEveryPreviousValueAndRetainsLaterChoice() {
        for (initial in listOf(null) + HomeDocumentDisplayMode.entries) {
            var value = initial
            var done = false
            var commits = 0
            fun migrate() = migrateHomeDisplayOnce(done) { value = HomeDocumentDisplayMode.ALL; done = true; commits++ }
            migrate(); assertEquals(HomeDocumentDisplayMode.ALL, value)
            value = HomeDocumentDisplayMode.HIDDEN
            migrate(); assertEquals(HomeDocumentDisplayMode.HIDDEN, value); assertEquals(1, commits)
        }
    }

    @Test fun firstSyncPaginatesMetadataAndReplaysConcurrentChangesWithoutDownloadingOriginals() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val first = doc("first"); val second = doc("second")
        api.bodies["f1"] = first.json().toString().toByteArray(); api.bodies["f2"] = second.json().toString().toByteArray()
        api.pages[null] = DrivePage(listOf(file("f1", first.id)), "page2")
        api.pages["page2"] = DrivePage(listOf(file("f2", second.id)), null)
        api.changePages["start"] = DrivePage(listOf(JSONObject().put("fileId", "f1").put("removed", true)), null, "next")
        var progress = 0
        PrivateDriveSync(api, cache).sync { progress++ }
        assertEquals(PrivateRemoteState.MISSING, cache.revisions["f1"]?.remoteState)
        assertTrue(cache.revisions.containsKey("f2"))
        assertEquals(setOf("f1", "f2"), api.reads.toSet())
        assertTrue(api.listQueries.first().startsWith("trashed=false and "))
        assertEquals("next", cache.checkpoint); assertTrue(progress >= 3)
    }

    @Test fun partialSyncDoesNotClaimCompletionAndCanRetry() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        api.bodies["f1"] = doc().json().toString().toByteArray()
        api.pages[null] = DrivePage(listOf(file("f1", "one")), "missing")
        assertTrue(runCatching { PrivateDriveSync(api, cache).sync {} }.isFailure)
        assertNull(cache.checkpoint); assertEquals(1, cache.revisions.size); assertEquals(0L, cache.lastSync)
        api.pages["missing"] = DrivePage(emptyList(), null)
        PrivateDriveSync(api, cache).sync {}
        assertNotNull(cache.checkpoint); assertTrue(cache.lastSync > 0)
        assertEquals(listOf("f1"), api.reads)
    }

    @Test fun expiredChangeTokenTriggersFullResync() = runBlocking {
        val cache = MemoryCache().apply { checkpoint = "expired"; revisions["old"] = doc() }
        val api = FakeApi().apply { pages[null] = DrivePage(emptyList(), null) }
        PrivateDriveSync(api, cache).sync {}
        assertEquals(PrivateRemoteState.MISSING, cache.revisions["old"]?.remoteState); assertEquals("next", cache.checkpoint)
    }

    @Test fun reconnectFindsMarkedFolderAcrossPagesWithoutLocalIdOrName() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        api.pages[null] = DrivePage(emptyList(), "p2")
        api.pages["p2"] = DrivePage(listOf(JSONObject().put("id", "existing-renamed-folder")), null)
        assertEquals("existing-renamed-folder", PrivateDriveSync(api, cache).folder())
        assertEquals(0, api.creates)
    }

    @Test fun deletionPartialFailureRetainsOnlyUnfinishedExactTargets() = runBlocking {
        val cache = MemoryCache().apply { cleanup.addAll(listOf("a", "b", "c")) }
        val api = FakeApi().apply { failTrash = "b" }
        assertTrue(runCatching { PrivateDriveSync(api, cache).retryCleanup() }.isFailure)
        assertEquals(setOf("b", "c"), cache.cleanup)
        api.failTrash = null; PrivateDriveSync(api, cache).retryCleanup()
        assertTrue(cache.cleanup.isEmpty()); assertEquals(listOf("a", "b", "b", "c"), api.trashed)
    }

    @Test fun quotaPermissionAndRateErrorsAreSafeAndActionable() {
        assertTrue(DriveFailure(403, "storageQuotaExceeded").message!!.contains("공간"))
        assertTrue(DriveFailure(401).message!!.contains("권한"))
        assertTrue(DriveFailure(429).message!!.contains("잠시 후"))
    }

    @Test fun failedAttachmentNeverPublishesMetadataAndRetryReusesIds() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi().apply { failCreate = "b" }
        File(cache.directory, "upload-0").writeText("image one")
        File(cache.directory, "upload-1").writeText("image two")
        val document = doc().copy(attachments = listOf(PrivateAttachment("a", "image/jpeg"), PrivateAttachment("b", "image/jpeg")))
        val plan = JSONObject().put("folder", "folder").put("metadataId", "meta").put("document", document.json())
            .put("attachments", JSONArray(listOf(
                JSONObject().put("id", "a").put("mime", "image/jpeg").put("local", "upload-0"),
                JSONObject().put("id", "b").put("mime", "image/jpeg").put("local", "upload-1"))))
        val journal = File(cache.directory, "pending.json").apply { writeText(plan.toString()) }
        assertTrue(runCatching { PrivateDriveUploads(api, cache).uploadPending() }.isFailure)
        assertTrue(cache.revisions.isEmpty()); assertTrue(journal.exists()); assertFalse("meta" in api.created)
        api.failCreate = null
        PrivateDriveUploads(api, cache).uploadPending()
        assertEquals(listOf("a", "b", "a", "b", "meta", "generated"), api.created)
        assertEquals("one", cache.revisions["meta"]?.id); assertFalse(journal.exists())
        assertFalse(File(cache.directory, "upload-0").exists())
    }

    @Test fun durablePendingSaveIsVisibleWithKoreanRetryStateAfterRestart() {
        val cache = MemoryCache()
        val pending = doc().copy(fileId = "local-meta")
        File(cache.directory, "pending.json").writeText(JSONObject()
            .put("operation", "SAVE").put("metadataId", "local-meta").put("document", pending.json())
            .put("syncStatus", "FAILED").put("syncError", "인터넷 연결을 확인해 주세요.").toString())

        val restored = checkNotNull(pendingPrivateSave(cache.directory))
        assertEquals(PrivateSyncStatus.FAILED, restored.status)
        assertEquals("인터넷 연결을 확인해 주세요.", restored.error)
        assertEquals(PrivateSyncStatus.FAILED, restored.document.syncStatus)
        assertEquals(listOf("local-meta"), privateHeads(listOf(restored.document)).map { it.fileId })
    }

    @Test fun deleteJournalIsNotMistakenForARecoverableSave() {
        val cache = MemoryCache()
        File(cache.directory, "pending.json").writeText(JSONObject().put("metadataId", "delete-meta")
            .put("document", doc().copy(deleted = true).json()).toString())
        assertNull(pendingPrivateSave(cache.directory))
    }

    @Test fun queueKeepsMultipleOfflineSavesAndTheirLocalAttachmentsSeparate() {
        val cache = MemoryCache()
        val queue = privateQueueDirectory(cache.directory)
        fun queued(job: String, title: String) = JSONObject().put("jobId", job).put("metadataId", "")
            .put("document", doc(title).copy(fileId = "local-meta-$job").json())
            .put("attachments", JSONArray()).put("syncStatus", PrivateSyncStatus.WAITING.name)
        queue.resolve("a.json").writeText(queued("a", "첫 저장").toString())
        queue.resolve("b.json").writeText(queued("b", "두번째 저장").toString())

        val restored = queuedPrivateSaves(cache.directory)
        assertEquals(listOf("첫 저장", "두번째 저장"), restored.map { it.document.id })
        assertTrue(restored.all { it.status == PrivateSyncStatus.WAITING })
        assertTrue(File(cache.directory, "pending.json").exists().not())
    }

    @Test fun completedLegacyJournalNeverDeletesAccountOrOtherQueueJobs() {
        val cache = MemoryCache()
        val queue = privateQueueDirectory(cache.directory)
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val own = queue.resolve(jobId).apply { mkdirs() }.resolve("attachment").apply { writeText("own") }
        val other = queue.resolve("abcdefab-cdef-cdef-cdef-abcdefabcdef").apply { mkdirs() }.resolve("attachment").apply { writeText("other") }
        val accountMetadata = cache.directory.resolve("metadata.json").apply { writeText("keep") }
        val legacy = cache.directory.resolve("pending.json").apply { writeText("keep") }

        cleanupCompletedQueueAttachments(legacy, JSONObject())
        assertTrue(own.exists()); assertTrue(other.exists()); assertTrue(accountMetadata.exists())
        cleanupCompletedQueueAttachments(queue.resolve("$jobId.json"), JSONObject().put("jobId", jobId))
        assertFalse(own.exists()); assertTrue(other.exists()); assertTrue(accountMetadata.exists())
    }

    @Test fun actionRequiredAndCancelledJobsCannotBeAutomaticallyStarted() {
        assertFalse(workerMayStart(PrivateSyncStatus.ACTION_REQUIRED))
        assertFalse(workerMayStart(PrivateSyncStatus.CANCELLED))
        assertTrue(workerMayStart(PrivateSyncStatus.WAITING))
        assertTrue(workerMayStart(PrivateSyncStatus.FAILED))
    }

    @Test fun accountQueueLockSerializesCancellationStateWrites() {
        val cache = MemoryCache()
        val job = privateQueueDirectory(cache.directory).resolve("job.json")
        writePrivatePlan(job, JSONObject().put("syncStatus", PrivateSyncStatus.WAITING.name))
        withAccountQueueLock(cache.directory) {
            val plan = JSONObject(job.readText()).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
            writePrivatePlan(job, plan)
        }
        assertEquals(PrivateSyncStatus.CANCELLED.name, JSONObject(job.readText()).getString("syncStatus"))
    }

    @Test fun cancelledQueueJournalCannotBeOverwrittenByUploaderSnapshot() {
        val cache = MemoryCache()
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        val cancelled = JSONObject().put("jobId", jobId).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
        writePrivatePlan(file, cancelled)
        val staleUploaderPlan = JSONObject().put("jobId", jobId).put("syncStatus", PrivateSyncStatus.SYNCING.name)
        assertTrue(runCatching { writeActiveQueuePlan(file, staleUploaderPlan) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(PrivateSyncStatus.CANCELLED.name, JSONObject(file.readText()).getString("syncStatus"))
    }

    @Test fun cancellationDuringUploadStopsBeforeReceiptAndKeepsRecoveryJournal() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        val document = doc().copy(fileId = "meta", schemaVersion = 2, lineageId = "ledger")
        val plan = JSONObject().put("jobId", jobId).put("folder", "folder").put("metadataId", "meta")
            .put("document", document.json()).put("attachments", JSONArray()).put("syncStatus", PrivateSyncStatus.SYNCING.name)
        writePrivatePlan(file, plan)
        api.onCreate = { id -> if (id == "meta") {
            val latest = JSONObject(file.readText()).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
            writePrivatePlan(file, latest)
        } }
        assertTrue(runCatching { PrivateDriveUploads(api, cache).uploadPending(file) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(file.exists()); assertTrue("meta" in api.created); assertFalse("ledger" in api.created)
    }

    @Test fun cancelledMetadataIsFinalizedOnlyAfterConfirmedRemoteRead() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi()
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        privateQueueDirectory(cache.directory).resolve(jobId).mkdirs()
        val document = doc().copy(fileId = "meta", schemaVersion = 2, lineageId = "ledger")
        val plan = JSONObject().put("jobId", jobId).put("folder", "folder").put("metadataId", "meta")
            .put("document", document.json()).put("attachments", JSONArray()).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
        writePrivatePlan(file, plan); api.bodies["meta"] = byteArrayOf(1)
        reconcileCancelledUpload(api, cache, file, plan)
        assertFalse(file.exists()); assertTrue("ledger" in api.created); assertEquals("one", cache.revisions["meta"]?.id)
    }

    @Test fun cancelledCleanupNetworkFailureIsRetryableAndLaterCompletesWithoutReupload() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi().apply { failRead = "meta" }
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        val attachment = privateQueueDirectory(cache.directory).resolve(jobId).apply { mkdirs() }.resolve("attachment").apply { writeText("keep") }
        val document = doc().copy(fileId = "meta", schemaVersion = 2, lineageId = "ledger")
        val plan = JSONObject().put("jobId", jobId).put("folder", "folder").put("metadataId", "meta")
            .put("document", document.json()).put("attachments", JSONArray()).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
        writePrivatePlan(file, plan)
        val failure = runCatching { reconcileCancelledUpload(api, cache, file, plan) }.exceptionOrNull()
        assertTrue(failure is DriveFailure); recordCancelledCleanupFailure(file, failure as Exception)
        assertTrue(file.exists()); assertTrue(attachment.exists()); assertTrue(api.created.isEmpty())

        api.failRead = null; api.bodies["meta"] = byteArrayOf(1)
        reconcileCancelledUpload(api, cache, file, JSONObject(file.readText()))
        assertFalse(file.exists()); assertFalse(attachment.exists())
        assertEquals(listOf("ledger"), api.created)
    }

    @Test fun cancelledCleanupAuthFailureKeepsCancellationIntentUntilReauthorized() = runBlocking {
        val cache = MemoryCache(); val api = FakeApi().apply { failRead = "meta"; readFailureStatus = 401 }
        val jobId = "12345678-1234-1234-1234-123456789abc"
        val file = privateQueueDirectory(cache.directory).resolve("$jobId.json")
        val document = doc().copy(fileId = "meta", schemaVersion = 2, lineageId = "ledger")
        val plan = JSONObject().put("jobId", jobId).put("folder", "folder").put("metadataId", "meta")
            .put("document", document.json()).put("attachments", JSONArray()).put("syncStatus", PrivateSyncStatus.CANCELLED.name)
        writePrivatePlan(file, plan)
        val failure = runCatching { reconcileCancelledUpload(api, cache, file, plan) }.exceptionOrNull() as DriveFailure
        recordCancelledCleanupFailure(file, failure)
        val retained = JSONObject(file.readText())
        assertEquals(PrivateSyncStatus.CANCELLED.name, retained.getString("syncStatus"))
        assertTrue(retained.getBoolean("cleanupActionRequired")); assertTrue(api.created.isEmpty())

        // Reauthorization re-runs cancellation cleanup, never the upload path.
        api.failRead = null; api.bodies["meta"] = byteArrayOf(1)
        reconcileCancelledUpload(api, cache, file, retained)
        assertFalse(file.exists()); assertEquals(listOf("ledger"), api.created)
    }

    private fun file(id: String, docId: String) = JSONObject().put("id", id).put("version", "1")
        .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "metadata").put("documentId", docId))

    private fun summaryFile(document: PrivateDocument) = JSONObject().put("id", document.fileId)
        .put("version", "1")
        .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "metadata")
            .put("documentId", document.id).put("listDocumentId", document.id)
            .put("listRevision", document.revision).put("listTitle", document.title)
            .put("listCategory", document.category).put("listCreated", document.created.toString())
            .put("listModified", document.modified.toString()).put("listPinned", document.pinned.toString())
            .put("listDeleted", document.deleted.toString())
            .put("listParents", document.parents.joinToString(PRIVATE_PARENT_SEPARATOR)))

    private fun lineageFile(lineage: PrivateLineage) = JSONObject().put("id", lineage.ledgerId).put("version", "1")
        .put("appProperties", JSONObject().put("app", DriveMarker).put("kind", "lineage")
            .put("documentId", lineage.documentId).put("listRevision", lineage.revision))

    @Test fun externalRemovalMustNotPromoteAncestor() = runBlocking {
        val cache = MemoryCache().apply {
            checkpoint = "start"
            revisions["a"] = doc(revision = "a").copy(fileId = "a")
            revisions["b"] = doc(revision = "b", parents = listOf("a")).copy(fileId = "b")
        }
        val api = FakeApi().apply {
            changePages["start"] = DrivePage(listOf(JSONObject().put("fileId", "b").put("removed", true)), null, "next")
        }
        PrivateDriveSync(api, cache).sync {}
        assertFalse("Deleted B must continue to suppress A", privateHeads(cache.revisions.values).any { it.revision == "a" })
        assertTrue(api.trashed.isEmpty())
    }

    private class MemoryCache : PrivateMetadataCache {
        override val directory = Files.createTempDirectory("drive-test").toFile().apply { deleteOnExit() }
        override var checkpoint: String? = null
        override var lastSync = 0L
        override var initialSyncComplete = false
        override var initialListReady = false
        var saveCount = 0
        override val revisions = linkedMapOf<String, PrivateDocument>()
        override val versions = linkedMapOf<String, String>()
        override val cleanup = linkedSetOf<String>()
        override val lineages = linkedMapOf<String, PrivateLineage>()
        override fun save() { saveCount++ }
    }
    private class FakeApi : PrivateDriveApi {
        val pages = mutableMapOf<String?, DrivePage>()
        val listQueries = mutableListOf<String>()
        val changePages = mutableMapOf<String, DrivePage>()
        val bodies = mutableMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()
        val trashed = mutableListOf<String>()
        var failTrash: String? = null
        var creates = 0
        val created = mutableListOf<String>()
        val generatedIds = mutableListOf<String>()
        val createdMetadata = mutableMapOf<String, JSONObject>()
        val patchedProperties = mutableMapOf<String, JSONObject>()
        var failCreate: String? = null
        var failPatch: String? = null
        var failRead: String? = null
        var readFailureStatus = 503
        var readDelayMs = 0L
        val readDelayById = mutableMapOf<String, Long>()
        val readStarted = mutableListOf<String>()
        val readFinished = mutableListOf<String>()
        val readEvents = mutableListOf<String>()
        private var activeReads = 0
        var maxConcurrentReads = 0
        override suspend fun list(query: String, page: String?): DrivePage {
            listQueries += query
            return pages[page] ?: throw DriveFailure(503)
        }
        override suspend fun startToken() = "start"
        override suspend fun changes(page: String): DrivePage {
            if (page == "expired") throw DriveFailure(410)
            return changePages[page] ?: DrivePage(emptyList(), null, "next")
        }
        override suspend fun read(id: String): ByteArray {
            synchronized(this) {
                activeReads++; maxConcurrentReads = maxOf(maxConcurrentReads, activeReads)
                readStarted += id; readEvents += "start:$id"
            }
            try {
                reads += id
                val delayMs = readDelayById[id] ?: readDelayMs
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                if (id == failRead) throw DriveFailure(readFailureStatus)
                return bodies.getValue(id)
            } finally { synchronized(this) { activeReads--; readFinished += id; readEvents += "finish:$id" } }
        }
        override suspend fun generateId() = "generated".also { generatedIds += it }
        override suspend fun create(id: String, metadata: JSONObject, bytes: ByteArray?, mime: String) {
            creates++; created += id; createdMetadata[id] = JSONObject(metadata.toString()); onCreate?.invoke(id); if (id == failCreate) throw DriveFailure(503)
        }
        override suspend fun patchAppProperties(id: String, properties: JSONObject) {
            patchedProperties[id] = JSONObject(properties.toString())
            if (id == failPatch) throw DriveFailure(503)
        }
        override suspend fun trash(id: String) { trashed += id; if (id == failTrash) throw DriveFailure(503) }
        override suspend fun copy(sourceId: String, id: String, metadata: JSONObject) { creates++; created += id }
        var onCreate: ((String) -> Unit)? = null
    }
}
