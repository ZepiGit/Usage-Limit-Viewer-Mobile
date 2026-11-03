package com.usagelimits.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.math.BigDecimal

/**
 * The placed widgets under the German locale (R4).
 *
 * [PercentModeWidgetRenderingTest] and [CreditsWidgetRenderingTest] pin the English words; this
 * pins that every widget reads them from resources, so a German phone sees the glossary — Übrig,
 * Verbraucht, übrig, verbraucht — and the percent sign set off by a space. Same fixture as the
 * English suite: a 5h window with 40 % used and a Weekly window with 63 % used, so the two modes
 * never print the same digits.
 *
 * Legacy graphics for the reason the English suite gives: this reads text, never pixels.
 */
@OptIn(ExperimentalGlanceApi::class, ExperimentalCoroutinesApi::class, ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36], qualifiers = "de-mdpi")
class GermanWidgetRenderTest {
    private var nextWidgetId = 700

    @Before fun initializeApplication() {
        runBlocking { ApplicationProvider.getApplicationContext<UsageLimitsApp>().startupJob?.join() }
    }

    private val app get() = ApplicationProvider.getApplicationContext<UsageLimitsApp>()

    private suspend fun seed(vararg windows: UsageWindow): String {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, "german-fixture",
            "fixture@example.invalid", "Fixture", "Pro", emptyMap())
        repository.saveSnapshot(UsageSnapshot(account.localId, System.currentTimeMillis(),
            com.usagelimits.core.model.SnapshotStatus.OK, windows = windows.toList()))
        return account.localId
    }

    private suspend fun seedFixture() = seed(
        UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 40.0, 18_000, System.currentTimeMillis() + 3_600_000, false),
        UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 63.0, 604_800, System.currentTimeMillis() + 86_400_000, false),
    )

    private suspend fun inspect(widgetName: String, accountId: String, size: DpSize, check: (View) -> Unit) {
        val placed = WidgetUpdater.allWidgets.first { it.name == widgetName }
        val config = WidgetConfigEntity(nextWidgetId++, "CUSTOM", null, null, System.currentTimeMillis(),
            customAccountIdsJson = "[\"$accountId\"]")
        app.container.widgetConfigDao.upsert(config)
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, placed.receiver)
        })
        placed.widget().runComposition(app,
            id = GlanceAppWidgetManager(app).getGlanceIdBy(config.appWidgetId), sizes = listOf(size)).first { remote ->
            val host = AppWidgetHostView(app).apply { setAppWidget(config.appWidgetId, null) }
            val view = remote.apply(app, host)
            host.addView(view)
            remote.reapply(app, view)
            ShadowLooper.idleMainLooper()
            view.measure(View.MeasureSpec.makeMeasureSpec(size.width.value.toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.height.value.toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, size.width.value.toInt(), size.height.value.toInt())
            check(view)
            true
        }
    }

    private suspend fun TestScope.inEachMode(body: suspend (PercentDisplay) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in PercentDisplay.entries) {
                app.container.settingsStore.setPercentDisplay(mode)
                body(mode)
            }
        } finally {
            app.container.settingsStore.setPercentDisplay(PercentDisplay.LEFT)
            Dispatchers.resetMain()
        }
    }

    private fun modeWord(mode: PercentDisplay) = if (mode == PercentDisplay.USED) "Verbraucht" else "Übrig"
    private fun figures(mode: PercentDisplay) = if (mode == PercentDisplay.USED) listOf("40 %", "63 %") else listOf("60 %", "37 %")

    @Test fun `compact tiles lead with the German mode word, the app's window label and a spaced figure`() = runTest {
        val id = seedFixture()
        inEachMode { mode ->
            inspect("CompactUsageWidget", id, DpSize(420.dp, 130.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val word = modeWord(mode)
                assertTrue("$mode: 5h tile label, got $text", "$word · 5‑Std.-Limit" in text)
                assertTrue("$mode: weekly tile label, got $text", "$word · Wöchentlich" in text)
                figures(mode).forEach { assertTrue("$mode: figure $it present, got $text", it in text) }
                assertTrue("$mode: the next reset tile is German, got $text", "Nächster Reset" in text)
                assertTrue("$mode: the status tile says Konto, got $text", "Konto" in text)
                assertTrue("$mode: no English word is left on the tiles, got $text",
                    text.none { it.startsWith("Left") || it.startsWith("Used") || it == "Next reset" || it == "Account" })
            }
        }
    }

    @Test fun `detailed rows say the side in German after a spaced figure`() = runTest {
        val id = seedFixture()
        inEachMode { mode ->
            inspect("DetailedUsageWidget", id, DpSize(300.dp, 260.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val side = if (mode == PercentDisplay.USED) "verbraucht" else "übrig"
                figures(mode).forEach { assertTrue("$mode: '$it $side' present, got $text", "$it $side" in text) }
                assertTrue("$mode: no English side word is left, got $text",
                    text.none { it.endsWith(" left") || it.endsWith(" used") })
            }
        }
    }

    @Test fun `account rings caption the mode in German and describe each ring in German`() = runTest {
        val id = seedFixture()
        inEachMode { mode ->
            inspect("MinimalUsageWidget", id, DpSize(180.dp, 240.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                val centre = figures(mode).last()
                assertTrue("$mode: centre figure $centre present, got $text", centre in text)
                val spoken = "$centre ${if (mode == PercentDisplay.USED) "verbraucht" else "übrig"}"
                assertTrue("$mode: the ring says '$spoken', got $descriptions", descriptions.any { spoken in it })
                assertTrue("$mode: the status line names the window in German, got $text",
                    text.any { it.startsWith("Wöchentlich · ") })
            }
        }
    }

    @Test fun `an exhausted ring without a percentage says its quota is unknown in German`() = runTest {
        val id = seed(UsageWindow("blocked", "Weekly", WindowCategory.WEEKLY, null, 604_800,
            System.currentTimeMillis() + 86_400_000, true))
        inEachMode { mode ->
            inspect("MinimalUsageWidget", id, DpSize(180.dp, 240.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                assertTrue("$mode: the status line is German, got $text", "Kontingent unbekannt" in text)
                if (mode == PercentDisplay.USED) assertTrue("USED: the centre reads 100 %, got $text", "100 %" in text)
            }
        }
    }

    @Test fun `the credits widget is German, including a key that spent this month`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val empty = CreditsWidget().compose(app, size = DpSize(250.dp, 110.dp)).apply(app, FrameLayout(app))
            assertTrue(textViews(empty).map { it.text.toString() }.contains("Keine Guthaben. Füge eins in der App hinzu."))

            val now = System.currentTimeMillis()
            for (index in 0 until 4) {
                val id = "credit-fal-$index"
                app.container.creditRepository.upsert(
                    CreditAccount(id, CreditProviderId.FAL, "Account $index", "credit:$id", createdAt = now),
                    CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(index * 3))), fetchedAt = now),
                )
            }
            val view = CreditsWidget().compose(app, size = DpSize(250.dp, 110.dp)).apply(app, FrameLayout(app))
            val text = textViews(view).map { it.text.toString() }
            assertTrue("header is German, got $text", "Guthaben" in text)
            assertTrue("overflow is German, got $text", text.any { it.matches(Regex("\\+\\d+ weitere")) })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `the picker labels resolve to German`() {
        val packageManager = app.packageManager
        fun label(receiver: Class<*>) = packageManager.getReceiverInfo(ComponentName(app, receiver), 0).loadLabel(packageManager).toString()
        assertEquals("Nutzungsübersicht", label(CompactUsageWidgetReceiver::class.java))
        assertEquals("Verbrauchsbalken", label(DetailedUsageWidgetReceiver::class.java))
        assertEquals("Konto-Ringe", label(MinimalUsageWidgetReceiver::class.java))
        assertEquals("Mini-Ringe", label(MiniRingsWidgetReceiver::class.java))
        assertEquals("Guthaben", label(CreditsWidgetReceiver::class.java))
    }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun descriptions(view: View): List<String> = buildList {
        view.contentDescription?.let { add(it.toString()) }
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descriptions(view.getChildAt(index)))
    }
}
