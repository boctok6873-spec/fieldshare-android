package com.youngsu.fieldshare

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PushNotificationManagerConcurrencyTest {
    @Test
    fun disableAndTokenRefresh_areSerializedSoOptOutCannotBeRelinked() = runBlocking {
        var optedIn = true
        var deviceRegistrationWrites = 0
        val disableEntered = CompletableDeferred<Unit>()
        val finishDisable = CompletableDeferred<Unit>()
        val tokenRefreshStarted = CompletableDeferred<Unit>()

        val disable = async(Dispatchers.Default) {
            PushNotificationManager.withStateTransitionLock {
                optedIn = false
                disableEntered.complete(Unit)
                finishDisable.await()
            }
        }
        disableEntered.await()
        val tokenRefresh = async(Dispatchers.Default) {
            tokenRefreshStarted.complete(Unit)
            PushNotificationManager.withStateTransitionLock {
                if (optedIn) deviceRegistrationWrites += 1
            }
        }
        tokenRefreshStarted.await()

        withTimeout(1_000) {
            assertFalse(tokenRefresh.isCompleted)
        }
        finishDisable.complete(Unit)
        awaitAll(disable, tokenRefresh)

        assertEquals(0, deviceRegistrationWrites)
    }
}
