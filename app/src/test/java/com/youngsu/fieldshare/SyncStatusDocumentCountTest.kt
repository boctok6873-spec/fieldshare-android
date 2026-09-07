package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusDocumentCountTest {
    @Test
    fun activeDocumentCountLabel_showsTheServerCount() {
        assertEquals("500개", activeDocumentCountLabel(500L))
    }

    @Test
    fun activeDocumentCountLabel_showsPlaceholderWhileLoadingOrUnavailable() {
        assertEquals("—", activeDocumentCountLabel(null))
    }
}
