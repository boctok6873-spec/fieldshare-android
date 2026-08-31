package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserProfileValidationTest {
    @Test
    fun normalize_trimsValidDisplayName() {
        assertEquals("홍길동", UserProfileValidation.normalize("  홍길동  "))
    }

    @Test
    fun normalize_rejectsBlankAndTooLongDisplayNames() {
        assertNull(UserProfileValidation.normalize("   "))
        assertNull(UserProfileValidation.normalize("가".repeat(UserProfileValidation.MaxDisplayNameLength + 1)))
    }
}
