package com.youngsu.fieldshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingNotificationCleanupTest {
    @Test
    fun remoteFailure_keepsUidAndInstallationIdAfterTokenDeletionSucceeds() {
        val prepared = prepareNotificationCleanup(
            PendingNotificationCleanup(),
            currentUserId = "uid-1",
            currentInstallationId = "installation-1"
        )

        val remaining = applyNotificationCleanupResults(
            prepared,
            remoteDeletionSucceeded = false,
            tokenDeletionSucceeded = true
        )

        assertTrue(remaining.hasWork)
        assertTrue(remaining.needsRemoteDeletion)
        assertFalse(remaining.needsTokenDeletion)
        assertEquals("uid-1", remaining.userId)
        assertEquals("installation-1", remaining.installationId)
    }

    @Test
    fun cleanupIdentifiers_areOnlyDiscardedAfterBothCleanupOperationsSucceed() {
        val pending = PendingNotificationCleanup(
            userId = "uid-1",
            installationId = "installation-1",
            needsRemoteDeletion = true,
            needsTokenDeletion = true
        )

        val partial = applyNotificationCleanupResults(pending, remoteDeletionSucceeded = true, tokenDeletionSucceeded = false)
        val complete = applyNotificationCleanupResults(partial, remoteDeletionSucceeded = null, tokenDeletionSucceeded = true)

        assertTrue(partial.hasWork)
        assertEquals("installation-1", partial.installationId)
        assertFalse(complete.hasWork)
    }
}
