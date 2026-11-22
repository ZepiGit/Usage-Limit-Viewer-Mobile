package com.usagelimits.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.util.TypedValueCompat
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.model.SharedVectors
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal

/** The Credits widget composed in a real Glance session at each of its three sizes. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalGlanceRemoteViewsApi::class, ExperimentalGlanceApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class CreditsWidgetRenderingTest {

    @Before fun initializeApplication() {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        runBlocking { app.startupJob?.join() }
    }

    @org.junit.After fun drain() {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        try {
            androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            com.usagelimits.core.sync.resetTestWorkManager()
        }
    }

    private suspend fun seed(app: UsageLimitsApp, count: Int) {
        val now = System.currentTimeMillis()
        repeat(count) { index ->
            val id = "credit-fal-$index"
            app.container.creditRepository.upsert(
                CreditAccount(id, CreditProviderId.FAL, "Account $index", "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(index * 3))), fetchedAt = now),
            )
        }
    }

    private fun texts(view: View, text: String) = arrayListOf<View>().also { view.findViewsWithText(it, text, View.FIND_VIEWS_WITH_TEXT) }

    @Test
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile puts centered title above 28sp monospace balance and status`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            seed(app, 1)
            val view = CreditsWidget().compose(app, size = DpSize(110.dp, 110.dp)).apply(app, FrameLayout(app))
            val pixels = (110 * app.resources.displayMetrics.density).toInt()
            val spec = View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, pixels, pixels)

            val title = texts(view, "Account 0").single() as TextView
            val amount = texts(view, "$0.00").single() as TextView
            val status = (texts(view, "As of") + texts(view, "fal.ai")).single() as TextView
            fun bounds(child: View) = Rect().also { rect ->
                child.getDrawingRect(rect)
                (view as ViewGroup).offsetDescendantRectToMyCoords(child, rect)
            }
            val titleBounds = bounds(title)
            val amountBounds = bounds(amount)
            val statusBounds = bounds(status)
            val padding = 10 * app.resources.displayMetrics.density
            assertEquals("title starts at the top of the padded tile", padding, titleBounds.top.toFloat(), 1f)
            val titleTextCenter = titleBounds.left + title.totalPaddingLeft +
                (title.layout.getLineLeft(0) + title.layout.getLineRight(0)) / 2f
            // The title and its 6dp dot plus 6dp gap, not the title glyphs alone, are centered.
            assertEquals("title and severity dot are centered as a group", pixels / 2f, titleTextCenter - 6f, 1f)
            assertTrue("balance is beneath the title", amountBounds.top >= titleBounds.bottom)
            assertEquals("the amount itself, not the amount-status group, is centered vertically",
                pixels / 2f, amountBounds.exactCenterY(), 1f)
            assertEquals("the amount is centered horizontally", pixels / 2f, amountBounds.exactCenterX(), 1f)
            assertTrue("status is directly beneath the balance", statusBounds.top in amountBounds.bottom..(amountBounds.bottom + 1))
            assertTrue("a current balance has a freshness label", status.text.startsWith("As of "))
            assertEquals("balance uses 28sp", androidx.core.util.TypedValueCompat.spToPx(28f, app.resources.displayMetrics), amount.textSize, 0.01f)
            val styled = amount.text as Spanned
            assertEquals("monospace", styled.getSpans(0, styled.length, TypefaceSpan::class.java).single().family)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun smallTile(app: UsageLimitsApp): View {
        val view = CreditsWidget().compose(app, size = DpSize(110.dp, 110.dp)).apply(app, FrameLayout(app))
        val pixels = (110 * app.resources.displayMetrics.density).toInt()
        val spec = View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, pixels, pixels)
        return view
    }

    private fun capture(view: View, name: String): Bitmap {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let { path ->
                val directory = File(path).apply { mkdirs() }
                File(directory, "credits-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return image
        } catch (failure: Throwable) {
            image.recycle()
            throw failure
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile keeps severity dot inside title row with long title`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = System.currentTimeMillis()
            val title = "A very long credit account title for testing"
            val id = "long-title"
            app.container.creditRepository.upsert(
                CreditAccount(id, CreditProviderId.FAL, title, "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal("20"))), fetchedAt = now),
            )
            val view = smallTile(app)
            val image = capture(view, "S2-long-title-en")
            try {
                val titleView = texts(view, title).single() as TextView
                val titleBounds = Rect().also {
                    titleView.getDrawingRect(it)
                    (view as ViewGroup).offsetDescendantRectToMyCoords(titleView, it)
                }
                val dot = Rect(image.width, image.height, 0, 0)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (image.getPixel(x, y) == 0xFF6FBF73.toInt()) {
                        dot.left = minOf(dot.left, x)
                        dot.top = minOf(dot.top, y)
                        dot.right = maxOf(dot.right, x + 1)
                        dot.bottom = maxOf(dot.bottom, y + 1)
                    }
                }
                assertTrue("the green dot is fully present, not clipped away: $dot", dot.width() >= 4 && dot.width() == dot.height())
                assertTrue("the dot is inside the tile: $dot", dot.left >= 0 && dot.top >= 0 && dot.right <= image.width && dot.bottom <= image.height)
                assertTrue("the dot is inside the title row: $dot vs $titleBounds", dot.top >= titleBounds.top && dot.bottom <= titleBounds.bottom)
            } finally {
                image.recycle()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun boundsIn(root: View, child: View) = Rect(0, 0, child.width, child.height).also {
        (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
    }

    private fun inkIn(root: View, view: TextView): Rect {
        val styled = view.layout.text
        val text = styled.toString()
        val result = Rect()
        val runInk = Rect()
        val paint = TextPaint()
        for (line in 0 until view.layout.lineCount) {
            val end = view.layout.getLineVisibleEnd(line)
            val baseline = view.baseline + view.layout.getLineBaseline(line) - view.layout.getLineBaseline(0)
            var start = view.layout.getLineStart(line)
            while (start < end) {
                paint.set(view.paint)
                val runEnd = if (styled is Spanned) {
                    val transition = styled.nextSpanTransition(start, end, MetricAffectingSpan::class.java)
                    styled.getSpans(start, transition, MetricAffectingSpan::class.java).forEach { it.updateDrawState(paint) }
                    transition
                } else end
                paint.getTextBounds(text, start, runEnd, runInk)
                runInk.offset(view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start).toInt(), baseline)
                result.union(runInk)
                start = runEnd
            }
        }
        val bounds = boundsIn(root, view)
        result.offset(bounds.left, bounds.top)
        return result
    }

    @Serializable
    private data class CompactStatusFile(val description: String, val vectors: List<CompactStatusVector>)

    @Serializable
    private data class CompactStatusVector(val state: String, val en: String, val de: String)

    private fun compactStatuses(app: UsageLimitsApp, german: Boolean): Map<String, String> {
        val file = SharedVectors.file("credit-widget-compact-status.json")
        val vectors = SharedVectors.parse(file.readText(), CompactStatusFile.serializer()).vectors
        val resources = mapOf(
            "unread" to com.usagelimits.R.string.widget_credits_compact_not_read,
            "unreadable" to com.usagelimits.R.string.widget_credits_compact_unreadable,
            "refreshFailed" to com.usagelimits.R.string.widget_credits_compact_refresh_failed,
        )
        assertEquals(resources.keys, vectors.map { it.state }.toSet())
        return vectors.associate { vector ->
            val expected = if (german) vector.de else vector.en
            assertEquals(vector.state, expected, app.getString(resources.getValue(vector.state)))
            vector.state to expected
        }
    }

    private suspend fun clippedStatuses(
        app: UsageLimitsApp,
        language: String,
        prefixes: List<List<String>>,
        compact: Map<String, String> = emptyMap(),
    ): List<String> {
        val now = System.currentTimeMillis()
        val id = "status-fixture"
        val account = CreditAccount(id, CreditProviderId.OPENROUTER, "Status account", "credit:$id", createdAt = now)
        val uncapped = CreditBalance("USD", BigDecimal("20"), unlimited = true)
        val fixtures = listOf(
            "unread" to null,
            "stale" to CreditSnapshot(id, listOf(uncapped), fetchedAt = now - 30L * 24 * 60 * 60 * 1000),
            "unreadable" to CreditSnapshot(id, emptyList(), fetchedAt = now, status = CreditStatus.FAILED, error = null),
            "refreshFailed" to CreditSnapshot(id, listOf(uncapped), fetchedAt = now, status = CreditStatus.FAILED, error = "Network error"),
        )
        return fixtures.zip(prefixes).mapNotNull { (fixture, alternatives) ->
            val (state, snapshot) = fixture
            app.container.creditRepository.remove(id)
            app.container.creditRepository.upsert(account, snapshot)
            val view = smallTile(app)
            capture(view, "S3-$state-$language").recycle()
            val short = compact[state]
            val candidates = alternatives + listOfNotNull(short)
            val status = candidates.flatMap { texts(view, it) }.distinct().single() as TextView
            val text = status.text.toString()
            if (language == "de-1_6x" && short != null) {
                assertEquals("$state uses the approved compact word", short, text)
            } else {
                assertTrue("$state at $language: expected an exact visible label from $candidates, got '$text'",
                    text in candidates)
            }
            // Read the accessibility label delivered by RemoteViews to the actual host TextView.
            val speech = status.contentDescription?.toString()
            assertTrue("$state at $language: full accessibility label, got '$speech'", speech in alternatives)
            val title = texts(view, account.label).single() as TextView
            val hasBalance = snapshot?.balances?.isNotEmpty() == true
            val amountText = when {
                !hasBalance -> "—"
                language.startsWith("de") -> "20,00"
                else -> "20.00"
            }
            val amount = texts(view, amountText).single() as TextView
            val titleInk = inkIn(view, title)
            val amountInk = inkIn(view, amount)
            val statusInk = inkIn(view, status)
            val host = Rect(0, 0, view.width, view.height)
            assertTrue("$state at $language: title $titleInk, amount $amountInk and status $statusInk ink stay in order",
                titleInk.bottom <= amountInk.top && amountInk.bottom <= statusInk.top)
            for (ink in listOf(titleInk, amountInk, statusInk)) {
                assertTrue("$state at $language: text ink $ink is visible inside $host", !ink.isEmpty && host.contains(ink))
            }
            assertTrue("$state at $language: amount fits without an ellipsis",
                amount.layout.getEllipsisCount(0) == 0 && amount.layout.getLineVisibleEnd(0) == amount.text.length)
            if (!hasBalance) assertEquals("the placeholder still uses 28sp", TypedValueCompat.spToPx(28f, app.resources.displayMetrics),
                amount.textSize, 0.01f)
            val layout = status.layout
            val visible = Rect()
            val ink = Rect()
            assertTrue("'$text' is visible in $language", status.getLocalVisibleRect(visible))
            assertEquals("'$text' uses the full 11sp at $language", TypedValueCompat.spToPx(11f, app.resources.displayMetrics),
                status.textSize, 0.01f)
            val hidden = text.indices.firstOrNull { offset ->
                if (text[offset].isWhitespace()) return@firstOrNull false
                val line = layout.getLineForOffset(offset)
                val ellipsisStart = layout.getLineStart(line) + layout.getEllipsisStart(line)
                // The host clips ink, not the font's full line box (which can extend past descenders).
                status.paint.getTextBounds(text, offset, offset + 1, ink)
                ink.offset(status.totalPaddingLeft + layout.getPrimaryHorizontal(offset).toInt(),
                    status.baseline + layout.getLineBaseline(line) - layout.getLineBaseline(0))
                offset in ellipsisStart until ellipsisStart + layout.getEllipsisCount(line) ||
                    offset >= layout.getLineVisibleEnd(line) || !visible.contains(ink)
            }
            hidden?.let { "'$text' clipped at character $it in $language (ink $ink, visible $visible, ${status.textSize}px)" }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile shows complete status prefixes in English`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            checkStatusScales(app, "en", listOf(listOf("Not read yet"), listOf("Out of date", "Stale"),
                listOf("Could not be read"), listOf("Refresh failed")))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(qualifiers = "de-rDE-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile shows complete status prefixes in German`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            checkStatusScales(app, "de", listOf(listOf("Noch nicht gelesen"), listOf("Veraltet"),
                listOf("Konnte nicht gelesen werden"), listOf("Aktualisierung fehlgeschlagen")))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun checkStatusScales(app: UsageLimitsApp, language: String, prefixes: List<List<String>>) {
        val compact = compactStatuses(app, language == "de")
        val originalScale = app.resources.configuration.fontScale
        try {
            val problems = buildList {
                org.robolectric.RuntimeEnvironment.setFontScale(1f)
                addAll(clippedStatuses(app, language, prefixes))
                org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
                addAll(clippedStatuses(app, "$language-1_6x", prefixes, compact))
                org.robolectric.RuntimeEnvironment.setFontScale(2f)
                addAll(clippedStatuses(app, "$language-2x", prefixes, compact))
            }
            assertTrue(problems.joinToString("; "), problems.isEmpty())
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    @Test
    @Config(qualifiers = "de-rDE-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile displays the entire German currency amount`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = System.currentTimeMillis()
            val id = "currency-fit"
            val account = CreditAccount(id, CreditProviderId.OPENROUTER, "Status account", "credit:$id", createdAt = now)
            val problems = mutableListOf<String>()
            for (failed in listOf(false, true)) {
                val status = if (failed) CreditStatus.FAILED else CreditStatus.OK
                app.container.creditRepository.upsert(account, CreditSnapshot(id,
                    listOf(CreditBalance("USD", BigDecimal("20"), unlimited = failed)), fetchedAt = now,
                    status = status, error = if (failed) "Network error" else null))
                val view = smallTile(app)
                capture(view, if (failed) "amount-uncapped-failed-de" else "amount-current-capped-de").recycle()
                val amount = texts(view, "20,00").single() as TextView
                val layout = amount.layout
                val contentWidth = amount.width - amount.totalPaddingLeft - amount.totalPaddingRight
                val ellipsis = layout.getEllipsisCount(0)
                val visibleEnd = layout.getLineVisibleEnd(0)
                val details = "$status: '${amount.text}', laid out ${layout.getLineWidth(0)} px in $contentWidth px, " +
                    "text size ${amount.textSize} px, ellipsis start ${layout.getEllipsisStart(0)}, count $ellipsis, visible end $visibleEnd"
                println(details)
                if (amount.text.toString().replace('\u00a0', ' ') != "20,00 $" || layout.lineCount != 1 ||
                    ellipsis != 0 || visibleEnd != amount.text.length || layout.getLineWidth(0) > contentWidth) {
                    problems += details
                }
            }
            assertTrue(problems.joinToString("; "), problems.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(qualifiers = "de-rDE-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small German Credits at 1_6x keeps the centered amount and complete freshness`() = runTest {
        checkLargeTile("20,00", "Stand", "de")
    }

    @Test
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small English Credits at 1_6x keeps the centered amount and complete freshness`() = runTest {
        checkLargeTile("20.00", "As of", "en")
    }

    private suspend fun TestScope.checkLargeTile(amountText: String, freshness: String, language: String) {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val originalScale = app.resources.configuration.fontScale
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
            val now = System.currentTimeMillis()
            val id = "large-credit"
            app.container.creditRepository.upsert(
                CreditAccount(id, CreditProviderId.FAL, "Guthaben", "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal("20"))), fetchedAt = now))
            val view = smallTile(app)
            capture(view, "small-1_6x-$language").recycle()
            val amount = texts(view, amountText).single() as TextView
            val status = texts(view, freshness).single() as TextView
            fun bounds(child: View) = Rect(0, 0, child.width, child.height).also {
                (view as ViewGroup).offsetDescendantRectToMyCoords(child, it)
            }
            val amountBounds = bounds(amount)
            val statusBounds = bounds(status)
            val title = texts(view, "Guthaben").single()
            val bottomMargin = (5f * app.resources.displayMetrics.density / app.resources.configuration.fontScale).toInt()
            val centeredAmountBottom = (view.height + amountBounds.height()) / 2
            if (centeredAmountBottom + statusBounds.height() + bottomMargin <= view.height) {
                assertEquals("amount stays centered when freshness fits below it", view.height / 2f, amountBounds.exactCenterY(), 1f)
            }
            assertTrue("title remains above the amount", bounds(title).bottom <= amountBounds.top)
            assertTrue("freshness hangs below the amount", statusBounds.top >= amountBounds.bottom)
            assertEquals("freshness keeps the full requested 11sp", TypedValueCompat.spToPx(11f, app.resources.displayMetrics),
                status.textSize, 0.01f)
            for (text in listOf(amount, status)) {
                val layout = text.layout
                assertEquals("${text.text} retains the complete text", text.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) {
                    assertEquals("${text.text} is not ellipsized", 0, layout.getEllipsisCount(line))
                    assertTrue("${text.text} fits its width", layout.getLineWidth(line) <= text.width - text.totalPaddingLeft - text.totalPaddingRight)
                }
                assertTrue("${text.text} fits its height", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                assertTrue("${text.text} stays inside the tile", Rect(0, 0, view.width, view.height).contains(bounds(text)))
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(originalScale)
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(qualifiers = "de-rDE-mdpi")
    fun `small uncapped Credits uses the same spend wording as a balance line`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val now = System.currentTimeMillis()
            val id = "uncapped-wording"
            app.container.creditRepository.upsert(
                CreditAccount(id, CreditProviderId.OPENROUTER, "Uncapped", "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal("20"), unlimited = true)), fetchedAt = now))
            val view = smallTile(app)
            assertEquals("one localized spending clause, without repeating the amount", 1,
                texts(view, "diesen Monat ausgegeben").size)
            assertEquals("the balance remains separate", 1, texts(view, "20,00").size)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    @Config(qualifiers = "de-rDE-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `uncapped Credits at 2x keeps the amount and speaks complete freshness`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val originalScale = app.resources.configuration.fontScale
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(2f)
            val now = System.currentTimeMillis()
            val id = "uncapped-large"
            app.container.creditRepository.upsert(
                CreditAccount(id, CreditProviderId.OPENROUTER, "Guthaben", "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal("20"), unlimited = true)), fetchedAt = now),
            )
            val view = smallTile(app)
            capture(view, "uncapped-de-2x").recycle()
            val title = texts(view, "Guthaben").single() as TextView
            val amount = texts(view, "20,00").single() as TextView
            val status = texts(view, "diesen Monat ausgegeben").single() as TextView
            assertEquals("complete spending and freshness label", true,
                status.contentDescription?.matches(Regex("diesen Monat ausgegeben · Stand .+")))
            assertTrue("ancillary freshness still has at most two visible lines", status.layout.lineCount in 1..2)
            assertEquals("freshness keeps the full 11sp", TypedValueCompat.spToPx(11f, app.resources.displayMetrics), status.textSize, 0.01f)
            assertEquals("amount retains its complete currency figure", 0, amount.layout.getEllipsisCount(0))
            val ink = listOf(title, amount, status).map { inkIn(view, it) }
            val host = Rect(0, 0, view.width, view.height)
            assertTrue("all lettering $ink fits in $host", ink.all { !it.isEmpty && host.contains(it) })
            assertTrue("title, amount and status ink stay in order: $ink", ink.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(originalScale)
            Dispatchers.resetMain()
        }
    }

    @Test fun `composes empty and with accounts at every size`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))
            for (size in sizes) CreditsWidget().compose(app, size = size).apply(app, FrameLayout(app))
            seed(app, 1)
            for (size in sizes) {
                val view = CreditsWidget().compose(app, size = size).apply(app, FrameLayout(app))
                assertTrue("single account retains the large tile at $size", texts(view, "Credits").isEmpty())
                assertTrue("single account is named at $size", texts(view, "Account 0").isNotEmpty())
            }
            seed(app, 8)
            for (size in sizes) {
                val view = CreditsWidget().compose(app, size = size).apply(app, FrameLayout(app))
                assertEquals("list header only above the small tile at $size", size.width >= 250.dp, texts(view, "Credits").isNotEmpty())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
