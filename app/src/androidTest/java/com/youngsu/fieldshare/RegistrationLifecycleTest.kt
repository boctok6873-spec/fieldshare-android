package com.youngsu.fieldshare

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File

class RegistrationLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<RegistrationLifecycleTestActivity>()
    private fun draft() = PrivateRegistrationContext(compose.activity, "unconnected", compose.activity.draftId)
    @After fun cleanup() { draft().apply { endSave(); clearDraft() } }

    @Test fun recreateRetainsSharedImageScanOcrAndCameraAndFailedSaveCanRetry() {
        val id = compose.activity.draftId
        val directory = draft().draft
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(id, compose.activity.draftId)
        assertTrue(File(directory, "image.png").exists())
        assertTrue(File(directory, "waiting-camera.jpg").exists())
        compose.onNodeWithText("저장 후 공유").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("냉장고 RF85 모델 검색 텍스트", compose.activity.savedOcr)
            assertTrue(compose.activity.savedImagesReadable)
            assertTrue(File(directory, "image.png").exists())
        }
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.onNodeWithText("저장 후 공유").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(compose.activity.savedImagesReadable) }
    }

    @Test fun privateDraftRecreationAndSuccessCleanOnlyThatDraft() {
        compose.onNodeWithText("내 자료").performScrollTo().performClick()
        val dir = draft().draft
        val other = PrivateRegistrationContext(compose.activity, "another-account")
        val keep = File(other.cacheDir, "keep").apply { writeText("other draft") }
        try {
            compose.activityRule.scenario.recreate(); compose.waitForIdle()
            compose.runOnIdle { compose.activity.saveSucceeded = true }
            compose.onNodeWithText("내 자료 저장").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(compose.activity.savedImagesReadable)
                assertEquals("냉장고 RF85 모델 검색 텍스트", compose.activity.savedOcr)
                assertFalse(dir.exists()); assertTrue(keep.exists())
            }
        } finally { other.clearDraft() }
    }

    @Test fun explicitCancelRemovesDraftButDisposalDidNot() {
        val dir = draft().draft
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertTrue(dir.exists())
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(dir.exists())
    }

    @Test fun lostFileAndInterruptedOcrGetRecoveryGuidanceAfterRecreate() {
        val context = draft()
        compose.runOnIdle {
            val state = context.readSnapshot()!!.put("ocrStatus", "PENDING").put("ocr", "")
            context.writeSnapshot(state)
            File(context.cacheDir, "image.png").delete()
        }
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.waitUntil(5_000) { context.readSnapshot()?.optString("ocrStatus") == "FAILED" }
        val state = context.readSnapshot()!!
        assertEquals("FAILED", state.getString("ocrStatus")); assertEquals("", state.getString("ocr"))
        assertFalse(context.readable(state.getString("image")))
        compose.onNodeWithText("중단된 이미지/OCR 처리를 복원하지 못했습니다. 다시 첨부해 주세요.").performScrollTo()
    }

    @Test fun staleCleanerExcludesActiveAndSavingDrafts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "cleaner-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val app = object : android.content.ContextWrapper(base) { override fun getCacheDir() = root }
        val active = PrivateRegistrationContext(app, "cleanup-test")
        val saving = PrivateRegistrationContext(app, "cleanup-test")
        val stale = PrivateRegistrationContext(app, "cleanup-test")
        active.acquire(); saving.beginSave()
        val future = System.currentTimeMillis() + 8 * 24 * 60 * 60_000L
        try {
            PrivateRegistrationContext.cleanupStale(app, future)
            assertTrue(active.draft.exists()); assertTrue(saving.draft.exists()); assertFalse(stale.draft.exists())
        } finally { active.release(); active.clearDraft(); saving.endSave(); saving.clearDraft(); root.deleteRecursively() }
    }
}
