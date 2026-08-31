package com.youngsu.fieldshare

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDocumentContentTest {
    @Test
    fun contentForDocumentSave_keepsImageContentEnteredByUser() {
        assertEquals("현장 점검 내용을 기록합니다.", contentForDocumentSave(true, "현장 점검 내용을 기록합니다."))
    }

    @Test
    fun contentForDocumentSave_keepsTextDocumentFallback() {
        assertEquals("등록된 내용이 없습니다.", contentForDocumentSave(false, ""))
    }

    @Test
    fun imageContentForDetail_hidesLegacyPlaceholderAndBlankContent() {
        assertNull(imageContentForDetail("원본 이미지 자료"))
        assertNull(imageContentForDetail("   "))
        assertEquals("실제 등록 내용", imageContentForDetail("실제 등록 내용"))
    }

    @Test
    fun canEditDocumentText_allowsTextDocumentsAndImagesWithVisibleTextOnly() {
        assertTrue(canEditDocumentText(document(source = DocumentSource.TEXT, content = "")))
        assertTrue(canEditDocumentText(document(source = DocumentSource.IMAGE, content = "현장 점검 내용")))
        assertFalse(canEditDocumentText(document(source = DocumentSource.IMAGE, content = "")))
        assertFalse(canEditDocumentText(document(source = DocumentSource.IMAGE, content = "원본 이미지 자료")))
    }

    private fun document(source: DocumentSource, content: String) = FieldDocument(
        id = "test",
        title = "테스트 자료",
        category = "기타",
        date = "2026.08.31",
        source = source,
        content = content,
        thumbnailColor = Color.Gray,
        icon = Icons.Default.Description
    )
}
