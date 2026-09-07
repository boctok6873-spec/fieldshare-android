package com.youngsu.fieldshare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDisplaySettingsTest {
    @Test
    fun recentMode_limitsAllDocumentsToTenAndCategoryToFive() {
        val allDocuments = (0 until 12).map { index -> document("all-$index", "기타", index) }
        val categoryDocuments = (0 until 7).map { index -> document("tv-$index", "TV", index) }

        assertEquals(10, limitHomeDocuments(allDocuments, "전체", HomeDocumentDisplayMode.RECENT_ONLY).size)
        assertEquals(5, limitHomeDocuments(categoryDocuments, "TV", HomeDocumentDisplayMode.RECENT_ONLY).size)
    }

    @Test
    fun allMode_doesNotLimitMatchingDocuments() {
        val documents = (0 until 12).map { index -> document("all-$index", "기타", index) }

        assertEquals(12, limitHomeDocuments(documents, "전체", HomeDocumentDisplayMode.ALL).size)
    }

    @Test
    fun hiddenMode_returnsNoDocuments() {
        val documents = (0 until 12).map { index -> document("all-$index", "기타", index) }

        assertEquals(0, limitHomeDocuments(documents, "전체", HomeDocumentDisplayMode.HIDDEN).size)
    }

    @Test
    fun hiddenMode_keepsMatchingDocumentsForSearchOnly() {
        val documents = (0 until 3).map { index -> document("all-$index", "기타", index) }

        assertEquals(0, homeDocumentsForDisplay(documents, "전체", HomeDocumentDisplayMode.HIDDEN, false).size)
        assertEquals(3, homeDocumentsForDisplay(documents, "전체", HomeDocumentDisplayMode.HIDDEN, true).size)
    }

    @Test
    fun recentMode_sortsByRegistrationDateDescending() {
        val documents = listOf(
            document("old", "TV", 1),
            document("new", "TV", 3),
            document("middle", "TV", 2)
        )

        assertEquals(listOf("new", "middle", "old"), limitHomeDocuments(documents, "TV", HomeDocumentDisplayMode.RECENT_ONLY).map { it.id })
    }

    @Test
    fun allMode_usesLocalSearchAfterTheServerHasLoadedAllDocuments() {
        val documents = listOf(
            document("old", "TV", 1).copy(content = "필터 청소"),
            document("new", "냉장고", 3).copy(description = "필터 교체")
        )
        val stream = DocumentStream.Data(documents, isFromCache = false)
        val route = homeSearchRoute(
            homeDisplayMode = HomeDocumentDisplayMode.ALL,
            isAllDocumentsLoaded = isAllDocumentsLoaded(HomeDocumentDisplayMode.ALL, stream)
        )

        assertEquals(listOf("new", "old"), searchLocalDocuments(documents, "필터").map { it.id })
        assertTrue(isAllDocumentsLoaded(HomeDocumentDisplayMode.ALL, stream))
        assertEquals(HomeSearchRoute.LOCAL_ALL_DOCUMENTS, route)
        assertTrue(usesLocalHomeSearch(route, "필터"))
    }

    @Test
    fun allMode_usesAlgoliaWhileTheServerListIsStillLoading() {
        val route = homeSearchRoute(
            homeDisplayMode = HomeDocumentDisplayMode.ALL,
            isAllDocumentsLoaded = isAllDocumentsLoaded(HomeDocumentDisplayMode.ALL, DocumentStream.Loading)
        )

        assertEquals(HomeSearchRoute.ALGOLIA, route)
        assertFalse(usesLocalHomeSearch(route, "검색"))
    }

    @Test
    fun allMode_usesAlgoliaWhileAnOnlineCacheSnapshotIsDisplayed() {
        val stream = DocumentStream.Data(listOf(document("cached", "TV", 1)), isFromCache = true)
        val route = homeSearchRoute(
            homeDisplayMode = HomeDocumentDisplayMode.ALL,
            isAllDocumentsLoaded = isAllDocumentsLoaded(HomeDocumentDisplayMode.ALL, stream)
        )

        assertFalse(isAllDocumentsLoaded(HomeDocumentDisplayMode.ALL, stream))
        assertEquals(HomeSearchRoute.ALGOLIA, route)
        assertFalse(usesLocalHomeSearch(route, "캐시"))
    }

    @Test
    fun allMode_usesCachedDocumentsAsLocalFallbackOnlyAfterAlgoliaFails() {
        val route = homeSearchRoute(
            homeDisplayMode = HomeDocumentDisplayMode.ALL,
            isAllDocumentsLoaded = false,
            hasAlgoliaFailedForCurrentQuery = true,
            hasCachedDocuments = true
        )

        assertEquals(HomeSearchRoute.LOCAL_CACHE_FALLBACK, route)
        assertTrue(usesLocalHomeSearch(route, "캐"))
    }

    @Test
    fun allMode_requiresTwoCharactersWhileLoadingAndOneAfterLocalDataIsReady() {
        assertEquals(2, minimumHomeSearchQueryLength(HomeSearchRoute.ALGOLIA))
        assertEquals(1, minimumHomeSearchQueryLength(HomeSearchRoute.LOCAL_ALL_DOCUMENTS))
        assertEquals(1, minimumHomeSearchQueryLength(HomeSearchRoute.LOCAL_CACHE_FALLBACK))
        assertEquals(
            "전체 자료 검색은 2글자 이상 입력해 주세요.",
            homeSearchMinimumQueryMessage(HomeDocumentDisplayMode.ALL, HomeSearchRoute.ALGOLIA)
        )
    }

    @Test
    fun allMode_filtersAlreadyLoadedDocumentsForCategoryTabs() {
        val documents = listOf(document("tv", "TV", 2), document("fridge", "냉장고", 3))

        assertEquals(listOf("tv"), homeDocumentsForDisplay(documents, "TV", HomeDocumentDisplayMode.ALL, false).map { it.id })
    }

    private fun document(id: String, category: String, day: Int) = FieldDocument(
        id = id,
        title = id,
        category = category,
        date = "2026-08-${day.toString().padStart(2, '0')}",
        source = DocumentSource.TEXT,
        content = "",
        thumbnailColor = Color.White,
        icon = Icons.Default.Description
    )
}
