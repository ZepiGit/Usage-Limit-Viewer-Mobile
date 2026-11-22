package com.usagelimits.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
import com.usagelimits.core.model.ClaudeCodeMood
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
import kotlin.math.roundToInt
import java.io.File

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
        provider: ProviderId = ProviderId.CLAUDE,
    ): String {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(provider, key,
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
        style: WidgetStyle = WidgetStyle(),
        check: (View) -> Unit,
    ) {
        val placed = WidgetUpdater.allWidgets.first { it.name == widgetName }
        val config = WidgetConfigEntity(nextWidgetId++, "CUSTOM", null, null, System.currentTimeMillis(),
            customAccountIdsJson = accountIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
            backgroundArgb = style.background.argb, backgroundOpacity = style.background.clampedOpacity,
            textTone = style.textTone.name)
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
            val density = app.resources.displayMetrics.density
            val width = (size.width.value * density).roundToInt()
            val height = (size.height.value * density).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            check(view)
            true
        }
    }

    /**
     * Runs [body] once per mode with the setting persisted before composing, then restores the
     * previous app mode so a failing assertion cannot leak a change into a neighbouring test.
     */
    private suspend fun TestScope.inEachMode(icon: String = "claude-color", body: suspend (PercentDisplay) -> Unit) {
        val settings = app.container.settingsStore
        val original = settings.settings.first()
        val provider = ProviderId.CLAUDE.id
        val originalIcon = original.providerIcons[provider] ?: "claudecode-color"
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // SDK sandboxes share DataStore with other test classes, including icon pickers.
            settings.setProviderIcon(provider, icon)
            for (mode in PercentDisplay.entries) {
                settings.setPercentDisplay(mode)
                body(mode)
            }
        } finally {
            try {
                settings.setPercentDisplay(original.percentDisplay)
            } finally {
                try {
                    settings.setProviderIcon(provider, originalIcon)
                } finally {
                    Dispatchers.resetMain()
                }
            }
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
                assertEquals("single-account Bars artwork is 26dp", listOf(26), claudeArtworkSlotSizes(view))
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
                assertEquals("Account Rings artwork is 18dp", listOf(18), claudeArtworkSlotSizes(view))
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

    @Test fun `both hosted ring kinds speak stale failed and reconnect readings without losing the window`() = runTest {
        val now = System.currentTimeMillis()
        val window = UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 63.0, 604_800,
            now + 86_400_000, false)
        val cases = listOf(
            Triple("Stale speech", SnapshotStatus.OK, "Data is out of date"),
            Triple("Failed speech", SnapshotStatus.FAILED, "Update failed · last known data"),
            Triple("Reconnect speech", SnapshotStatus.OK, "reconnect required"),
        ).map { (name, status, clause) ->
            val id = seedNamedAccount("speech-$name", name, listOf(window))
            app.container.repository.saveSnapshot(UsageSnapshot(id,
                if (name == "Stale speech") now - 2 * 86_400_000L else now,
                status, windows = listOf(window),
                connectionStatus = if (name == "Reconnect speech") ConnectionStatus.RECONNECT_REQUIRED else ConnectionStatus.CONNECTED))
            id to clause
        }
        inEachMode { mode ->
            for ((id, clause) in cases) for (kind in listOf("MinimalUsageWidget", "MiniRingsWidget")) {
                inspect(kind, listOf(id), DpSize(180.dp, 110.dp)) { root ->
                    val speech = ringImages(root).single().contentDescription.toString()
                    assertTrue("$kind $mode keeps '$clause': $speech", clause in speech)
                    assertTrue("$kind $mode names the actual window and reset: $speech", "Weekly, Resets " in speech)
                    val figure = if (mode == PercentDisplay.USED) "63% used" else "37% remaining"
                    assertTrue("$kind $mode retains the cached figure: $speech", figure in speech)
                    for ((_, other) in cases.filter { it.second != clause }) {
                        assertTrue("$kind does not confuse $clause with $other: $speech", other !in speech)
                    }
                }
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
                val markWidth = claudeArtworkSlotSizes(view).single()
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

    @Test fun `an empty ring selection shows the no matching accounts message`() = runTest {
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

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `placed Claude artwork follows each raw headline in both modes without Hooray`() = runTest {
        val now = System.currentTimeMillis()
        fun window(left: Double?, exhausted: Boolean = false, scope: UsageScope = UsageScope.GLOBAL) =
            UsageWindow("$scope-$left", "Weekly", WindowCategory.WEEKLY,
                left?.let { 100.0 - it }, 604_800, null, exhausted, scope = scope)
        val cases = listOf(
            "Asleep" to (listOf(window(0.0)) to ClaudeCodeMood.ASLEEP),
            "Tired" to (listOf(window(15.0)) to ClaudeCodeMood.TIRED),
            "Working" to (listOf(window(40.0)) to ClaudeCodeMood.WORKING),
            "Idle" to (listOf(window(70.0)) to ClaudeCodeMood.IDLE),
            "Dancing" to (listOf(window(95.0)) to ClaudeCodeMood.DANCING),
            "Declared" to (listOf(window(70.0, exhausted = true)) to ClaudeCodeMood.ASLEEP),
            "Unknown exhausted" to (listOf(window(null, exhausted = true)) to ClaudeCodeMood.ASLEEP),
            "Unknown" to (listOf(window(null)) to ClaudeCodeMood.IDLE),
            "Stale" to (listOf(window(15.0)) to ClaudeCodeMood.TIRED),
            "Expired" to (listOf(window(40.0)) to ClaudeCodeMood.WORKING),
            "Restricted" to (listOf(window(70.0), window(0.0, true, UsageScope.MODEL)) to ClaudeCodeMood.IDLE),
        )
        val ids = cases.map { (name, fixture) ->
            val id = seedNamedAccount("mood-$name", name, fixture.first)
            if (name == "Stale" || name == "Expired") app.container.repository.saveSnapshot(
                UsageSnapshot(id, if (name == "Stale") now - 2 * 86_400_000L else now - 2_000,
                    SnapshotStatus.OK, windows = fixture.first,
                    connectionStatus = if (name == "Expired") ConnectionStatus.RECONNECT_REQUIRED else ConnectionStatus.CONNECTED))
            id
        }
        val baseline = mutableMapOf<String, Bitmap>()
        try {
            inEachMode(icon = "claudecode-color") { mode ->
                inspect("MinimalUsageWidget", ids, DpSize(220.dp, 900.dp)) { root ->
                    for ((name, fixture) in cases) {
                        val image = providerImages(accountCell(root, name)).single()
                        try { assertStill(image, 18, fixture.second, 0xFFD97757.toInt(), WidgetStyle()) }
                        catch (failure: AssertionError) { throw AssertionError("$name: ${failure.message}; cell texts=${textViews(accountCell(root, name)).map { it.text }}", failure) }
                        val bitmap = (image.drawable as BitmapDrawable).bitmap
                        baseline[name]?.let { assertTrue("$name ignores Used", it.sameAs(bitmap)) }
                            ?: run { baseline[name] = bitmap.copy(Bitmap.Config.ARGB_8888, false) }
                    }
                    if (mode == PercentDisplay.LEFT) saveMoodCapture(root, "mood-accounts-color")
                }
            }
        } finally {
            baseline.values.forEach(Bitmap::recycle)
        }
    }

    @Config(qualifiers = "xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `all placed logo slots keep body size alpha ink and particles at actual density`() = runTest {
        val id = seedAccount(UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 5.0, 604_800, null, false))
        val settings = app.container.settingsStore
        val original = settings.settings.first()
        val styles = listOf(WidgetStyle(), WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt())),
            WidgetStyle(WidgetBackground(opacityPercent = 0)),
            WidgetStyle(WidgetBackground(opacityPercent = 0), WidgetTextTone.DARK))
        inEachMode {
            try {
                for (choice in listOf("claudecode-color", "claudecode")) {
                    settings.setProviderIcon(ProviderId.CLAUDE.id, choice)
                    settings.setAnimateClaudeCodeIcon(false)
                    settings.setFigureFont(com.usagelimits.core.settings.FigureFont.IBM_PLEX_MONO)
                    for ((styleIndex, style) in styles.withIndex()) {
                        settings.setAppTheme(com.usagelimits.core.settings.AppTheme.entries[styleIndex])
                        for ((widget, size) in listOf("DetailedUsageWidget" to DpSize(180.dp, 110.dp),
                            "MinimalUsageWidget" to DpSize(180.dp, 110.dp),
                            "MiniRingsWidget" to DpSize(56.dp, 56.dp))) {
                            inspect(widget, listOf(id), size, style) { root ->
                                val image = providerImages(root).single()
                                val density = app.resources.displayMetrics.density
                                val body = when (widget) {
                                    "DetailedUsageWidget" -> (26 * density).roundToInt()
                                    "MinimalUsageWidget" -> (18 * density).roundToInt()
                                    else -> (ringImages(root).single().width * 0.575f).roundToInt()
                                }
                                assertStill(image, body, ClaudeCodeMood.DANCING,
                                    if (choice == "claudecode") style.textPrimary.toArgb() else 0xFFD97757.toInt(), style)
                                val bounds = Rect(0, 0, image.width, image.height)
                                (root as ViewGroup).offsetDescendantRectToMyCoords(image, bounds)
                                assertTrue("$widget shows the full particle envelope: $bounds in ${root.width}x${root.height}",
                                    Rect(0, 0, root.width, root.height).contains(bounds))
                                // Each note extends past the nominal body box. The hosted image must retain both.
                                val frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                                try {
                                    image.draw(Canvas(frame))
                                    val inset = (image.width - body) / 2
                                    assertTrue("left note survives host scaling", (0 until inset).any { x ->
                                        (0 until frame.height).any { y -> Color.alpha(frame.getPixel(x, y)) > 0 } })
                                    assertTrue("right note survives host scaling", (inset + body until frame.width).any { x ->
                                        (0 until frame.height).any { y -> Color.alpha(frame.getPixel(x, y)) > 0 } })
                                } finally { frame.recycle() }
                                if (styleIndex == 2) saveMoodCapture(root, "mood-$widget-$choice-transparent")
                            }
                        }
                    }
                }
            } finally {
                try { settings.setAnimateClaudeCodeIcon(original.animateClaudeCodeIcon) }
                finally {
                    try { settings.setFigureFont(original.figureFont) }
                    finally {
                        try { settings.setAppTheme(original.appTheme) }
                        finally { settings.setProviderIcon(ProviderId.CLAUDE.id, original.providerIcons[ProviderId.CLAUDE.id] ?: "claudecode-color") }
                    }
                }
            }
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `new readings replace the placed still while other artwork stays static`() = runTest {
        val id = seedAccount(UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 85.0, 604_800, null, false))
        val codex = seedNamedAccount("mood-static-codex", "Codex", fixtureWindows(), provider = ProviderId.CODEX)
        inEachMode(icon = "claudecode-color") {
            inspect("DetailedUsageWidget", listOf(id), DpSize(180.dp, 110.dp)) { root ->
                assertStill(providerImages(root).single(), 26, ClaudeCodeMood.TIRED, 0xFFD97757.toInt(), WidgetStyle())
            }
            app.container.repository.saveSnapshot(UsageSnapshot(id, System.currentTimeMillis(), SnapshotStatus.OK,
                windows = listOf(UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 60.0, 604_800, null, false))))
            inspect("DetailedUsageWidget", listOf(id), DpSize(180.dp, 110.dp)) { root ->
                assertStill(providerImages(root).single(), 26, ClaudeCodeMood.WORKING, 0xFFD97757.toInt(), WidgetStyle())
            }
            val settings = app.container.settingsStore
            try {
                for (choice in com.usagelimits.ui.ProviderIconCatalog.choices(ProviderId.CLAUDE)
                    .filter { it.id !in setOf("claudecode-color", "claudecode") }) {
                    settings.setProviderIcon(ProviderId.CLAUDE.id, choice.id)
                    inspect("DetailedUsageWidget", listOf(id), DpSize(180.dp, 110.dp)) { root ->
                        val image = providerImages(root).single()
                        assertEquals(choice.id, choice.drawable, shadowOf(image.drawable).createdFromResId)
                        assertEquals("static slot", 26, image.width)
                    }
                }
            } finally { settings.setProviderIcon(ProviderId.CLAUDE.id, "claudecode-color") }
            val codexChoice = com.usagelimits.ui.ProviderIconCatalog.selected(ProviderId.CODEX,
                settings.settings.first().providerIcons[ProviderId.CODEX.id])
            inspect("DetailedUsageWidget", listOf(codex), DpSize(180.dp, 110.dp)) { root ->
                val image = providerImages(root).single()
                assertEquals("other providers keep catalog artwork", codexChoice.drawable, shadowOf(image.drawable).createdFromResId)
                assertEquals("other providers keep their slot", 26, image.width)
            }
            app.container.repository.saveSnapshot(UsageSnapshot(id, System.currentTimeMillis(), SnapshotStatus.OK,
                windows = listOf(UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 85.0, 604_800, null, false))))
        }
    }

    private fun assertStill(image: ImageView, body: Int, mood: ClaudeCodeMood, ink: Int, style: WidgetStyle) {
        val bitmap = (image.drawable as BitmapDrawable).bitmap
        assertEquals("envelope keeps the CH-5 body size", kotlin.math.ceil(body * 1.3).toInt(), image.height)
        assertEquals("bitmap drawn for density without rescaling", bitmap.width, image.width)
        assertEquals("the square is not compressed vertically", bitmap.height, image.height)
        val reference = com.usagelimits.ui.claudeCodeStillBitmap(mood, ink, body, bitmap.width, style.textSecondary.toArgb())
        try {
            if (!reference.sameAs(bitmap)) {
                val firstDifference = (0 until bitmap.height).firstNotNullOfOrNull { y ->
                    (0 until bitmap.width).firstOrNull { x -> bitmap.getPixel(x, y) != reference.getPixel(x, y) }
                        ?.let { x -> "($x,$y): actual=${bitmap.getPixel(x, y).toUInt().toString(16)}, expected=${reference.getPixel(x, y).toUInt().toString(16)}" }
                }
                System.getProperty("capture.dir")?.let { directory ->
                    for ((label, frame) in listOf("actual" to bitmap, "reference" to reference)) {
                        File(directory, "mood-difference-$mood-$body-$label.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                org.junit.Assert.fail("projected $mood at $body px uses the shared steady drawing; $firstDifference")
            }
        } finally { reference.recycle() }
        val inset = (bitmap.width - body) / 2f
        fun pixel(x: Float, y: Float) = bitmap.getPixel((inset + x * body / 24f).toInt(), (inset + y * body / 24f).toInt())
        assertEquals("body uses caller ink", ink, pixel(12f, 14f))
        val torsoY = (inset + body * 15.5f / 24f).toInt()
        val torso = (0 until bitmap.width).filter { bitmap.getPixel(it, torsoY) == ink }
        assertTrue("the torso stays three quarters of its slot, not its envelope",
            kotlin.math.abs(torso.size - body * 0.75f) <= 2f)
        if (body >= 36 && mood == ClaudeCodeMood.DANCING) {
            assertEquals("left eye is real alpha", 0, Color.alpha(pixel(6.74f, 9.5f)))
            assertEquals("right eye is real alpha", 0, Color.alpha(pixel(17.25f, 9.5f)))
        }
        assertEquals("bitmap surround is transparent", 0, Color.alpha(bitmap.getPixel(0, bitmap.height - 1)))
    }

    private fun saveMoodCapture(root: View, name: String) {
        val directory = System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for ((backdrop, color) in listOf("dark" to 0xFF242322.toInt(), "light" to 0xFFF3F0E8.toInt())) {
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                root.draw(canvas)
                File(directory, "$name-on-$backdrop.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { bitmap.recycle() }
    }

    /** Runs [body] at [scale] and always puts the font scale back, so a failure cannot leak it onward. */
    private inline fun <T> atFontScale(scale: Float, body: () -> T): T {
        val original = app.resources.configuration.fontScale
        RuntimeEnvironment.setFontScale(scale)
        try {
            return body()
        } finally {
            RuntimeEnvironment.setFontScale(original)
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

    private fun claudeArtworkSlotSizes(view: View): List<Int> = providerImages(view).map { image ->
        if (image.drawable is BitmapDrawable && shadowOf(image.drawable).createdFromResId == 0)
            (image.width / 1.3f).toInt() else image.width
    }

    /** Provider art is decorative; rings and the refresh button have spoken descriptions. */
    private fun providerImages(view: View): List<ImageView> = buildList {
        if (!view.contentDescription.isNullOrBlank()) return@buildList
        if (view is ImageView && view.drawable is BitmapDrawable &&
            view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
            view.width == view.height && view.scaleType == ImageView.ScaleType.FIT_CENTER) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(providerImages(view.getChildAt(index)))
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
