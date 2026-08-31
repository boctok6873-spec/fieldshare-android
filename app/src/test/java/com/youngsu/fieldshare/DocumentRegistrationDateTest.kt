package com.youngsu.fieldshare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRegistrationDateTest {
    @Test
    fun recentActivityWindow_includesExactThirtyDayBoundaryOnly() {
        val now = 2_000_000_000_000L

        assertTrue(isCreatedWithinRecentActivityWindow(now - RecentActivityWindowMillis, now))
        assertFalse(isCreatedWithinRecentActivityWindow(now - RecentActivityWindowMillis - 1, now))
        assertFalse(isCreatedWithinRecentActivityWindow(null, now))
    }

    @Test
    fun recentActivitiesForDisplay_includesActivityCreatedAfterListenerStarted() {
        val listenerStartedAt = 2_000_000_000_000L
        val activityCreatedAt = listenerStartedAt + 1_000L
        val activity = DocumentActivity(
            id = "new-activity",
            action = DocumentActivityAction.CREATED,
            documentId = "document-1",
            documentTitle = "새 자료",
            actorUid = "user-1",
            actorLabel = "사용자",
            createdAt = Timestamp(Date(activityCreatedAt))
        )

        assertFalse(isCreatedWithinRecentActivityWindow(activityCreatedAt, listenerStartedAt))
        assertEquals(
            listOf(activity),
            recentActivitiesForDisplay(
                activities = listOf(activity),
                nowMillis = activityCreatedAt + 1_000L
            )
        )
    }

    @Test
    fun allMode_includesOldAndLegacyDocumentsWithoutCreatedAt() {
        val now = 2_000_000_000_000L
        val documents = listOf(
            FieldDocument(
                id = "old",
                title = "오래된 자료",
                category = "기타",
                date = "2026.07.01",
                createdAtMillis = now - RecentActivityWindowMillis - 1,
                source = DocumentSource.TEXT,
                content = "",
                thumbnailColor = Color.White,
                icon = Icons.Default.Description
            ),
            FieldDocument(
                id = "legacy",
                title = "기존 자료",
                category = "기타",
                date = "등록일 확인 중",
                createdAtMillis = null,
                source = DocumentSource.TEXT,
                content = "",
                thumbnailColor = Color.White,
                icon = Icons.Default.Description
            )
        )

        assertEquals(
            setOf("old", "legacy"),
            homeDocumentsForDisplay(documents, "전체", HomeDocumentDisplayMode.ALL, false)
                .map { it.id }
                .toSet()
        )
    }

    @Test
    fun allMode_sortsDocumentWithTimestampBeforeLegacyDocumentWithoutTimestamp() {
        val now = 2_000_000_000_000L
        val newlyRegistered = FieldDocument(
            id = "new",
            title = "새 자료",
            category = "기타",
            date = "2026.08.31",
            createdAtMillis = now,
            source = DocumentSource.TEXT,
            content = "",
            thumbnailColor = Color.White,
            icon = Icons.Default.Description
        )
        val legacy = newlyRegistered.copy(
            id = "legacy",
            title = "기존 자료",
            date = "등록일 확인 중",
            createdAtMillis = null
        )

        assertEquals(
            listOf("new", "legacy"),
            homeDocumentsForDisplay(
                documents = listOf(legacy, newlyRegistered),
                selectedCategory = "전체",
                displayMode = HomeDocumentDisplayMode.ALL,
                hasSearchQuery = false
            ).map { it.id }
        )
    }

    @Test
    fun registrationDate_usesKoreanReadableFormatAndHandlesMissingTimestamp() {
        assertEquals("2026.08.31", formatRegistrationDate(1_788_134_400_000L))
        assertEquals("등록일 확인 중", formatRegistrationDate(null))
    }
}
