package com.usagelimits.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Host-applied RemoteViews, including their persisted style. No launcher is simulated here. */
@OptIn(ExperimentalGlanceApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetAppearanceRenderingTest {
    @Before fun initializeApplication() {
        runBlocking { ApplicationProvider.getApplicationContext<UsageLimitsApp>().startupJob?.join() }
    }

    @Test fun `saved panel and text survive theme changes resizing and host reapply for every widget`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, "widget-appearance-fixture",
            "fixture@example.invalid", "Widget fixture", "Pro", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.PARTIAL, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 0.0, 18_000, now + 3_600_000, false),
            UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 63.0, 604_800, now + 86_400_000, false),
            UsageWindow("fable", "Weekly (Fable)", WindowCategory.WEEKLY, 100.0, 604_800,
                now + 86_400_000, true, scope = UsageScope.MODEL),
        )))
        val manager = AppWidgetManager.getInstance(app)
        val glanceManager = GlanceAppWidgetManager(app)
        val dao = app.container.widgetConfigDao
        val sizes = mapOf(
            "CompactUsageWidget" to listOf(DpSize(420.dp, 130.dp), DpSize(180.dp, 150.dp)),
            "DetailedUsageWidget" to listOf(DpSize(300.dp, 260.dp)),
            "MinimalUsageWidget" to listOf(DpSize(180.dp, 240.dp)),
            "MiniRingsWidget" to listOf(DpSize(62.dp, 180.dp)),
        )
        val styles = listOf(
            WidgetStyle(WidgetBackground(0xFF16213A.toInt(), 100)),
            WidgetStyle(WidgetBackground(0xFFFFFFFF.toInt(), 100)),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for ((widgetIndex, placed) in WidgetUpdater.allWidgets.withIndex()) {
                val configs = styles.mapIndexed { styleIndex, style ->
                    WidgetConfigEntity(100 + widgetIndex * 10 + styleIndex, "CUSTOM", null, null, now,
                        customAccountIdsJson = "[\"${account.localId}\"]",
                        backgroundArgb = style.background.argb, backgroundOpacity = style.background.clampedOpacity,
                        textTone = style.textTone.name)
                }
                configs.forEach { config ->
                    shadowOf(manager).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
                        provider = ComponentName(app, placed.receiver)
                    })
                    dao.upsert(config)
                }
                for (night in listOf(false, true)) {
                    RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
                    for ((styleIndex, config) in configs.withIndex()) {
                        for ((sizeIndex, size) in sizes.getValue(placed.name).withIndex()) {
                            // Keep the composition alive until its collection views have been
                            // applied, laid out and inspected inside the first-emission callback.
                            placed.widget().runComposition(app,
                                id = glanceManager.getGlanceIdBy(config.appWidgetId), sizes = listOf(size)).first { remote ->
                                // Android refuses collection adapters unless rootParent is an
                                // AppWidgetHostView. FrameLayout silently produced blank list widgets.
                                val host = AppWidgetHostView(app).apply { setAppWidget(config.appWidgetId, null) }
                                val view = remote.apply(app, host)
                                host.addView(view)
                                remote.reapply(app, view)
                                ShadowLooper.idleMainLooper()
                                val bitmap = draw(view, size.width.value.toInt(), size.height.value.toInt())
                                val name = "${placed.name}-${styleIndex}-${if (night) "night" else "day"}-$sizeIndex"
                                export(bitmap, name)
                                assertEquals("$name preserves the selected background",
                                    config.backgroundArgb, bitmap.getPixel(bitmap.width / 2, 2))
                                if (placed.name == "CompactUsageWidget") {
                                    val owner = textViews(view).firstOrNull { it.text.toString() == account.label }
                                    assertNotNull("$name identifies whose quota is displayed", owner)
                                    assertEquals("$name keeps readable saved ink", styles[styleIndex].textPrimary.toArgb(), owner!!.currentTextColor)
                                    assertTrue("$name displays the partial-sync warning",
                                        textViews(view).any { it.text.toString().contains("Sync issue") })
                                }
                                if (placed.name == "MiniRingsWidget") {
                                    assertTrue("$name keeps a visible partial-update badge",
                                        textViews(view).any { it.text.toString() == "!" })
                                }
                                if (placed.name == "DetailedUsageWidget" || placed.name == "MinimalUsageWidget") {
                                    val collections = adapterViews(view)
                                    assertEquals("$name has one account collection", 1, collections.size)
                                    assertEquals("$name binds the selected account to its adapter", 1, collections.single().adapter?.count)
                                    val text = textViews(view).map { it.text.toString() }
                                    assertTrue("$name materializes the account row, not only the panel", account.label in text)
                                    if (placed.name == "DetailedUsageWidget") {
                                        assertTrue("$name retains the scoped limit", "Weekly (Fable)" in text)
                                        assertTrue("$name renders the global remaining quota", "37%" in text)
                                        assertTrue("$name renders the independent fetch warning", "Some limits could not be updated" in text)
                                    } else {
                                        assertTrue("$name renders the fetch warning", "Partial update" in text)
                                    }
                                }
                                assertEquals(config, dao.get(config.appWidgetId))
                                true
                            }
                        }
                    }
                }
                // Composing either instance must not rewrite its neighbour's appearance or order.
                configs.forEach { assertEquals(it, dao.get(it.appWidgetId)) }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("+notnight")
            Dispatchers.resetMain()
        }
    }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun adapterViews(view: View): List<AdapterView<*>> = buildList {
        if (view is AdapterView<*>) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(adapterViews(view.getChildAt(index)))
    }

    private fun draw(view: View, width: Int, height: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun export(bitmap: Bitmap, name: String) {
        val directory = File("build/reports/widget-renderings").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
