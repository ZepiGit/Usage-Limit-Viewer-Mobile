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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-widget percent mode survives the trip from the config screen into the widget's row (R5).
 *
 * The screen tests pin what is handed to `onSave`; this pins that the Activity writes it, since a
 * dropped argument there would leave every screen test green and every placed widget on the app mode.
 */
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

    private fun storedMode(widgetId: Int): String? = runBlocking { dao.get(widgetId)?.percentDisplay }

    private fun openConfigFor(widgetId: Int, percentDisplay: String?): ActivityScenario<WidgetConfigActivity> {
        runBlocking { dao.upsert(WidgetConfigEntity(widgetId, "CLOSEST_RESETS", null, null, 1L, percentDisplay = percentDisplay)) }
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
    fun `saving writes the chosen mode into the widget row`() {
        openConfigFor(41, percentDisplay = null).use {
            choose("Used")
            compose.onNodeWithText("Save widget").performClick()
            compose.waitUntil(10_000) { storedMode(41) == "USED" }
            assertEquals("USED", storedMode(41))
        }
    }

    @Test
    fun `choosing the app setting again clears a stored mode`() {
        openConfigFor(42, percentDisplay = "USED").use {
            choose("App setting")
            compose.onNodeWithText("Save widget").performClick()
            compose.waitUntil(10_000) { storedMode(42) == null }
            assertEquals(null, storedMode(42))
        }
    }
}
