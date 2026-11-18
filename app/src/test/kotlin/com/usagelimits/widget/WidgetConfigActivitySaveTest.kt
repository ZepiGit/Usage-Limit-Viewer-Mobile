package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
