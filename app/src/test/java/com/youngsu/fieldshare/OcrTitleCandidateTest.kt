package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTitleCandidateTest {
    @Test
    fun selectOcrTitleCandidate_usesTopTextFromFirstImage() {
        assertEquals(
            "에어컨 C422 점검 안내",
            selectOcrTitleCandidate(listOf(listOf("에어컨 C422 점검 안내", "점검 순서")))
        )
    }

    @Test
    fun selectOcrTitleCandidate_returnsNullForBlankOcr() {
        assertNull(selectOcrTitleCandidate(listOf(listOf(" ", "\n"))))
    }

    @Test
    fun selectOcrTitleCandidate_ignoresLaterImages() {
        assertEquals(
            "첫 페이지 제목",
            selectOcrTitleCandidate(
                listOf(
                    listOf("첫 페이지 제목"),
                    listOf("두 번째 페이지 제목")
                )
            )
        )
    }

    @Test
    fun autoTitleIfEligible_doesNotOverwriteUserTitle() {
        assertNull(autoTitleIfEligible("사용자 제목", titleEditedByUser = true, candidate = "OCR 제목"))
        assertNull(autoTitleIfEligible("", titleEditedByUser = true, candidate = "OCR 제목"))
        assertEquals("OCR 제목", autoTitleIfEligible("", titleEditedByUser = false, candidate = "OCR 제목"))
    }

    @Test
    fun selectOcrTitleCandidate_truncatesLongTitleNaturally() {
        val candidate = selectOcrTitleCandidate(listOf(listOf("현장 점검 제목 안내 ".repeat(8))))

        requireNotNull(candidate)
        assert(candidate.endsWith("…"))
        assert(candidate.length <= 49)
    }
}
