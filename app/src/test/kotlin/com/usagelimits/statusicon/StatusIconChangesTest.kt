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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Changes to the status icons, made while an earlier change is still being published, through the
 * one seam every caller uses ([StatusIconChanges]) and the real status icon publisher.
 *
 * Everything that could suspend runs on one test scheduler: the settings are an in-memory value
 * written at once, the publisher reads that same value, and the publication is held at a gate
 * before its status step. After each change the scheduler runs until nothing more can: a write
 * that is not held back by the sequence has then certainly landed, one that is has certainly not.
 * Only then is the gate opened. No timeout decides anything; one may only fail a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusIconChangesTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val now = System.currentTimeMillis()

    private fun usage(id: String) = AccountUsage(
        ProviderAccount(id, ProviderId.CLAUDE, "ext-$id", null, id, null, "cred-$id", 0L, null),
        UsageSnapshot(id, now, SnapshotStatus.OK, listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
            usedPercent = 58.0, periodSeconds = 18_000, resetAt = now + 3_600_000, exhausted = false))),
    )

    private var accounts = listOf(usage("A"), usage("B"), usage("C"), usage("D"))
    private var settings = AppSettings()

    /** Completed when a held publication reaches its gate; the next one is held while [holdNext]. */
    private var held = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    private var holdNext = false

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    private fun showing(): Set<String> = manager.activeNotifications
        .filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }
        .map { checkNotNull(shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId")) }
        .toSet()

    private fun TestScope.changes(): StatusIconChanges {
        var clock = now
        val publisher = StatusIconPublisher(
            app, SettingsStore(app),
            readAccounts = { accounts },
            readSettings = { settings },
            now = { clock },
            nextTick = { delay(1); clock += 1 },
            shown = object : ShownSelection {
                private var ids = emptyList<String>()
                override fun read() = ids
                override fun write(accountIds: List<String>) { ids = accountIds }
            },
        )
        return StatusIconChanges(
            readPinned = { settings.pinnedAccountIds },
            writePinned = { settings = settings.copy(pinnedAccountIds = it) },
            writeEnabled = { settings = settings.copy(statusIconsEnabled = it) },
            usageAccountIds = { accounts.map { it.account.localId } },
            publish = {
                // The repaint and boundary steps that come before the status step, held here.
                if (holdNext) {
                    holdNext = false
                    held.complete(Unit)
                    gate.await()
                }
                publisher.publish(restoreDismissed = false)
            },
        )
    }

    /** Swipe stand-in: cancels an account's records in the notification manager (active list). */
    private fun dismiss(name: String) {
        manager.activeNotifications.filter { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") == name }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Makes [first] with its publication held, then [then] while it is held, drives the scheduler
     * until nothing more can run, and only then lets the held publication go.
     */
    private fun TestScope.whileHeld(first: suspend () -> Unit, vararg then: suspend () -> Unit) {
        holdNext = true
        launch { first() }
        advanceUntilIdle()
        assertTrue("the first change's publication is held", held.isCompleted && !gate.isCompleted)
        for (change in then) launch { change() }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `switching off and quickly on again shows a dismissed icon again`() = runTest(timeout = 30.seconds) {
        val changes = changes()
        changes.setPinned("A", true)
        changes.setPinned("B", true)
        changes.setEnabled(true)
        assertEquals(setOf("A", "B"), showing())
        dismiss("A")

        whileHeld({ changes.setEnabled(false) }, { changes.setEnabled(true) })

        assertTrue(settings.statusIconsEnabled)
        assertEquals("the dismissed icon comes back", setOf("A", "B"), showing())
    }

    @Test fun `unpinning and quickly pinning a dismissed account again shows it again`() = runTest(timeout = 30.seconds) {
        val changes = changes()
        changes.setEnabled(true)
        changes.setPinned("A", true)
        changes.setPinned("B", true)
        assertEquals(setOf("A", "B"), showing())
        dismiss("A")

        whileHeld({ changes.setPinned("A", false) }, { changes.setPinned("A", true) })

        assertEquals(listOf("B", "A"), settings.pinnedAccountIds)
        assertEquals("the dismissed icon comes back", setOf("A", "B"), showing())
    }

    @Test fun `changes made while one is published apply to the pins stored when their turn comes`() = runTest(timeout = 30.seconds) {
        val changes = changes()
        changes.setEnabled(true)
        changes.setPinned("A", true)

        whileHeld({ changes.setPinned("B", true) }, { changes.setPinned("C", true) }, { changes.setPinned("A", false) })

        assertEquals("independent changes are all kept", listOf("B", "C"), settings.pinnedAccountIds)
        assertEquals(setOf("B", "C"), showing())
    }

    @Test fun `a fourth pin that arrives through the queue is refused, not stored`() = runTest(timeout = 30.seconds) {
        val changes = changes()
        changes.setEnabled(true)
        changes.setPinned("A", true)
        changes.setPinned("B", true)
        val results = mutableListOf<Boolean>()

        whileHeld({ results += changes.setPinned("C", true) }, { results += changes.setPinned("D", true) })

        assertEquals(listOf(true, false), results)
        assertEquals(listOf("A", "B", "C"), settings.pinnedAccountIds)
        assertFalse("D" in showing())
    }

    @Test fun `a pin for an account removed while it waited is refused, and a removed account's pin drops out`() = runTest(timeout = 30.seconds) {
        val changes = changes()
        changes.setEnabled(true)
        changes.setPinned("A", true)
        changes.setPinned("B", true)

        whileHeld(
            { changes.setPinned("C", true) },
            {
                // B and D are removed from the cache before the queued pin of D runs.
                accounts = accounts.filter { it.account.localId != "B" && it.account.localId != "D" }
                changes.release("B")
            },
            { changes.setPinned("D", true) },
        )

        assertEquals(listOf("A", "C"), settings.pinnedAccountIds)
        assertEquals(setOf("A", "C"), showing())
    }
}
