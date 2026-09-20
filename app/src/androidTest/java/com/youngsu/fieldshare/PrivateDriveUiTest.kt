package com.youngsu.fieldshare

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PrivateDriveUiTest {
    @get:Rule val compose = createAndroidComposeRule<PrivateUiTestActivity>()

    @Test fun disconnectedPrivateTabShowsProfileGuidanceWithoutQueryingDrive() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "test-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        val repository = DriveConnectionRepository(context)
        var openedProfile = false
        try {
            compose.setContent {
                val scope = rememberCoroutineScope()
                MaterialTheme {
                    FieldShareHomeScreen(selectedCategory = PrivateCategory,
                        privateContent = {
                            PrivateLibrary(
                                repository = repository,
                                query = "",
                                mode = HomeDocumentDisplayMode.ALL,
                                scope = scope,
                                onProfile = { openedProfile = true },
                                onDocumentClick = {}
                            )
                        }
                    )
                }
            }
            compose.onNodeWithText("내 자료를 사용하려면 내 정보에서 Google Drive를 연결해 주세요.").assertIsDisplayed()
            compose.onNodeWithText("내 정보로 이동").performClick()
            assertTrue(openedProfile)
        } finally { root.deleteRecursively() }
    }

    @Test fun existingBodyUrlUsesBrowserHandler() {
        var opened: String? = null
        val url = "https://example.com/guide"
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                MaterialTheme { LinkedDocumentText(url, Modifier.testTag("body-url")) }
            }
        }
        compose.onNodeWithTag("body-url").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(url, opened) }
    }
}
