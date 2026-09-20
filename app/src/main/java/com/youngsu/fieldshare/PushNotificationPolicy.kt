package com.youngsu.fieldshare

internal fun shouldStoreFcmToken(
    notificationsOptedIn: Boolean,
    systemNotificationsAllowed: Boolean,
    hasAuthenticatedUser: Boolean,
    token: String
): Boolean = notificationsOptedIn && systemNotificationsAllowed && hasAuthenticatedUser && token.isNotBlank()

/** Missing preference means fresh installation; a stored value is always an existing user's choice. */
internal fun initialNotificationIntent(storedIntent: Boolean?): Boolean = storedIntent ?: true

internal enum class NotificationDeliveryState { ON, PERMISSION_REQUIRED, OFF }

internal fun notificationDeliveryState(userIntentEnabled: Boolean, systemNotificationsAllowed: Boolean): NotificationDeliveryState = when {
    !userIntentEnabled -> NotificationDeliveryState.OFF
    systemNotificationsAllowed -> NotificationDeliveryState.ON
    else -> NotificationDeliveryState.PERMISSION_REQUIRED
}
