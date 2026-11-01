package com.usagelimits.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Every widget, host-applied, in both percent modes (R1, R2).
 *
 * The figure on a widget is only half of what it says: "60%" means nothing until the widget also
 * says which side it counts. These pin the pair — the number AND the word that disambiguates it
 * (a tile label, a suffix, or a caption) — for each widget type, plus the accessibility text
 * that carries the same meaning for a screen reader.
 *
 * Fixture: one Claude account, a 5h window with 40% used (60% left) and a Weekly window with 63%
 * used (37% left), so the two modes never produce the same digits and a swapped label is caught.
 *
 * Legacy graphics rather than the native mode WidgetAppearanceRenderingTest requests: this suite
 * reads text and descriptions, never pixels, and the native runtime in this environment cannot
 * inflate Glance's ProgressBar (a PathInterpolator error), which every bar-carrying widget needs.
 */
@OptIn(ExperimentalGlanceApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36], qualifiers = "mdpi")
class PercentModeWidgetRenderingTest {
    private var nextWidgetId = 300

    @Before fun initializeApplication() {
        runBlocking { ApplicationProvider.getApplicationContext<UsageLimitsApp>().startupJob?.join() }
    }

    private val app get() = ApplicationProvider.getApplicationContext<UsageLimitsApp>()

