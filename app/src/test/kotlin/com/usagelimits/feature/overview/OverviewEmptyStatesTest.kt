package com.usagelimits.feature.overview

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Overview's three states without a list of accounts, told apart by their own words and actions,
 * in English and German (#88 01b, third review, item 8): C1 with no accounts, loading before the
 * store has answered, and a filter that matches nothing. Each test is one row of the table; every
 * marker is read from the resources of the app's language, so no English word stands in for German.
 * The two languages are the two subclasses below, each with its own locale qualifier.
 */
abstract class OverviewEmptyStatesTest {

    @get:Rule val compose = createComposeRule()

    private val now = 1_757_000_000_000L
    private val utc = ZoneId.of("Etc/UTC")

    private val healthy = AccountUsage(
        ProviderAccount("a", ProviderId.CODEX, "ext-a", "someone@example.com", null, "Plus", "ref-a", 0L, now),
        UsageSnapshot("a", now, SnapshotStatus.OK, listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 27.0, 18_000, now + 3_600_000, false))),
    )

    private fun word(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    /** The markers: the state's own words, and the entries each state must or must not offer. */
    private val markers = listOf(
        "C1 title" to R.string.overview_empty_title,
        "C1 credit entry" to R.string.overview_track_credit,
        "loading" to R.string.overview_loading_usage,
        "no match" to R.string.overview_no_match,
        "filter chip" to R.string.overview_filter_attention,
        "mode chip" to R.string.percent_flip_to_used,
        "add account" to R.string.overview_add_account,
        "credits add" to R.string.overview_add_credit,
    )

    /** Which markers the screen shows, scrolled through so the credits block below is reached. */
    private fun observed(): Map<String, Boolean> = markers.associate { (name, id) ->
        val text = word(id)
        val visible = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithContentDescription(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            runCatching {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
                true
            }.getOrDefault(false)
        name to visible
    }

    private fun show(state: UsageUiState, filter: OverviewFilter = OverviewFilter.ALL,
        onAddAccount: () -> Unit = {}, onAddCredit: () -> Unit = {}) {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = state, nowMs = now, zone = utc, filter = filter,
                    onRefresh = {}, onAccountClick = {}, onAddAccount = onAddAccount, onPercentDisplayChange = {},
                    onAddCredit = onAddCredit)
            }
        }
        compose.waitForIdle()
    }

    @Test fun `C1 offers both entries and nothing to filter, and both of its actions open their flows`() {
        var addAccount = 0
        var addCredit = 0
        show(UsageUiState(), onAddAccount = { addAccount++ }, onAddCredit = { addCredit++ })
        assertEquals(mapOf("C1 title" to true, "C1 credit entry" to true, "loading" to false, "no match" to false,
            "filter chip" to false, "mode chip" to false, "add account" to true, "credits add" to true), observed())

        compose.onNode(hasText(word(R.string.overview_add_account)) and hasClickAction()).performClick()
        compose.onNode(hasText(word(R.string.overview_track_credit)) and hasClickAction()).performClick()
        assertEquals("each C1 action opens its own flow, once", 1 to 1, addAccount to addCredit)
    }

    @Test fun `while loading no state's words and no add action are shown`() {
        show(UsageUiState(loaded = false))
        assertEquals(mapOf("C1 title" to false, "C1 credit entry" to false, "loading" to true, "no match" to false,
            "filter chip" to false, "mode chip" to false, "add account" to false, "credits add" to false), observed())
    }

    @Test fun `a filter without matches says so and keeps the List's and the credits' entries`() {
        show(UsageUiState(accounts = listOf(healthy)), filter = OverviewFilter.NEEDS_ATTENTION)
        assertEquals(mapOf("C1 title" to false, "C1 credit entry" to false, "loading" to false, "no match" to true,
            "filter chip" to true, "mode chip" to true, "add account" to true, "credits add" to true), observed())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w393dp-h1600dp")
class OverviewEmptyStatesEnglishTest : OverviewEmptyStatesTest()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w393dp-h1600dp")
class OverviewEmptyStatesGermanTest : OverviewEmptyStatesTest() {
    @Test fun `the German words are the ones on screen`() {
        // A guard that the qualifier took effect: the table above would pass in English too.
        assertEquals("Noch nichts zu verfolgen",
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.overview_empty_title))
    }
}
