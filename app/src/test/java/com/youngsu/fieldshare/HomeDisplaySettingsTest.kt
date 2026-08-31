package com.youngsu.fieldshare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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
    fun allMode_searchesAllLocalDocumentsWithoutCategoryRestriction() {
        val documents = listOf(
            document("old", "TV", 1).copy(content = "필터 청소"),
            document("new", "냉장고", 3).copy(description = "필터 교체")
        )

        assertEquals(listOf("new", "old"), searchLocalDocuments(documents, "필터").map { it.id })
        assertEquals(true, usesLocalHomeSearch(HomeDocumentDisplayMode.ALL, "필터"))
        assertEquals(false, usesLocalHomeSearch(HomeDocumentDisplayMode.RECENT_ONLY, "필터"))
        assertEquals(false, usesLocalHomeSearch(HomeDocumentDisplayMode.HIDDEN, "필터"))
    }

    @Test
    fun allMode_startsLocalSearchFromOneCharacterWhileOtherModesRequireTwo() {
        val documents = listOf(
            document("aircon", "에어컨", 1).copy(content = "에어컨 점검")
        )

        assertEquals(1, minimumHomeSearchQueryLength(HomeDocumentDisplayMode.ALL))
        assertEquals(2, minimumHomeSearchQueryLength(HomeDocumentDisplayMode.RECENT_ONLY))
        assertEquals(2, minimumHomeSearchQueryLength(HomeDocumentDisplayMode.HIDDEN))
        assertEquals(listOf("aircon"), searchLocalDocuments(documents, "에").map { it.id })
        assertEquals(true, usesLocalHomeSearch(HomeDocumentDisplayMode.ALL, "에"))
        assertEquals(false, usesLocalHomeSearch(HomeDocumentDisplayMode.RECENT_ONLY, "에"))
        assertEquals(false, usesLocalHomeSearch(HomeDocumentDisplayMode.HIDDEN, "에"))
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
