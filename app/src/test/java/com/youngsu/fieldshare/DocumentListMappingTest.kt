package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DocumentListMappingTest {
    @Test
    fun listMapping_keepsStoragePathsWithoutResolvingDownloadUrls() {
        val storagePath = "images/document-1/original.jpg"
        val document = FirebaseDocumentDto(
            id = "document-1",
            title = "점검 사진",
            source = DocumentSource.IMAGE.name,
            imagePaths = listOf(storagePath)
        ).toListFieldDocument()

        assertEquals(storagePath, document.imageUri)
        assertEquals(listOf(storagePath), document.imageUris)
        assertFalse(document.imageUri.orEmpty().startsWith("http"))
    }
}
