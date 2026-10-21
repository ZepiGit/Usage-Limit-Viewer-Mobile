package com.usagelimits.widget

import android.view.View
import android.widget.FrameLayout
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
