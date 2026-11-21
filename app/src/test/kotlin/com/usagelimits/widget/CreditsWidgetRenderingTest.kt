package com.usagelimits.widget

import android.graphics.Rect
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
            assertEquals("title and severity dot are centered as a group", pixels / 2f, titleTextCenter + 6f, 1f)
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
