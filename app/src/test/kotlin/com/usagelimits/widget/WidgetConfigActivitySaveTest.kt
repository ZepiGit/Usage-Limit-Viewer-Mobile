package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.settings.FigureFont
import com.usagelimits.core.settings.TextFont
import java.io.File
import org.junit.Assert.assertTrue
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.PercentDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.usagelimits.feature.waitDrained
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saving a widget keeps its appearance and selection without changing the app's display mode. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class WidgetConfigActivitySaveTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
    private val dao get() = app.container.widgetConfigDao

    @Before fun initializeApplication() {
        runBlocking { app.startupJob?.join() }
    }

    @org.junit.After fun drain() {
        try {
            androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            com.usagelimits.core.sync.resetTestWorkManager()
        }
    }

    private fun storedConfig(widgetId: Int): WidgetConfigEntity? = runBlocking { dao.get(widgetId) }

    private fun openConfigFor(config: WidgetConfigEntity): ActivityScenario<WidgetConfigActivity> {
        runBlocking { dao.upsert(config) }
        val widgetId = config.appWidgetId
        val scenario = ActivityScenario.launch<WidgetConfigActivity>(
            Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save widget").fetchSemanticsNodes().isNotEmpty() }
        return scenario
    }

    private fun choose(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `configuration keeps its warm preview when saved app theme and fonts change`() {
        val settings = app.container.settingsStore
        val original = runBlocking { settings.settings.first() }
        val now = System.currentTimeMillis()
        val account = runBlocking {
            app.container.repository.upsertFromLogin(ProviderId.CODEX, "warm-preview",
                "preview@example.invalid", "Preview fixture", "Plus", emptyMap()).also {
                app.container.repository.saveSnapshot(UsageSnapshot(it.localId, now, SnapshotStatus.OK,
                    windows = listOf(UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 40.0, 604_800, null, false))))
            }
        }
        val config = WidgetConfigEntity(43, "CUSTOM", null, null, now,
            customAccountIdsJson = "[\"${account.localId}\"]")
        org.robolectric.Shadows.shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(config.appWidgetId,
            android.appwidget.AppWidgetProviderInfo().apply {
                provider = android.content.ComponentName(app, MinimalUsageWidgetReceiver::class.java)
            })
        fun capture(scenario: ActivityScenario<WidgetConfigActivity>, name: String): Bitmap {
            compose.waitForIdle()
            var bitmap: Bitmap? = null
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
            return bitmap!!.also { image ->
                System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let { path ->
                    val directory = File(path).apply { mkdirs() }
                    File(directory, "widget-config-$name.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        try {
            runBlocking {
                settings.setAppTheme(AppTheme.WARM_DARK)
                settings.setTextFont(TextFont.ROBOTO)
                settings.setFigureFont(FigureFont.ROBOTO_MONO)
                settings.setPercentDisplay(PercentDisplay.LEFT)
            }
            openConfigFor(config).use { scenario ->
                compose.waitDrained {
                    compose.onAllNodesWithContentDescription("60% remaining", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("60%").assertExists()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Left or used follows Settings › Display, like the Overview."))
                capture(scenario, "B3-text-controls").recycle()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("60%"))
                val baseline = capture(scenario, "B3-warm-baseline")
                runBlocking {
                    settings.setTextFont(TextFont.FIGTREE)
                    settings.setFigureFont(FigureFont.IBM_PLEX_MONO)
                }
                try {
                    assertEquals("configuration chrome keeps the pre-theme warm ground",
                        0xFF0F0F0E.toInt(), baseline.getPixel(2, baseline.height / 2))
                    val pixels = IntArray(baseline.width * baseline.height)
                    baseline.getPixels(pixels, 0, baseline.width, 0, 0, baseline.width, baseline.height)
                    for ((role, argb) in listOf(
                        "warm ground and default widget panel" to 0xFF0F0F0E.toInt(),
                        "warm card" to 0xFF1A1918.toInt(),
                        "warm ink" to 0xFFF0EEE6.toInt(),
                        "preview mat" to 0xFF3A3936.toInt(),
                        "widget data arc" to 0xFFD97757.toInt(),
                    )) {
                        assertTrue("the pre-theme configuration keeps its known $role", pixels.any { it == argb })
                    }
                    for (theme in AppTheme.entries) {
                        runBlocking { settings.setAppTheme(theme) }
                        assertEquals(theme, runBlocking { settings.settings.first().appTheme })
                        runBlocking { settings.setPercentDisplay(PercentDisplay.USED) }
                        compose.waitDrained {
                            compose.onAllNodesWithContentDescription("40% used", substring = true).fetchSemanticsNodes().isNotEmpty()
                        }
                        runBlocking { settings.setPercentDisplay(PercentDisplay.LEFT) }
                        compose.waitDrained {
                            compose.onAllNodesWithContentDescription("60% remaining", substring = true).fetchSemanticsNodes().isNotEmpty()
                        }
                        val changed = capture(scenario, "B3-${theme.name.lowercase()}-figtree-plex")
                        try {
                            assertTrue("$theme must not restyle the configuration or its preview", baseline.sameAs(changed))
                        } finally {
                            changed.recycle()
                        }
                    }
                } finally {
                    baseline.recycle()
                }
            }
        } finally {
            runBlocking {
                settings.setAppTheme(original.appTheme)
                settings.setTextFont(original.textFont)
                settings.setFigureFont(original.figureFont)
                settings.setPercentDisplay(original.percentDisplay)
            }
        }
    }

    @Test
    fun `saving preserves the widget appearance while the app displays used`() {
        val originalMode = runBlocking { app.container.settingsStore.settings.first().percentDisplay }
        runBlocking { app.container.settingsStore.setPercentDisplay(PercentDisplay.USED) }
        val config = WidgetConfigEntity(41, "CLOSEST_RESETS", null, null, 1L,
            transparent = true, layoutMetricsJson = WidgetMetrics().toJson(),
            backgroundArgb = 0xFF16213A.toInt(), backgroundOpacity = 0, textTone = "LIGHT")
        try {
            openConfigFor(config).use {
                compose.onNodeWithText("Save widget").performClick()
                compose.waitDrained { (storedConfig(41)?.updatedAt ?: 0L) > config.updatedAt }
                val stored = storedConfig(41)!!
                assertEquals(config.copy(updatedAt = stored.updatedAt), stored)
                assertEquals(PercentDisplay.USED, runBlocking { app.container.settingsStore.settings.first().percentDisplay })
            }
        } finally {
            runBlocking { app.container.settingsStore.setPercentDisplay(originalMode) }
        }
    }

    @Test
    fun `saving a different account selection leaves the app display mode alone`() {
        val originalMode = runBlocking { app.container.settingsStore.settings.first().percentDisplay }
        runBlocking { app.container.settingsStore.setPercentDisplay(PercentDisplay.LEFT) }
        try {
            openConfigFor(WidgetConfigEntity(42, "CLOSEST_RESETS", null, null, 1L)).use {
                choose("All accounts")
                compose.onNodeWithText("Save widget").performClick()
                compose.waitDrained { storedConfig(42)?.scope == "ALL_ACCOUNTS" }
                assertEquals("ALL_ACCOUNTS", storedConfig(42)!!.scope)
                assertEquals(PercentDisplay.LEFT, runBlocking { app.container.settingsStore.settings.first().percentDisplay })
            }
        } finally {
            runBlocking { app.container.settingsStore.setPercentDisplay(originalMode) }
        }
    }
}
