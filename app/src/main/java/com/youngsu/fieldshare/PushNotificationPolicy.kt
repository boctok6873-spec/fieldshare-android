package com.youngsu.fieldshare

internal fun shouldStoreFcmToken(
    notificationsOptedIn: Boolean,
    systemNotificationsAllowed: Boolean,
    hasAuthenticatedUser: Boolean,
    token: String
): Boolean = notificationsOptedIn && systemNotificationsAllowed && hasAuthenticatedUser && token.isNotBlank()
