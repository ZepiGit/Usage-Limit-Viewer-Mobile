package com.usagelimits.statusicon

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.seconds

/**
 * What the publisher does when the wall clock misbehaves while it orders the icons: it posts every
 * pinned account anyway, never with an invented time, and never waits on the clock past a small
 * limit measured on a clock that does not jump. The clocks are scripted through the publisher's
 * `now`, `nextTick` and `monotonic` seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusIconClockTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val base = System.currentTimeMillis()

    private fun usage(id: String) = AccountUsage(
        ProviderAccount(id, ProviderId.CLAUDE, "ext-$id", null, id, null, "cred-$id", 0L, null),
        UsageSnapshot(id, base, SnapshotStatus.OK, listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
            usedPercent = 58.0, periodSeconds = 18_000, resetAt = base + 3_600_000, exhausted = false))),
    )

    private val accounts = listOf(usage("A"), usage("B"), usage("C"))
    private val settings = AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("A", "B", "C"))

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    private fun whens(): Map<String, Long> = manager.activeNotifications
        .filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }
        .groupBy { checkNotNull(shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId")) }
        .mapValues { (_, r) -> r.first().notification.`when` }

    /** Publishes with a wall clock that returns [readings] in turn, then repeats the last one. */
    private fun TestScope.publishWith(readings: List<Long>): List<Long> {
        var index = 0
        return publishWith(now = { readings[minOf(index++, readings.lastIndex)] })
    }

    /** Publishes with the wall clock [now]; [onTick] runs each time the publisher waits for it. */
    private fun TestScope.publishWith(now: () -> Long, onTick: () -> Unit = {}): List<Long> {
        val handedOut = mutableListOf<Long>()
        var monotonic = 0L
        val publisher = StatusIconPublisher(
            app, SettingsStore(app),
            readAccounts = { accounts },
            readSettings = { settings },
            now = { now().also { handedOut += it } },
            nextTick = { delay(1); monotonic += 1; onTick() },
            monotonic = { monotonic },
            shown = object : ShownSelection {
                override fun read() = emptyList<String>()
                override fun write(accountIds: List<String>) = Unit
            },
        )
        val job = launch { publisher.publish(restoreDismissed = true) }
        advanceUntilIdle()
        assertTrue("the publication ran to its end", job.isCompleted)
        // One bounded wait at most: once the guarantee is given up, the rest are posted at once.
        assertTrue("waited $monotonic ms on the monotonic clock", monotonic <= StatusIconPublisher.CLOCK_WAIT_LIMIT_MS + 1)
        return handedOut
    }

    @Test fun `a clock that does not move posts every account after a bounded wait`() = runTest(timeout = 30.seconds) {
        val handedOut = publishWith(listOf(base))

        val posted = whens()
        assertEquals("every pinned account is posted", setOf("A", "B", "C"), posted.keys)
        assertTrue("only real readings are used: $posted", posted.values.all { it in handedOut })
    }

    @Test fun `a clock that jumps backwards posts every account with real readings, none in the future`() = runTest(timeout = 30.seconds) {
        // The clock reads base until the publisher waits for it to move on (its first tick),
        // and from then on reads a second earlier, going on from there.
        var jumped = false
        var after = base - 1_000
        val handedOut = publishWith(now = { if (jumped) after++ else base }, onTick = { jumped = true })

        val posted = whens()
        assertEquals(setOf("A", "B", "C"), posted.keys)
        assertTrue("only real readings are used: $posted", posted.values.all { it in handedOut })
        // Every account is built after the jump; a when of base would lie a second in its future.
        assertTrue("no when is later than the clock that follows the jump: $posted", posted.values.all { it < base })
    }

    @Test fun `a normal clock still orders the accounts`() = runTest(timeout = 30.seconds) {
        publishWith((0..20L).map { base + it })

        val posted = whens()
        assertTrue("A leads, then B, then C: $posted", posted.getValue("A") > posted.getValue("B") && posted.getValue("B") > posted.getValue("C"))
    }
}
