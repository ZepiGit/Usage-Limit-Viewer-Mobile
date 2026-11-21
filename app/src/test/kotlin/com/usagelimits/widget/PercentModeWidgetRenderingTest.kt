package com.usagelimits.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Every widget, host-applied, in both percent modes (R1, R2).
 *
 * Summary and Bars visibly name the side; rings name it only in their spoken description.
 *
 * Fixture: one Claude account, a 5h window with 40% used (60% left) and a Weekly window with 63%
 * used (37% left), so the two modes never produce the same digits and a swapped label is caught.
 *
 * Bar widgets use legacy graphics because the native runtime cannot inflate Glance's ProgressBar
 * (a PathInterpolator error); their hosted progress values still expose the fill. Ring fill tests
 * use native graphics to inspect the arc bitmap alongside its spoken description.
 */
@OptIn(ExperimentalGlanceApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36, 37], qualifiers = "mdpi")
class PercentModeWidgetRenderingTest {
    private var nextWidgetId = 300

    @Before fun initializeApplication() {
        runBlocking { ApplicationProvider.getApplicationContext<UsageLimitsApp>().startupJob?.join() }
    }

    private val app get() = ApplicationProvider.getApplicationContext<UsageLimitsApp>()

    @org.junit.After fun drain() {
        try {
            androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
        } finally {
            com.usagelimits.core.sync.resetTestWorkManager()
        }
    }

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
    private suspend fun inspect(
        widgetName: String,
        accountIds: List<String>,
        size: DpSize,
        check: (View) -> Unit,
    ) {
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
     * Runs [body] once per mode with the setting persisted before composing, then restores the
     * previous app mode so a failing assertion cannot leak a change into a neighbouring test.
     */
    private suspend fun TestScope.inEachMode(body: suspend (PercentDisplay) -> Unit) {
        val originalMode = app.container.settingsStore.settings.first().percentDisplay
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in PercentDisplay.entries) {
                app.container.settingsStore.setPercentDisplay(mode)
                body(mode)
            }
        } finally {
            app.container.settingsStore.setPercentDisplay(originalMode)
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
                    val fills = progressBars(view).map { it.progress.toFloat() / it.max }
                    val expected = if (mode == PercentDisplay.USED) listOf(0.40f, 0.63f) else listOf(0.60f, 0.37f)
                    assertEquals("$mode: both Summary bars are present", 2, fills.size)
                    expected.zip(fills).forEach { (wanted, actual) ->
                        assertEquals("$mode: Summary fill follows the app mode", wanted, actual, 0.001f)
                    }
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

    @Test fun `a detailed widget follows the app setting in every row figure and side word`() = runTest {
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
                assertEquals("single-account Bars artwork is 26dp", listOf(26), claudeArtworkWidths(view))
                val fills = progressBars(view).map { it.progress.toFloat() / it.max }
                val expected = if (mode == PercentDisplay.USED) listOf(0.40f, 0.63f) else listOf(0.60f, 0.37f)
                assertEquals("$mode: both Usage Bars are present", 2, fills.size)
                expected.zip(fills).forEach { (wanted, actual) ->
                    assertEquals("$mode: Usage Bars fill follows the app mode", wanted, actual, 0.001f)
                }
            }
        }
    }

    // endregion

    // region Account Rings

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `account rings omit the caption and put the matching figure in the centre`() = runTest {
        val id = seedFixtureAccount()
        inEachMode { mode ->
            inspect("MinimalUsageWidget", listOf(id), DpSize(180.dp, 240.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: rings have no display-mode caption, got $text", "Left" !in text && "Used" !in text)
                assertEquals("Account Rings artwork is 18dp", listOf(18), claudeArtworkWidths(view))
                // The most critical window is Weekly, so its figure is the representative one.
                val centre = figures(mode).last()
                assertTrue("$mode: centre figure $centre present, got $text", centre in text)
                assertTrue("$mode: the other mode's ${otherFigures(mode).last()} must be absent, got $text",
                    otherFigures(mode).last() !in text)
                val spoken = if (mode == PercentDisplay.USED) "% used" else "% remaining"
                val notSpoken = if (mode == PercentDisplay.USED) "% remaining" else "% used"
                assertTrue("$mode: ring description says '$spoken', got $descriptions", descriptions.any { spoken in it })
                assertTrue("$mode: no ring description says '$notSpoken', got $descriptions", descriptions.none { notSpoken in it })
                val ring = ringImages(view).single()
                val speech = ring.contentDescription.toString()
                assertTrue("$mode: speech identifies the headline and its reset, got $speech",
                    "Weekly" in speech && "Resets " in speech)
                assertRingFill(view, mode)
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
                assertTrue("$mode: rings have no display-mode caption, got $text", "Left" !in text && "Used" !in text)
                if (mode == PercentDisplay.USED) {
                    // An exhausted 100 is written without its sign so it fits (#38).
                    assertTrue("USED: the centre reads a bare 100, got $text", "100" in text && "100%" !in text)
                    assertTrue("USED: the ring says 100% used, got $descriptions", descriptions.any { "100% used" in it })
                    assertTrue("USED: the status still says the quota is unknown, got $text", "Quota unknown" in text)
                } else {
                    assertTrue("LEFT: no 100% appears for an unknown remainder, got $text", text.none { it.contains("100%") })
                    assertTrue("LEFT: no figure at all is drawn in the centre, got $text", text.none { it.endsWith("%") })
                }
            }
        }
    }

    @Test fun `account rings write no full allowance and an exhausted 100 without its sign`() = runTest {
        // Owner's decision (#38): in LEFT a 100 is the full allowance, which the closed ring says
        // by itself; in USED it is the exhausted window, written as a bare "100" so it fits. The
        // rule follows the written figure, so it holds for aged readings too, whose arc is still
        // drawn (grey); every other figure, including 0 and 99, keeps its sign.
        val now = System.currentTimeMillis()
        val future = now + 86_400_000
        fun weekly(used: Double?, exhausted: Boolean = false, resetAt: Long = future) =
            UsageWindow("week", "Weekly", WindowCategory.WEEKLY, used, 604_800, resetAt, exhausted)
        val repository = app.container.repository
        suspend fun seed(name: String, window: UsageWindow, fetchedAt: Long = now): String {
            val account = repository.upsertFromLogin(ProviderId.CLAUDE, "centre-$name",
                "$name@example.invalid", name, "Pro", emptyMap())
            repository.saveSnapshot(UsageSnapshot(account.localId, fetchedAt, SnapshotStatus.OK, windows = listOf(window)))
            return account.localId
        }
        val ids = listOf(
            seed("Full", weekly(0.0)),
            seed("Spent", weekly(100.0, exhausted = true)),
            seed("Almost", weekly(1.0)),
            seed("Most", weekly(99.0)),
            seed("Unknown", weekly(null)),
            // Two days old: past any stale threshold the sync interval can set.
            seed("Old", weekly(0.0), fetchedAt = now - 2 * 86_400_000L),
            // Fetched before a reset that has since passed: the spent window's reading.
            seed("Due", weekly(100.0, exhausted = true, resetAt = now - 60_000), fetchedAt = now - 120_000),
        )
        val expected = mapOf(
            PercentDisplay.LEFT to mapOf("Full" to null, "Spent" to "0%", "Almost" to "99%", "Most" to "1%",
                "Unknown" to null, "Old" to null, "Due" to "0%"),
            PercentDisplay.USED to mapOf("Full" to "0%", "Spent" to "100", "Almost" to "1%", "Most" to "99%",
                "Unknown" to null, "Old" to "0%", "Due" to "100"),
        )
        val spokenFull = mapOf(PercentDisplay.LEFT to listOf("Full", "Old"), PercentDisplay.USED to listOf("Spent", "Due"))
        inEachMode { mode ->
            inspect("MinimalUsageWidget", ids, DpSize(180.dp, 900.dp)) { view ->
                for ((name, figure) in expected.getValue(mode)) {
                    val cell = accountCell(view, name)
                    assertEquals("$mode $name: the figure written in the centre", listOfNotNull(figure), centreFigures(cell))
                }
                val clause = if (mode == PercentDisplay.USED) "100% used" else "100% remaining"
                for (name in spokenFull.getValue(mode)) {
                    val descriptions = descriptions(accountCell(view, name))
                    assertTrue("$mode $name: the ring still says '$clause', got $descriptions", descriptions.any { clause in it })
                }
            }
        }
    }

    @Test fun `account rings keep both labels in two columns with a very long label`() = runTest {
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
                assertTrue("$mode: rings have no display-mode caption, got $text", "Left" !in text && "Used" !in text)
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

    @Test fun `account rings retain the status line specific to each account`() = runTest {
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
                assertTrue("$mode: rings have no display-mode caption, got $text", "Left" !in text && "Used" !in text)
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

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `mini rings omit the caption and word the ring descriptions to match`() = runTest {
        val id = seedFixtureAccount()
        inEachMode { mode ->
            inspect("MiniRingsWidget", listOf(id), DpSize(62.dp, 180.dp)) { view ->
                val text = textViews(view).map { it.text.toString() }
                val descriptions = descriptions(view)
                assertTrue("$mode: rings have no display-mode caption, got $text", "Left" !in text && "Used" !in text)
                val spoken = if (mode == PercentDisplay.USED) "63% used" else "37% remaining"
                val notSpoken = if (mode == PercentDisplay.USED) "% remaining" else "% used"
                assertTrue("$mode: ring description says '$spoken', got $descriptions", descriptions.any { spoken in it })
                assertTrue("$mode: no ring description says '$notSpoken', got $descriptions", descriptions.none { notSpoken in it })
                assertTrue("$mode: the 1x1 rings carry a mark, not a figure, got $text", text.none { it.endsWith("%") })
                val ring = ringImages(view).single()
                val markWidth = claudeArtworkWidths(view).single()
                assertTrue("Claude occupies 57.5% of its ring: $markWidth in ${ring.width}",
                    kotlin.math.abs(markWidth - 0.575f * ring.width) <= 1f)
                val speech = ring.contentDescription.toString()
                assertTrue("$mode: speech identifies the headline and its reset, got $speech",
                    "Weekly" in speech && "Resets " in speech)
                assertRingFill(view, mode)
            }
        }
    }

    @Test fun `a 56dp mini host fits two rings again at both font scales`() = runTest {
        val ids = (1..4).map { seedNamedAccount("mini-$it", "Mini $it", fixtureWindows()) }
        val size = DpSize(56.dp, 56.dp)
        for (scale in listOf(1f, 1.5f)) atFontScale(scale) {
            assertEquals(scale, app.resources.configuration.fontScale, 0.001f)
            inEachMode { mode ->
                inspect("MiniRingsWidget", ids, size) { view ->
                    val rings = ringImages(view)
                    assertEquals("$mode at ${scale}x: two rings fit one launcher cell", 2, rings.size)
                    for (ring in rings) {
                        assertTrue("$mode at ${scale}x: ${ring.contentDescription} fits inside the host",
                            ring.height > 0 && bottomWithin(view, ring) <= view.height)
                    }
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

    private fun progressBars(view: View): List<ProgressBar> = buildList {
        if (view is ProgressBar) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(progressBars(view.getChildAt(index)))
    }

    private fun assertRingFill(view: View, mode: PercentDisplay) {
        val bitmap = (ringImages(view).single().drawable as BitmapDrawable).bitmap
        val style = WidgetStyle()
        // The representative Weekly window is 37% left / 63% used. Six o'clock is outside
        // the former arc and inside the latter. Find the stroke in the hosted bitmap so the
        // sample stays inside it when the ring size or stroke width changes.
        val centreX = bitmap.width / 2
        var innerStrokeY = bitmap.height / 2
        while (innerStrokeY < bitmap.height && Color.alpha(bitmap.getPixel(centreX, innerStrokeY)) == 0) innerStrokeY++
        assertTrue("$mode: the ring has a stroke at six o'clock", innerStrokeY < bitmap.height)
        var outerStrokeY = bitmap.height - 1
        while (Color.alpha(bitmap.getPixel(centreX, outerStrokeY)) == 0) outerStrokeY--
        assertEquals("$mode: the ring fill follows the app mode",
            if (mode == PercentDisplay.USED) style.data.toArgb() else style.trackArgb,
            bitmap.getPixel(centreX, (innerStrokeY + outerStrokeY) / 2))
    }

    private fun claudeArtworkWidths(view: View): List<Int> = buildList {
        if (view is ImageView && view.drawable?.let { shadowOf(it).createdFromResId } ==
            com.usagelimits.R.drawable.icon_claudecode_color) add(view.width)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(claudeArtworkWidths(view.getChildAt(index)))
    }

    /** Ring images describe the reading; their decorative provider marks have no description. */
    private fun ringImages(view: View): List<ImageView> = buildList {
        if (view is ImageView && !view.contentDescription.isNullOrBlank()) add(view)
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

    /**
     * The cell of the account labelled [label]: the nearest container around that label which also
     * holds a ring, so its texts are this account's alone.
     */
    private fun accountCell(root: View, label: String): View {
        var cell: View = textViews(root).singleOrNull { it.text.toString() == label }
            ?: error("one '$label' label, got ${textViews(root).map { it.text }}")
        while (ringImages(cell).isEmpty()) {
            cell = cell.parent as? View ?: error("no ring around '$label'")
        }
        return cell
    }

    /** What a cell writes as a figure: a percentage, a bare number or the unknown dash. */
    private fun centreFigures(cell: View): List<String> =
        textViews(cell).map { it.text.toString() }.filter { it.endsWith("%") || (it.isNotEmpty() && it.all(Char::isDigit)) || it == "—" }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun descriptions(view: View): List<String> = buildList {
        view.contentDescription?.let { add(it.toString()) }
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descriptions(view.getChildAt(index)))
    }
}
