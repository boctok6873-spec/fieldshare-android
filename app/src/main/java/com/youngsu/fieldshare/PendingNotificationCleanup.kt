package com.youngsu.fieldshare

/** Local, backup-excluded state for an opt-out whose remote cleanup has not finished yet. */
internal data class PendingNotificationCleanup(
    val userId: String? = null,
    val installationId: String? = null,
    val needsRemoteDeletion: Boolean = false,
    val needsTokenDeletion: Boolean = false
) {
    val hasWork: Boolean get() = needsRemoteDeletion || needsTokenDeletion
}

internal fun prepareNotificationCleanup(
    existing: PendingNotificationCleanup,
    currentUserId: String?,
    currentInstallationId: String?
): PendingNotificationCleanup {
    val installationId = existing.installationId ?: currentInstallationId
    val userId = existing.userId ?: currentUserId
    return PendingNotificationCleanup(
        userId = userId,
        installationId = installationId,
        needsRemoteDeletion = existing.needsRemoteDeletion || (!userId.isNullOrBlank() && !installationId.isNullOrBlank()),
        needsTokenDeletion = true
    )
}

internal fun applyNotificationCleanupResults(
    cleanup: PendingNotificationCleanup,
    remoteDeletionSucceeded: Boolean?,
    tokenDeletionSucceeded: Boolean?
): PendingNotificationCleanup = cleanup.copy(
    needsRemoteDeletion = if (cleanup.needsRemoteDeletion) remoteDeletionSucceeded != true else false,
    needsTokenDeletion = if (cleanup.needsTokenDeletion) tokenDeletionSucceeded != true else false
)
