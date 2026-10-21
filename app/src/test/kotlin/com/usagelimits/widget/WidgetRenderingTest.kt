package com.usagelimits.widget

import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.runComposition
import androidx.glance.layout.fillMaxWidth
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalGlanceRemoteViewsApi::class, ExperimentalGlanceApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class WidgetRenderingTest {
    @Before fun initializeApplication() {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        runBlocking { app.startupJob?.join() }
    }

    @Test fun `summary composes in a real Glance session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
            val views = CompactUsageWidget().compose(app, size = DpSize(320.dp, 120.dp))
            views.apply(app, FrameLayout(app))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `provider summary without a connected account explains how to add one`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val config = WidgetConfigEntity(201, "PROVIDER", null, "claude", System.currentTimeMillis())
        app.container.widgetConfigDao.upsert(config)
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, CompactUsageWidgetReceiver::class.java)
        })
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val remote = CompactUsageWidget().runComposition(app,
                id = GlanceAppWidgetManager(app).getGlanceIdBy(config.appWidgetId),
                sizes = listOf(DpSize(320.dp, 120.dp))).first()
            val view = remote.apply(app, FrameLayout(app))
            val messages = arrayListOf<View>()
            view.findViewsWithText(messages, "No Claude account connected. Add one in the app.", View.FIND_VIEWS_WITH_TEXT)
            assertTrue("the provider's missing sign-in is explained", messages.isNotEmpty())
            val stale = arrayListOf<View>()
            view.findViewsWithText(stale, "Stale", View.FIND_VIEWS_WITH_TEXT)
            assertTrue("no account is not stale usage", stale.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `usage bars can be applied and reapplied by the host`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context as UsageLimitsApp).startupJob?.join()
        val composer = GlanceRemoteViews()
        val host = FrameLayout(context)
        for (remaining in listOf(75.0, 25.0, 0.0, 100.0)) {
            val result = composer.compose(context, DpSize(300.dp, 60.dp)) {
                UsageBar(WidgetRow("5h limit", WindowCategory.FIVE_HOUR, remaining, null,
                    Severity.HEALTHY), GlanceModifier.fillMaxWidth())
            }
            val view = result.remoteViews.apply(context, host)
            result.remoteViews.reapply(context, view)
        }
    }

    @Test fun `detailed widget settles after rendering cached accounts`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CODEX, "fixture", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 25.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val firstRender = CompletableDeferred<Unit>()
        var renders = 0
        val session = launch {
            DetailedUsageWidget().runComposition(app, sizes = listOf(DpSize(320.dp, 300.dp)))
                .collect { views ->
                    views.apply(app, FrameLayout(app))
                    renders++
                    firstRender.complete(Unit)
                }
        }
        try {
            withContext(Dispatchers.IO) { withTimeout(10_000) { firstRender.await() } }
            withContext(Dispatchers.IO) { delay(600) }
            val settledRenders = renders
            withContext(Dispatchers.IO) { delay(600) }
            assertEquals("no new data must not keep rebuilding the host views", settledRenders, renders)
        } finally {
            session.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
