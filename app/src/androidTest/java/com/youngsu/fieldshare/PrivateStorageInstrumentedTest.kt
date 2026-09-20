package com.youngsu.fieldshare

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class PrivateStorageInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationIsAtomicAndPersistentWithoutChangingOtherPreferences() {
        val suffix = "test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$name-$suffix", mode)
        }
        val prefs = context.getSharedPreferences("fieldshare_settings", Context.MODE_PRIVATE)
        try {
            for (old in listOf(null, "recent_only", "hidden")) {
                prefs.edit().clear().putString("home_display_mode", old).putString("unrelated", "keep").commit()
                val repository = AppSettingsRepository(context)
                assertEquals(HomeDocumentDisplayMode.ALL, repository.getHomeDisplayMode())
                assertTrue(prefs.getBoolean("drive_home_all_migration_v1", false))
                assertEquals("keep", prefs.getString("unrelated", null))
                repository.setHomeDisplayMode(HomeDocumentDisplayMode.RECENT_ONLY)
                assertEquals(HomeDocumentDisplayMode.RECENT_ONLY, AppSettingsRepository(context).getHomeDisplayMode())
            }
        } finally { app.deleteSharedPreferences("fieldshare_settings-$suffix") }
    }

    @Test fun privateSnapshotSurvivesRestartAndCannotCrossAccountDirectories() {
        val root = File(app.noBackupFilesDir, "test-${UUID.randomUUID()}")
        try {
            val a = PrivateDriveStore(File(root, accountCacheKey("test-account-a")))
            val doc = PrivateDocument("d", "r", emptyList(), "제목", "본문", "냉장고", 1, 2, "OCR", fileId = "f")
            a.revisions["f"] = doc; a.checkpoint = "next"; a.cleanup.add("pending"); a.save()
            val reopened = PrivateDriveStore(a.directory)
            assertEquals(doc, reopened.revisions["f"]); assertEquals("next", reopened.checkpoint)
            assertEquals(setOf("pending"), reopened.cleanup)
            assertTrue(PrivateDriveStore(File(root, accountCacheKey("test-account-b"))).revisions.isEmpty())
            assertTrue(a.directory.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath))
        } finally { root.deleteRecursively() }
    }

    @Test fun draftFilesStayInCacheAndRemainReadableThroughFileProvider() {
        val context = PrivateRegistrationContext(app, "test-account")
        try {
            val file = File(context.getExternalFilesDir("Pictures"), "sample.txt").apply { writeText("private test") }
            assertTrue(file.canonicalPath.startsWith(app.cacheDir.canonicalPath))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            assertEquals("private test", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { context.clearDraft() }
    }

    @Test fun disconnectWaitsForOldSyncThenClearsItsResultsWithoutDeletingDriveFiles() = runBlocking {
        val root = File(app.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var trashCalls = 0
        val api = object : PrivateDriveApi {
            override suspend fun startToken(): String { started.complete(Unit); release.await(); return "start" }
            override suspend fun list(query: String, page: String?) = DrivePage(emptyList(), null)
            override suspend fun changes(page: String) = DrivePage(emptyList(), null, "next")
            override suspend fun read(id: String): ByteArray = error("No originals should be downloaded")
            override suspend fun generateId(): String = error("No remote files should be created")
            override suspend fun create(id: String, metadata: JSONObject, bytes: ByteArray?, mime: String) = error("No uploads")
            override suspend fun copy(sourceId: String, id: String, metadata: JSONObject) = error("No copies")
            override suspend fun trash(id: String) { trashCalls++ }
        }
        try {
            File(root, "private-drive").mkdirs()
            File(root, "private-drive/connection.json").writeText("{\"id\":\"fake-subject\",\"email\":\"test@example.com\"}")
            val repository = DriveConnectionRepository(context, api)
            assertEquals("test@example.com", repository.state.value.email)
            val sync = async(Dispatchers.Default) { repository.sync() }
            withTimeout(5_000) { started.await() }
            val disconnect = async(Dispatchers.Default) { repository.disconnect() }
            assertFalse(disconnect.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { sync.await(); disconnect.await() }
            assertNull(repository.state.value.email)
            assertTrue(repository.state.value.documents.isEmpty())
            assertEquals(0, trashCalls)
            assertFalse(File(root, "private-drive/connection.json").exists())
            assertNull(DriveConnectionRepository(context, api).state.value.accountKey)
        } finally { release.complete(Unit); root.deleteRecursively() }
    }
}
