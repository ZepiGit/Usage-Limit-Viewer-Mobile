package com.usagelimits.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

    private fun capture(view: View, name: String): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { image ->
            view.draw(Canvas(image))
            System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let { path ->
                val directory = File(path).apply { mkdirs() }
                File(directory, "credits-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
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

    private suspend fun checkStatuses(app: UsageLimitsApp, language: String, prefixes: List<List<String>>) {
        val now = 1_750_000_000_000L
        val previousTime = System.currentTimeMillis()
        SystemClock.setCurrentTimeMillis(now)
        try {
            val id = "status-fixture"
            val account = CreditAccount(id, CreditProviderId.OPENROUTER, "Status account", "credit:$id", createdAt = now)
            val uncapped = CreditBalance("USD", BigDecimal("20"), unlimited = true)
            val fixtures = listOf(
                "not-read" to null,
                "stale" to CreditSnapshot(id, listOf(uncapped), fetchedAt = now - 30L * 24 * 60 * 60 * 1000),
                "failed" to CreditSnapshot(id, emptyList(), fetchedAt = now, status = CreditStatus.FAILED, error = null),
                "uncapped-failed" to CreditSnapshot(id, listOf(uncapped), fetchedAt = now, status = CreditStatus.FAILED, error = "Network error"),
            )
            val views = fixtures.map { (name, snapshot) ->
                app.container.creditRepository.remove(id)
                app.container.creditRepository.upsert(account, snapshot)
                smallTile(app).also { capture(it, "S3-$name-$language").recycle() }
            }
            val clipped = views.zip(prefixes).mapNotNull { (view, alternatives) ->
                val status = alternatives.flatMap { texts(view, it) }.distinct().single() as TextView
                val phrase = alternatives.first { it in status.text }
                val start = status.text.indexOf(phrase)
                val layout = status.layout
                val contentHeight = status.height - status.totalPaddingTop - status.totalPaddingBottom
                val hidden = phrase.indices.firstOrNull { index ->
                    val offset = start + index
                    val line = layout.getLineForOffset(offset)
                    val ellipsisStart = layout.getLineStart(line) + layout.getEllipsisStart(line)
                    !phrase[index].isWhitespace() && (offset in ellipsisStart until ellipsisStart + layout.getEllipsisCount(line) ||
                        offset >= layout.getLineVisibleEnd(line) || layout.getLineBottom(line) > contentHeight)
                }
                hidden?.let { "'$phrase' clipped at character $it in '${status.text}'" }
            }
            assertTrue(clipped.joinToString("; "), clipped.isEmpty())
        } finally {
            SystemClock.setCurrentTimeMillis(previousTime)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `small tile shows complete status prefixes in English`() = runTest {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            checkStatuses(app, "en", listOf(listOf("Not read yet"), listOf("Out of date", "Stale"), listOf("Could not be read"), listOf("Refresh failed")))
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
            checkStatuses(app, "de", listOf(listOf("Noch nicht gelesen"), listOf("Veraltet"), listOf("Konnte nicht gelesen werden"), listOf("Aktualisierung fehlgeschlagen")))
        } finally {
            Dispatchers.resetMain()
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
