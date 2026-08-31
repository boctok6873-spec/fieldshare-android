package com.youngsu.fieldshare

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class PresenceSummary(
    val onlineUserCount: Int = 0,
    val onlineDeviceCount: Int = 0,
    val deviceTypeCounts: Map<String, Int> = emptyMap()
)

/**
 * Stores one non-identifying random installation ID per app installation. Presence is intentionally
 * approximate: an ungraceful disconnect is removed by Realtime Database after connection loss.
 */
class FirebasePresenceRepository(
    context: Context,
    private val currentUserId: String,
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val installationId = appContext
        .getSharedPreferences("fieldshare_presence", Context.MODE_PRIVATE)
        .let { preferences ->
            preferences.getString("installation_id", null) ?: UUID.randomUUID().toString().also { id ->
                preferences.edit().putString("installation_id", id).apply()
            }
        }
    private val deviceType = if (appContext.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
    private val presenceNode = database.getReference("presence").child(currentUserId).child(installationId)
    private val connectedNode = database.getReference(".info/connected")
    private var started = false

    private val connectionListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            if (snapshot.getValue(Boolean::class.java) == true) markOnline()
        }

        override fun onCancelled(error: DatabaseError) = Unit
    }

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        connectedNode.addValueEventListener(connectionListener)
        markOnline()
    }

    fun stop() {
        if (!started) return
        started = false
        connectedNode.removeEventListener(connectionListener)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        markOffline()
    }

    override fun onStart(owner: LifecycleOwner) = markOnline()

    override fun onStop(owner: LifecycleOwner) = markOffline()

    fun observePresence(): Flow<PresenceSummary> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val onlineUsers = mutableSetOf<String>()
                val deviceTypes = mutableMapOf<String, Int>()
                var deviceCount = 0
                snapshot.children.forEach { userSnapshot ->
                    userSnapshot.children.forEach { installationSnapshot ->
                        if (installationSnapshot.child("online").getValue(Boolean::class.java) == true) {
                            onlineUsers += userSnapshot.key.orEmpty()
                            deviceCount++
                            val type = installationSnapshot.child("deviceType").getValue(String::class.java) ?: "unknown"
                            deviceTypes[type] = (deviceTypes[type] ?: 0) + 1
                        }
                    }
                }
                trySend(PresenceSummary(onlineUsers.size, deviceCount, deviceTypes))
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(PresenceSummary())
            }
        }
        database.getReference("presence").addValueEventListener(listener)
        awaitClose { database.getReference("presence").removeEventListener(listener) }
    }

    private fun markOnline() {
        if (!started) return
        presenceNode.onDisconnect().removeValue()
        presenceNode.setValue(
            mapOf(
                "online" to true,
                "lastSeen" to ServerValue.TIMESTAMP,
                "deviceType" to deviceType
            )
        )
    }

    private fun markOffline() {
        presenceNode.onDisconnect().cancel()
        presenceNode.updateChildren(
            mapOf(
                "online" to false,
                "lastSeen" to ServerValue.TIMESTAMP
            )
        )
    }
}
