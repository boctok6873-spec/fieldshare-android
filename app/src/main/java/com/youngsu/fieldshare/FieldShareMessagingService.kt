package com.youngsu.fieldshare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FieldShareMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            PushNotificationManager(applicationContext, userId)
                .updateTokenIfEligible(token)
                .onFailure { error ->
                    android.util.Log.w(TAG, "FCM token refresh registration failed", error)
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val deliveryId = message.data["deliveryId"]
        if (deliveryId != null && hasDisplayedDelivery(deliveryId)) return

        val title = message.data["title"] ?: "새 자료가 등록되었습니다"
        val body = message.data["body"] ?: "자료공유에서 새 자료를 확인해 주세요."
        createNotificationChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()

        NotificationManagerCompat.from(this).notify(
            message.data["documentId"]?.hashCode() ?: NOTIFICATION_ID,
            notification
        )
        deliveryId?.let(::recordDisplayedDelivery)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "신규 자료 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "새 자료가 등록되면 알려드립니다."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasDisplayedDelivery(deliveryId: String): Boolean = notificationPreferences()
        .getStringSet(KEY_DELIVERED_IDS, emptySet())
        .orEmpty()
        .contains(deliveryId)

    private fun recordDisplayedDelivery(deliveryId: String) {
        val previous = notificationPreferences().getStringSet(KEY_DELIVERED_IDS, emptySet()).orEmpty()
        val recent = (previous + deliveryId).toList().takeLast(MAX_REMEMBERED_DELIVERIES).toSet()
        notificationPreferences().edit().putStringSet(KEY_DELIVERED_IDS, recent).apply()
    }

    private fun notificationPreferences() = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

    private companion object {
        const val CHANNEL_ID = "new_documents"
        const val NOTIFICATION_ID = 1001
        const val PREFERENCES_NAME = "fieldshare_notifications"
        const val KEY_DELIVERED_IDS = "delivered_notification_ids"
        const val MAX_REMEMBERED_DELIVERIES = 100
        const val TAG = "FieldShareMessaging"
    }
}
