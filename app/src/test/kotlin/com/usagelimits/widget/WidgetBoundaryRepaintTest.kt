package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.ui.claudeCodeStillBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.SyncWorker
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
 *
 * The projection runs for a configuration id with no widget bound to it, so the repaint has no
 * placed widget to update: the test starts no Glance session worker and sends no update
 * broadcast, and has none to end. The app's own sync cannot interfere either: start-up
 * scheduling has finished and its work is cancelled before seeding, and WorkManager is the test
 * manager, which runs nothing whose network constraint or delay the test does not release (as
 * in `SyncWorkerSchedulingTest`). Room, DataStore and [WidgetUpdater.refreshAll] stay real.
 */
@OptIn(ExperimentalGlanceApi::class, ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WidgetBoundaryRepaintTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After fun drain() {
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
        } finally {
            resetTestWorkManager()
        }
    }

    private suspend fun assertStill(account: WidgetAccount, expected: ClaudeCodeMood) {
        // No saved override: the catalog default is the qualifying Claude Code Color artwork.
        val view = GlanceRemoteViews().compose(app, DpSize(40.dp, 40.dp)) {
            WidgetLogo(account.copy(iconChoiceId = null), 26.dp)
        }.remoteViews.apply(app, FrameLayout(app))
        view.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 40, 40)
        fun images(node: View): List<ImageView> = buildList {
            if (node is ImageView && node.drawable is BitmapDrawable) add(node)
            if (node is ViewGroup) for (index in 0 until node.childCount) addAll(images(node.getChildAt(index)))
        }
        val bitmap = (images(view).single().drawable as BitmapDrawable).bitmap
        val reference = claudeCodeStillBitmap(expected, 0xFFD97757.toInt(), 26, 34)
        try { assertTrue("boundary projection draws $expected through the real default widget consumer", reference.sameAs(bitmap)) }
        finally { reference.recycle() }
    }

    @Test fun `a running All accounts session reranks when the repaint follows a reset`() = runBlocking {
        val fetchedAt = 1_800_000_000_000L
        val resetAt = fetchedAt + 600_000
        val repository = app.container.repository
        // Stored order alpha, bravo. Alpha's tight window resets at resetAt; bravo has 40 left.
        val alpha = repository.upsertFromLogin(ProviderId.CLAUDE, "alpha", null, "Alpha", null, emptyMap())
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
        // The configuration's id, not a bound launcher widget: with nothing placed, refreshAll
        // finds no widget to update, so neither a Glance session worker nor an update broadcast
        // exists in this test. It still writes the repaint time the projection observes.
        val glanceId = checkNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.appWidgetId)))

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
            assertStill(before.snapshot.accounts.first(), ClaudeCodeMood.TIRED)

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
            assertStill(after.snapshot.accounts.single { it.accountId == alpha.localId }, ClaudeCodeMood.IDLE)
            assertTrue("no sync ran: the repaint alone moved the session",
                WorkManager.getInstance(app).getWorkInfosByTag(SyncWorker::class.java.name).get()
                    .none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.SUCCEEDED || it.runAttemptCount > 0 })
        } finally {
            session.cancelAndJoin()
        }
    }
}
