package com.youngsu.fieldshare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDeletionVisibilityTest {
    @Test
    fun hidePendingDeletionDocuments_excludesLocallyMarkedDocumentImmediately() {
        val visible = hidePendingDeletionDocuments(
            documents = listOf(document("visible"), document("deleting")),
            pendingDocumentIds = setOf("deleting")
        )

        assertEquals(listOf("visible"), visible.map { it.id })
    }

    private fun document(id: String) = FieldDocument(
        id = id,
        title = id,
        category = "기타",
        date = "2026-08-30",
        source = DocumentSource.TEXT,
        content = "",
        thumbnailColor = Color.White,
        icon = Icons.Default.Folder
    )
}
