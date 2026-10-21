package com.usagelimits.feature

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewOrderingTest {

    private val now = 1_789_000_000_000L

    @Test
    fun `exhausted outranks stale and error, which come last`() {
        val order = Severity.entries.sortedBy { it.urgency }
        assertEquals(Severity.EXHAUSTED, order.first())
        assertTrue(
            "stale and error must rank after every live severity",
            order.indexOf(Severity.STALE) > order.indexOf(Severity.HEALTHY) &&
                order.indexOf(Severity.ERROR) > order.indexOf(Severity.HEALTHY),
        )
        // The property the bug violated, stated directly.
        assertTrue(Severity.EXHAUSTED.urgency < Severity.STALE.urgency)
        assertTrue(Severity.EXHAUSTED.urgency < Severity.ERROR.urgency)
    }

    private fun account(id: String) = ProviderAccount(
        localId = id,
        provider = ProviderId.CODEX,
        externalAccountId = "ext-$id",
        email = null,
        displayName = id,
        plan = null,
        credentialReference = "codex_$id",
        createdAt = now,
        lastSuccessfulSync = null,
    )

    private fun window(id: String, resetAt: Long?) = UsageWindow(
        id = id, label = id, category = WindowCategory.FIVE_HOUR,
        usedPercent = 10.0, periodSeconds = 18_000, resetAt = resetAt, exhausted = false,
    )

    private fun usage(id: String, vararg resets: Long?) = AccountUsage(
        account(id),
        UsageSnapshot(
            accountId = id, fetchedAt = now, status = SnapshotStatus.OK,
            windows = resets.mapIndexed { i, r -> window("$id-$i", r) },
        ),
    )

    /**
     * The order the user dragged the cards into beats the urgency ranking — but only once they
     * have actually dragged one.
     *
     * Two orders live on this screen, and getting the precedence wrong is invisible in either
     * direction: re-ranking a hand-made arrangement on the next sync looks like the drag was
     * forgotten, and honouring an all-zero `sortOrder` on a fresh install looks like the
     * urgency ranking was never built.
     */
    @Test
    fun `overview uses the same stored order before and after manual arrangement`() {
        // Deliberately handed over in the WRONG urgency order, as the repository would after a
        // reorder: it returns rows by sortOrder.
        val healthy = usageWith("healthy", 10.0)
        val exhausted = usageWith("exhausted", 100.0)
        val accounts = listOf(healthy, exhausted)

        val ranked = UsageUiState(accounts = accounts, settings = AppSettings())
        assertEquals(
            "the overview and All accounts widgets share the stored order",
            listOf("healthy", "exhausted"),
            ranked.orderedAccounts(now).map { it.account.localId },
        )

        val arranged = UsageUiState(
            accounts = accounts,
            settings = AppSettings(accountsManuallyOrdered = true),
        )
        assertEquals(
            "once arranged by hand, the stored order stands and nothing re-ranks it",
            listOf("healthy", "exhausted"),
            arranged.orderedAccounts(now).map { it.account.localId },
        )
    }

    @Test
    fun `a never-successful failure cannot advance screen freshness`() {
        val failed = usage("A").let {
            it.copy(snapshot = it.snapshot!!.copy(status = SnapshotStatus.FAILED))
        }
        assertNull(UsageUiState(accounts = listOf(failed)).lastUpdated)
    }

    private fun usageWith(id: String, usedPercent: Double) = AccountUsage(
        account(id),
        UsageSnapshot(
            accountId = id, fetchedAt = now, status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "$id-w", label = "5h limit", category = WindowCategory.FIVE_HOUR,
                    usedPercent = usedPercent, periodSeconds = 18_000,
                    resetAt = now + 3_600_000, exhausted = usedPercent >= 100.0,
                ),
            ),
        ),
    )
}
