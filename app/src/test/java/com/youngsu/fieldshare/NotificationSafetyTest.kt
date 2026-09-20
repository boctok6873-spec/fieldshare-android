package com.youngsu.fieldshare

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSafetyTest {
    @Test
    fun fcmToken_isStoredOnlyForAnOptedInAuthorizedSignedInUser() {
        assertTrue(shouldStoreFcmToken(true, true, true, "token"))
        assertFalse(shouldStoreFcmToken(false, true, true, "token"))
        assertFalse(shouldStoreFcmToken(true, false, true, "token"))
        assertFalse(shouldStoreFcmToken(true, true, false, "token"))
        assertFalse(shouldStoreFcmToken(true, true, true, ""))
    }

    @Test
    fun freshInstallDefaultsOnWhileExistingExplicitChoiceAndPermissionStateRemainSeparate() {
        assertTrue(initialNotificationIntent(null))
        assertTrue(initialNotificationIntent(true))
        assertFalse(initialNotificationIntent(false))
        assertEquals(NotificationDeliveryState.PERMISSION_REQUIRED, notificationDeliveryState(true, false))
        assertEquals(NotificationDeliveryState.ON, notificationDeliveryState(true, true))
        assertEquals(NotificationDeliveryState.OFF, notificationDeliveryState(false, true))
        // Permission denial must not mutate the saved user intent.
        assertTrue(initialNotificationIntent(true))
    }

    @Test
    fun notificationPreferences_areExcludedFromCloudBackupAndDeviceTransfer() {
        val extractionRules = resourceFile("data_extraction_rules.xml").readText()
        val legacyRules = resourceFile("backup_rules.xml").readText()

        assertTrue(extractionRules.contains("<cloud-backup>"))
        assertTrue(extractionRules.contains("<device-transfer>"))
        assertTrue(extractionRules.contains("domain=\"sharedpref\" path=\"fieldshare_notifications.xml\""))
        assertTrue(legacyRules.contains("domain=\"sharedpref\" path=\"fieldshare_notifications.xml\""))
    }

    private fun resourceFile(name: String): File = sequenceOf(
        File("src/main/res/xml/$name"),
        File("app/src/main/res/xml/$name")
    ).firstOrNull(File::isFile) ?: error("Missing Android XML resource: $name")
}
