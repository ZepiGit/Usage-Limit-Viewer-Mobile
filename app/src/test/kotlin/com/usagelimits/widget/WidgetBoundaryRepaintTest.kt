package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A widget session that is already running takes a presentation boundary from the repaint.
 *
 * Glance does not restart `provideGlance` on `updateAll`, so a running session sees only what
 * its observed flow emits. A reset that falls due changes no cached row; the boundary worker's
 * repaint ([WidgetUpdater.refreshAll]) is what has to reach the flow, which then rebuilds the
 * projection on the clock it is given. The clock here is a variable: no wall time decides the
 * outcome, and the timeout only fails a hung collector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class WidgetBoundaryRepaintTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
    }

    @After fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    @Test fun `a running All accounts session reranks when the repaint follows a reset`() = runBlocking {
        val fetchedAt = 1_800_000_000_000L
        val resetAt = fetchedAt + 600_000
        val repository = app.container.repository
        // Stored order alpha, bravo. Alpha's tight window resets at resetAt; bravo has 40 left.
        val alpha = repository.upsertFromLogin(ProviderId.CODEX, "alpha", null, "Alpha", null, emptyMap())
        val bravo = repository.upsertFromLogin(ProviderId.CLAUDE, "bravo", null, "Bravo", null, emptyMap())
        repository.saveSnapshot(UsageSnapshot(alpha.localId, fetchedAt, SnapshotStatus.OK, listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 95.0, 18_000, resetAt, exhausted = false),
            UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY, 30.0, 604_800, resetAt + 86_400_000, exhausted = false),
        )))
        repository.saveSnapshot(UsageSnapshot(bravo.localId, fetchedAt, SnapshotStatus.OK, listOf(
            UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY, 60.0, 604_800, resetAt + 86_400_000, exhausted = false),
        )))
        val config = WidgetConfigEntity(301, "ALL_ACCOUNTS", null, null, fetchedAt)
        app.container.widgetConfigDao.upsert(config)
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, DetailedUsageWidgetReceiver::class.java)
        })
        val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(config.appWidgetId)

        var clock = resetAt - 1
        val views = Channel<WidgetUpdater.WidgetView>(Channel.UNLIMITED)
        val session = launch(start = CoroutineStart.UNDISPATCHED) {
            WidgetUpdater.observe(app, glanceId, now = { clock }).collect { views.send(it) }
        }
        try {
            val before = withTimeout(10_000) { views.receive() }
            assertEquals("alpha's 5% leads before its reset", listOf(alpha.localId, bravo.localId),
                before.snapshot.accounts.map { it.accountId })
            assertFalse(before.snapshot.accounts.first().rows.single { it.windowId == "5h" }.resetElapsed)

            // The reset falls due; the cache does not move. The boundary worker's repaint follows.
            clock = resetAt
            WidgetUpdater.refreshAll(app, app.container.settingsStore)

            // Only a missing emission ends in the timeout: it fails the test, it never passes it.
            val after = withTimeoutOrNull(10_000) { views.receive() }
            assertNotNull("the running session saw no repaint, so it kept its old rank", after)
            after!!
            assertEquals("bravo's 40% leads once alpha ranks by its open weekly 70%",
                listOf(bravo.localId, alpha.localId), after.snapshot.accounts.map { it.accountId })
            // The same rebuild ages the reset-due mark, which had the same gap before #88.
            assertTrue(after.snapshot.accounts.single { it.accountId == alpha.localId }
                .rows.single { it.windowId == "5h" }.resetElapsed)
        } finally {
            session.cancelAndJoin()
        }
    }
}
