package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentCreatorNameTest {
    @Test
    fun displayCreatorName_usesAnonymousFallbackForLegacyDocuments() {
        assertEquals("익명 사용자", displayCreatorName(null))
        assertEquals("익명 사용자", displayCreatorName("   "))
    }

    @Test
    fun displayCreatorName_trimsStoredName() {
        assertEquals("홍길동", displayCreatorName(" 홍길동 "))
    }
}