    private suspend fun seedFixtureAccount() = seedAccount(
        UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 40.0, 18_000, System.currentTimeMillis() + 3_600_000, false),
        UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 63.0, 604_800, System.currentTimeMillis() + 86_400_000, false),
    )

    private suspend fun seedAccount(vararg windows: UsageWindow): String =
        seedNamedAccount("percent-mode-fixture", "Mode fixture", windows.toList())

    /** A distinct account per [key], so one test can place several; [name] is what the rows call it. */
    private suspend fun seedNamedAccount(
        key: String,
        name: String,
        windows: List<UsageWindow>,
        status: SnapshotStatus = SnapshotStatus.OK,
        connection: ConnectionStatus? = null,
    ): String {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, key,
            "fixture@example.invalid", name, "Pro", emptyMap())
        val snapshot = UsageSnapshot(account.localId, System.currentTimeMillis(), status, windows = windows)
        repository.saveSnapshot(connection?.let { snapshot.copy(connectionStatus = it) } ?: snapshot)
        return account.localId
    }

    private fun fixtureWindows() = listOf(
        UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 40.0, 18_000, System.currentTimeMillis() + 3_600_000, false),
        UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 63.0, 604_800, System.currentTimeMillis() + 86_400_000, false),
    )

    /** Composes [widgetName] for the accounts in [accountIds] at [size] and hands the laid-out view to [check]. */
    private suspend fun inspect(widgetName: String, accountIds: List<String>, size: DpSize, check: (View) -> Unit) {
        val placed = WidgetUpdater.allWidgets.first { it.name == widgetName }
        val config = WidgetConfigEntity(nextWidgetId++, "CUSTOM", null, null, System.currentTimeMillis(),
            customAccountIdsJson = accountIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
        app.container.widgetConfigDao.upsert(config)
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, placed.receiver)
        })
        placed.widget().runComposition(app,
            id = GlanceAppWidgetManager(app).getGlanceIdBy(config.appWidgetId), sizes = listOf(size)).first { remote ->
            // Collection widgets only bind their rows once hosted and laid out; inspect inside the
            // first-emission callback so the composition is still alive.
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

    /**
     * Runs [body] once per mode with the setting persisted before composing, and always puts the
     * app back on LEFT so a failing assertion cannot leak USED into a neighbouring test.
     */
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

    private fun modeWord(mode: PercentDisplay) = if (mode == PercentDisplay.USED) "Used" else "Left"
    private fun otherWord(mode: PercentDisplay) = if (mode == PercentDisplay.USED) "Left" else "Used"

    /** The 5h and Weekly figures the fixture shows in [mode]. */
    private fun figures(mode: PercentDisplay) = if (mode == PercentDisplay.USED) listOf("40%", "63%") else listOf("60%", "37%")
    private fun otherFigures(mode: PercentDisplay) = figures(if (mode == PercentDisplay.USED) PercentDisplay.LEFT else PercentDisplay.USED)

    // region Compact

    @Test fun `compact four tiles name the mode in each label and show the matching figures`() = runTest {
        val id = seedFixtureAccount()
        // 372dp is the narrowest width that still selects four tiles, so the labels are tightest there.
        val sizes = listOf(DpSize(420.dp, 130.dp), DpSize(372.dp, 130.dp))
        for (size in sizes) {
            assertEquals("${size.width} is wide enough for four tiles",
                WidgetLayout.CompactLayout.FOUR_TILES, WidgetLayout.compactLayout(size.width.value, 1f))
        }
        inEachMode { mode ->
            for (size in sizes) {
                inspect("CompactUsageWidget", listOf(id), size) { view ->
                    val text = textViews(view).map { it.text.toString() }
                    val word = modeWord(mode)
                    assertTrue("$mode ${size.width}: 5h tile label leads with the mode word, got $text", "$word · 5h limit" in text)
                    assertTrue("$mode ${size.width}: weekly tile label leads with the mode word, got $text", "$word · Weekly" in text)
                    figures(mode).forEach { assertTrue("$mode ${size.width}: tile figure $it present, got $text", it in text) }
                    otherFigures(mode).forEach {
                        assertTrue("$mode ${size.width}: the other mode's $it must be absent, got $text", it !in text)
                    }
                    assertTrue("$mode ${size.width}: no label carries the other mode's word, got $text",
                        text.none { it.startsWith("${otherWord(mode)} · ") })
                }
            }
        }
    }

    @Test fun `compact summary and focus layouts lead their label with the mode word`() = runTest {
        val id = seedFixtureAccount()
        // 212dp is the narrowest width that still selects the summary, so its labels are tightest there.
        val cases = listOf(
            Triple("summary 300dp", DpSize(300.dp, 130.dp), WidgetLayout.CompactLayout.SUMMARY),
            Triple("summary 212dp", DpSize(212.dp, 130.dp), WidgetLayout.CompactLayout.SUMMARY),
            Triple("focus 180dp", DpSize(180.dp, 150.dp), WidgetLayout.CompactLayout.FOCUS),
        )
        for ((name, size, layout) in cases) {
            assertEquals("$name selects $layout", layout, WidgetLayout.compactLayout(size.width.value, 1f))
        }
        inEachMode { mode ->
            for ((name, size, _) in cases) {
                inspect("CompactUsageWidget", listOf(id), size) { view ->
                    val text = textViews(view).map { it.text.toString() }
                    val word = modeWord(mode)
                    assertTrue("$mode $name: label leads with the mode word, got $text", "$word · 5h limit" in text)
                    val headline = figures(mode).first()
                    assertTrue("$mode $name: headline figure $headline present, got $text", headline in text)
                    assertTrue("$mode $name: the other mode's ${otherFigures(mode).first()} must be absent, got $text",
                        otherFigures(mode).first() !in text)
                    assertTrue("$mode $name: no label carries the other mode's word, got $text",
                        text.none { it.startsWith("${otherWord(mode)} · ") })
                }
            }
        }
    }

    // endregion

    // region Detailed

    @Test fun `detailed rows carry the side next to each figure`() = runTest {
        val id = seedFixtureAccount()
        inEachMode { mode ->
            inspect("DetailedUsageWidget", listOf(id), DpSize(300.dp, 260.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val suffix = if (mode == PercentDisplay.USED) "used" else "left"
                figures(mode).forEach {
                    assertTrue("$mode: row figure '$it $suffix' present, got $text", "$it $suffix" in text)
                }
                otherFigures(mode).forEach {
                    assertTrue("$mode: the other mode's '$it' must be absent, got $text", text.none { t -> t.startsWith(it) })
                }
                assertTrue("$mode: no bare figure is left to be misread, got $text",
                    text.none { it.endsWith("%") })
            }
        }
    }

    // endregion

    // region Account Rings

    @Test fun `account rings caption the mode and put the matching figure in the centre`() = runTest {
        val id = seedFixtureAccount()
        inEachMode { mode ->
            inspect("MinimalUsageWidget", listOf(id), DpSize(180.dp, 240.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                assertTrue("$mode: no caption of the other mode, got $text", otherWord(mode) !in text)
                // The most critical window is Weekly, so its figure is the representative one.
                val centre = figures(mode).last()
                assertTrue("$mode: centre figure $centre present, got $text", centre in text)
                assertTrue("$mode: the other mode's ${otherFigures(mode).last()} must be absent, got $text",
                    otherFigures(mode).last() !in text)
                val spoken = if (mode == PercentDisplay.USED) "% used" else "% remaining"
                val notSpoken = if (mode == PercentDisplay.USED) "% remaining" else "% used"
                assertTrue("$mode: ring description says '$spoken', got $descriptions", descriptions.any { spoken in it })
                assertTrue("$mode: no ring description says '$notSpoken', got $descriptions", descriptions.none { notSpoken in it })
            }
        }
    }

    @Test fun `an exhausted ring without a percentage reads one hundred used and no figure in left`() = runTest {
        // PLAN 1.6: the provider's verdict is the figure in USED, so the centre must not stay
        // behind the unknown-dash gate; LEFT has nothing to say and keeps the dash gate.
        val id = seedAccount(UsageWindow("blocked", "Weekly", WindowCategory.WEEKLY, null, 604_800,
            System.currentTimeMillis() + 86_400_000, true))
        inEachMode { mode ->
            inspect("MinimalUsageWidget", listOf(id), DpSize(180.dp, 240.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                if (mode == PercentDisplay.USED) {
                    assertTrue("USED: the centre reads 100%, got $text", "100%" in text)
                    assertTrue("USED: the ring says 100% used, got $descriptions", descriptions.any { "100% used" in it })
                    assertTrue("USED: the status still says the quota is unknown, got $text", "Quota unknown" in text)
                } else {
                    assertTrue("LEFT: no 100% appears for an unknown remainder, got $text", text.none { it.contains("100%") })
                    assertTrue("LEFT: no figure at all is drawn in the centre, got $text", text.none { it.endsWith("%") })
                }
            }
        }
    }

    @Test fun `account rings keep both labels and the caption in two columns with a very long label`() = runTest {
        val size = DpSize(420.dp, 300.dp)
        assertEquals("the fixture size must be a two-column layout",
            2, WidgetLayout.accountColumns(size.width.value, size.height.value, null, 1f))
        val longLabel = "long-account-".padEnd(60, 'x')
        val ids = listOf(
            seedNamedAccount("wide-long", longLabel, fixtureWindows()),
            seedNamedAccount("wide-short", "Short", fixtureWindows()),
        )
        inEachMode { mode ->
            inspect("MinimalUsageWidget", ids, size) { view ->
                val text = textViews(view).map { it.text.toString() }
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                // Ellipsising is the layout's business; the text it was given must be whole.
                assertTrue("$mode: the 60-character label is rendered, got $text", longLabel in text)
                assertTrue("$mode: the short label is rendered, got $text", "Short" in text)
                val long = textViews(view).first { it.text.toString() == longLabel }
                val short = textViews(view).first { it.text.toString() == "Short" }
                assertEquals("$mode: both accounts share one row of two columns",
                    bottomWithin(view, long), bottomWithin(view, short))
            }
        }
    }

    @Test fun `account rings caption the mode whatever status line each account shows`() = runTest {
        val future = System.currentTimeMillis() + 86_400_000
        fun weekly(used: Double?, exhausted: Boolean = false, resetAt: Long? = future) =
            UsageWindow("week", "Weekly", WindowCategory.WEEKLY, used, 604_800, resetAt, exhausted)
        val ids = listOf(
            seedNamedAccount("branch-reconnect", "Reconnect account", listOf(weekly(40.0)),
                connection = ConnectionStatus.RECONNECT_REQUIRED),
            seedNamedAccount("branch-out", "Out account", listOf(weekly(100.0, exhausted = true)), SnapshotStatus.PARTIAL),
            seedNamedAccount("branch-partial", "Partial account", listOf(weekly(40.0)), SnapshotStatus.PARTIAL),
            seedNamedAccount("branch-limited", "Limited account", listOf(weekly(40.0),
                UsageWindow("opus", "Opus", WindowCategory.OTHER, 100.0, null, future, true, scope = UsageScope.MODEL))),
            seedNamedAccount("branch-unknown", "Unknown account", listOf(weekly(null, resetAt = null))),
            seedNamedAccount("branch-normal", "Normal account", listOf(weekly(40.0))),
            seedNamedAccount("branch-noreset", "No reset account", listOf(weekly(40.0, resetAt = null))),
        )
        inEachMode { mode ->
            inspect("MinimalUsageWidget", ids, DpSize(180.dp, 900.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                assertTrue("$mode: no caption of the other mode, got $text", otherWord(mode) !in text)
                for (status in listOf("Reconnect", "Out · Partial update", "Partial update",
                    "Limited: Opus", "Quota unknown", "No reset time")) {
                    assertEquals("$mode: exactly one account shows '$status', got $text", 1, text.count { it == status })
                }
                assertEquals("$mode: exactly one account shows its window and reset, got $text",
                    1, text.count { it.startsWith("Weekly · ") })
            }
        }
    }

    // endregion

    // region Mini Rings

    @Test fun `mini rings caption the mode and word the ring descriptions to match`() = runTest {
        val id = seedFixtureAccount()
        inEachMode { mode ->
            inspect("MiniRingsWidget", listOf(id), DpSize(62.dp, 180.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
                assertTrue("$mode: no caption of the other mode, got $text", otherWord(mode) !in text)
                val spoken = if (mode == PercentDisplay.USED) "63% used" else "37% remaining"
                val notSpoken = if (mode == PercentDisplay.USED) "% remaining" else "% used"
                assertTrue("$mode: ring description says '$spoken', got $descriptions", descriptions.any { spoken in it })
                assertTrue("$mode: no ring description says '$notSpoken', got $descriptions", descriptions.none { notSpoken in it })
                assertTrue("$mode: the 1x1 rings carry a mark, not a figure, got $text", text.none { it.endsWith("%") })
            }
        }
    }

    @Test fun `mini rings at the 56dp minimum keep the caption and show one ring inside the host`() = runTest {
        // PLAN 3: the deliberate 2-to-1 drop. The caption's line is paid for before rows are
        // counted, so a one-cell-tall host with more accounts than fit clips nothing.
        val ids = (1..4).map { seedNamedAccount("mini-$it", "Mini $it", fixtureWindows()) }
        val size = DpSize(62.dp, 56.dp)
        val grid = WidgetLayout.miniGrid(size.width.value, size.height.value)
        val expectedRings = WidgetLayout.miniCaptionedRows(grid, 4, size.height.value, 1f) * grid.columns
        assertEquals("PLAN 3: a 1x1 cell holds one ring once the caption takes its line", 1, expectedRings)
        inEachMode { mode ->
            inspect("MiniRingsWidget", ids, size) { view ->
                assertCaptionedRingsFit("$mode", view, mode, size, expectedRings = expectedRings)
            }
        }
    }

    @Test fun `mini rings at the 56dp minimum and a 1_3 font scale still caption and fit`() = runTest {
        val ids = (1..4).map { seedNamedAccount("mini-$it", "Mini $it", fixtureWindows()) }
        val size = DpSize(62.dp, 56.dp)
        atFontScale(1.3f) {
            assertEquals("the font scale reached the resources the widget reads",
                1.3f, app.resources.configuration.fontScale, 0.001f)
            inEachMode { mode ->
                inspect("MiniRingsWidget", ids, size) { view ->
                    assertCaptionedRingsFit("$mode at 1.3x", view, mode, size, expectedRings = null)
                }
            }
        }
    }

    @Test fun `an empty selection shows its message and no mode caption`() = runTest {
        // PLAN 3: the caption is a hint about the rings below it; with no rings it would be a
        // word floating over an explanation.
        seedFixtureAccount()
        inEachMode { mode ->
            for (name in listOf("MinimalUsageWidget", "MiniRingsWidget")) {
                inspect(name, emptyList(), DpSize(180.dp, 240.dp)) { view ->
                    val text = textViews(view).map { it.text.toString() }
                    assertTrue("$mode $name: the empty message is shown, got $text", "No matching accounts" in text)
                    assertTrue("$mode $name: no caption over an empty widget, got $text",
                        "Left" !in text && "Used" !in text)
                }
            }
        }
    }

    // endregion

    /** Runs [body] at [scale] and always puts the font scale back, so a failure cannot leak it onward. */
    private inline fun <T> atFontScale(scale: Float, body: () -> T): T {
        RuntimeEnvironment.setFontScale(scale)
        try {
            return body()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /**
     * The caption and at least one ring are there, the rings number [expectedRings] when given, and
     * none of them ends below the host. Positions are measured against [root], which [inspect] laid
     * out at exactly [size] (one pixel per dp under mdpi).
     */
    private fun assertCaptionedRingsFit(name: String, root: View, mode: PercentDisplay, size: DpSize,
        expectedRings: Int?) {
        val hostHeight = size.height.value.toInt()
        assertEquals("$name: the host is laid out at its full height", hostHeight, root.height)
        val text = textViews(root).map { it.text.toString() }
        assertTrue("$name: caption '${modeWord(mode)}' present, got $text", modeWord(mode) in text)
        val rings = ringImages(root)
        assertTrue("$name: at least one ring is shown, got ${descriptions(root)}", rings.isNotEmpty())
        if (expectedRings != null) {
            assertEquals("$name: ring count, got ${descriptions(root)}", expectedRings, rings.size)
        }
        for (part in rings + textViews(root).filter { it.text.toString() == modeWord(mode) }) {
            val bottom = bottomWithin(root, part)
            assertTrue("$name: ${part.javaClass.simpleName} '${part.contentDescription ?: (part as? TextView)?.text}' " +
                "ends at ${bottom}px, below the ${hostHeight}px host", bottom <= hostHeight)
        }
    }

    /** The rings: images whose description ends in a figure clause, which a provider logo's never does. */
    private fun ringImages(view: View): List<ImageView> = buildList {
        val description = view.contentDescription?.toString()
        if (view is ImageView && description != null && ("% remaining" in description || "% used" in description)) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(ringImages(view.getChildAt(index)))
    }

    /** Where [view] ends, in [root]'s coordinates: its own bottom plus the top of every container between. */
    private fun bottomWithin(root: View, view: View): Int {
        var bottom = view.bottom
        var parent = view.parent
        while (parent is View && parent !== root) {
            bottom += parent.top
            parent = parent.parent
        }
        return bottom
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
