package com.usagelimits.core.model

/** Dated evidence from the same account instance, never an amount increase or clock tick. */
fun UsageSnapshot.confirmedResetWindowIds(previous: UsageSnapshot?): List<String> {
    if (previous == null || previous.accountId != accountId || status == SnapshotStatus.FAILED) return emptyList()
    return windows.mapNotNull { window ->
        if (!window.measured || window.remainingPercent == null) return@mapNotNull null
        val old = previous.windows.firstOrNull { it.id == window.id } ?: return@mapNotNull null
        if (!old.measured || old.remainingPercent == null) return@mapNotNull null
        val oldReset = old.resetAt ?: return@mapNotNull null
        val nextReset = window.resetAt ?: return@mapNotNull null
        window.id.takeIf {
            previous.fetchedAt < oldReset && fetchedAt >= oldReset &&
                nextReset > oldReset && nextReset > fetchedAt
        }
    }
}
