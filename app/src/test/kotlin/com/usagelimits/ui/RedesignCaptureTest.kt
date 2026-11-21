package com.usagelimits.ui

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.ui.theme.UsageColors
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.settings.FigureFont
import com.usagelimits.core.settings.TextFont
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.core.settings.OverviewCreditLayout
import com.usagelimits.core.settings.OverviewShowFirst
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.credits.CreditDetailScreen
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.ui.theme.UsageType
import com.usagelimits.widget.WidgetBackground
import com.usagelimits.widget.WidgetStyle
import com.usagelimits.widget.WidgetUpdater
import com.usagelimits.widget.WidgetMetrics
import com.usagelimits.core.sync.resetTestWorkManager
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.feature.overview.OverviewFilter
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Redesign captures: every screen and widget drawn to a PNG with synthetic demo data.
 *
 * Writes only when the `capture.dir` system property is set
 * (`./gradlew :app:testDebugUnitTest --tests 'com.usagelimits.ui.Redesign*' -PcaptureDir=/abs/path`);
 * otherwise it renders and passes without touching the disk. The window is taller than a phone
 * (1800dp) so a whole scrolling screen lands in one image; the first 852dp is the fold.
 */
private val captureDir: File? = System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let(::File)

/** Runs [block] on this still and releases its pixels afterwards, also when drawing or encoding throws. */
private inline fun Bitmap.useOnce(block: (Bitmap) -> Unit) {
    try {
        block(this)
    } finally {
        recycle()
    }
}

private fun Bitmap.save(name: String) {
    val dir = captureDir ?: return
    dir.mkdirs()
    File(dir, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private const val NOW = 1_757_000_000_000L
private const val HOUR = 3_600_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h1800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun account(id: String, provider: ProviderId, name: String, plan: String?) = ProviderAccount(
        localId = id, provider = provider, externalAccountId = "ext-$id", email = "$id@example.invalid",
        displayName = name, plan = plan, credentialReference = "ref-$id", createdAt = 0L, lastSuccessfulSync = NOW,
    )

    private fun window(id: String, label: String, category: WindowCategory, used: Double?, period: Long, resetIn: Long,
                       scope: UsageScope = UsageScope.GLOBAL) =
        UsageWindow(id, label, category, used, period, NOW + resetIn, used == 100.0, scope = scope)

    private val claude = AccountUsage(
        account("claude-work", ProviderId.CLAUDE, "Work", "Max 5x"),
        UsageSnapshot("claude-work", NOW - 4 * 60_000, SnapshotStatus.OK, windows = listOf(
            window("5h", "5h limit", WindowCategory.FIVE_HOUR, 34.0, 18_000, 2 * HOUR + 12 * 60_000),
            window("week", "Weekly", WindowCategory.WEEKLY, 71.0, 604_800, 3 * 24 * HOUR),
            window("opus", "Weekly (Opus)", WindowCategory.WEEKLY, 88.0, 604_800, 3 * 24 * HOUR, UsageScope.MODEL),
        )),
    )
    private val codex = AccountUsage(
        account("codex-home", ProviderId.CODEX, "Home", "Plus"),
        UsageSnapshot("codex-home", NOW - 9 * 60_000, SnapshotStatus.OK, windows = listOf(
            window("5h", "5h limit", WindowCategory.FIVE_HOUR, 0.0, 18_000, 5 * HOUR),
            window("week", "Weekly", WindowCategory.WEEKLY, 96.0, 604_800, 26 * HOUR),
        )),
    )
    private val kimi = AccountUsage(
        account("kimi-side", ProviderId.KIMI, "Side project", "Moderato").copy(lastSuccessfulSync = NOW - 30 * HOUR),
        UsageSnapshot("kimi-side", NOW - 30 * HOUR, SnapshotStatus.FAILED, windows = listOf(
            window("week", "Weekly", WindowCategory.WEEKLY, 52.0, 604_800, 40 * HOUR),
        ), errorMessage = "No network connection"),
    )

    private fun credit(id: String, provider: CreditProviderId, label: String, amount: String, total: String?,
                       error: String? = null) = CreditEntry(
        CreditAccount(id, provider, label, "credit:$id", createdAt = NOW),
        CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(amount), total = total?.let(::BigDecimal))),
            fetchedAt = NOW - 6 * 60_000, status = if (error == null) CreditStatus.OK else CreditStatus.FAILED, error = error),
    )

    private val credits = listOf(
        credit("or", CreditProviderId.OPENROUTER, "OpenRouter team", "18.40", "50"),
        credit("ds", CreditProviderId.DEEPSEEK, "DeepSeek", "3.12", null),
        credit("fal", CreditProviderId.FAL, "fal renders", "0.00", "25", error = "The key was rejected (HTTP 401). Check that it is the right kind of key."),
    )

    private val state = UsageUiState(accounts = listOf(claude, codex, kimi), settings = AppSettings(), credits = credits)

    /** Codex's sign-in expired: its last figures stay, it ranks first (A1) and its detail is C2. */
    private val expiredCodex = codex.copy(snapshot = codex.snapshot!!.copy(
        status = SnapshotStatus.FAILED, connectionStatus = ConnectionStatus.RECONNECT_REQUIRED,
        errorMessage = NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE))

    private val utc = ZoneId.of("Etc/UTC")

    private fun screens(): List<Pair<String, @Composable () -> Unit>> = listOf(
        // A1: Overview, List, with an expired sign-in ranked first.
        "overview" to {
            OverviewScreen(state = state.copy(accounts = listOf(claude, expiredCodex, kimi)), nowMs = NOW, zone = utc,
                onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onCreditClick = {}, onAddCredit = {})
        },
        // A1 with Resets today chosen where nothing resets today: the no-match line in the card.
        "overview-no-match" to {
            OverviewScreen(state = state.copy(accounts = listOf(kimi)), nowMs = NOW, zone = utc,
                filter = OverviewFilter.RESETS_TODAY, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onPercentDisplayChange = {}, onCreditClick = {}, onAddCredit = {})
        },
        // C1: no accounts yet.
        "overview-empty" to {
            OverviewScreen(state = UsageUiState(), nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {},
                onAddAccount = {}, onPercentDisplayChange = {})
        },
        // Loading: before the store's first answer, in every language the captures run in.
        "overview-loading" to {
            OverviewScreen(state = UsageUiState(loaded = false), nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {},
                onAddAccount = {}, onPercentDisplayChange = {})
        },
        // C2: the account detail of an expired sign-in.
        "account-detail-expired" to {
            AccountDetailScreen(usage = expiredCodex, nowMs = NOW, staleAfterMs = state.staleAfterMs,
                percentDisplay = com.usagelimits.core.model.PercentDisplay.LEFT, resetInFlight = false,
                supportsResetCredits = false, onRefresh = {}, onConsumeResetCredit = {}, onRemove = {},
                notificationsEnabled = true, onNotificationsChange = {})
        },
        "account-detail" to {
            AccountDetailScreen(usage = claude, nowMs = NOW, staleAfterMs = state.staleAfterMs, percentDisplay = com.usagelimits.core.model.PercentDisplay.LEFT, resetInFlight = false,
                supportsResetCredits = false, onRefresh = {}, onConsumeResetCredit = {}, onRemove = {},
                notificationsEnabled = true, onNotificationsChange = {})
        },
        "account-detail-failed" to {
            AccountDetailScreen(usage = kimi, nowMs = NOW, staleAfterMs = state.staleAfterMs, percentDisplay = com.usagelimits.core.model.PercentDisplay.LEFT, resetInFlight = false,
                supportsResetCredits = false, onRefresh = {}, onConsumeResetCredit = {}, onRemove = {},
                notificationsEnabled = true, onNotificationsChange = {})
        },
        "add-account" to {
            AddAccountScreen(state = AddAccountState.PickProvider, providers = ProviderId.entries, onStart = {},
                onCancel = {}, onDone = {}, onSubmitApiKey = {})
        },
        "add-account-device-code" to {
            AddAccountScreen(state = AddAccountState.AwaitingDeviceCode(ProviderId.CODEX, "WDJB-MJHT",
                "https://auth.openai.com/device"), providers = ProviderId.entries, onStart = {}, onCancel = {},
                onDone = {}, onSubmitApiKey = {})
        },
        "resets" to { ResetsScreen(state = state, nowMs = NOW) },
        "settings" to {
            SettingsScreen(state = state, onSyncIntervalChange = {}, onNotificationPermissionHandled = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {},
                onPercentDisplayChange = {})
        },
        "credit-detail" to {
            CreditDetailScreen(entry = credits[0], nowMs = NOW, staleAfterMs = state.staleAfterMs,
                onSave = { _, _ -> }, onRefresh = {}, onRemove = {})
        },
    )

    private fun dialScreens(): List<Pair<String, @Composable () -> Unit>> = buildList {
        for (mode in PercentDisplay.entries) {
            val name = mode.name.lowercase()
            val settings = AppSettings(percentDisplay = mode, overviewLayout = OverviewLayout.DIALS)
            add("overview-dials-$name" to {
                OverviewScreen(state.copy(accounts = listOf(claude, expiredCodex, kimi), settings = settings),
                    nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
            })
            for (layout in OverviewLayout.entries) {
                add("settings-${layout.name.lowercase()}-$name" to {
                    SettingsScreen(state.copy(settings = settings.copy(overviewLayout = layout)), onSyncIntervalChange = {},
                        onNotificationPermissionHandled = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                        onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {}, onNotifyCreditExpiring = {},
                        onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {})
                })
            }
        }
    }

    private fun captureAll(mode: String, cases: List<Pair<String, @Composable () -> Unit>> = screens() + dialScreens()) {
        val current = mutableStateOf<@Composable () -> Unit>({})
        compose.setContent { UsageLimitsTheme { current.value() } }
        for ((name, content) in cases) {
            current.value = { androidx.compose.runtime.key(name) { content() } }
            compose.waitForIdle()
            if (name.startsWith("settings-")) {
                val scroll = compose.onNode(hasScrollToNodeAction())
                val display = compose.activity.getString(com.usagelimits.R.string.settings_section_display)
                val showTier = compose.activity.getString(com.usagelimits.R.string.settings_show_tier_title)
                scroll.performScrollToKey("display-heading")
                compose.onNodeWithText(display).assertIsDisplayed()
                scroll.performScrollToNode(hasText(showTier))
                compose.onNodeWithText(showTier).assertIsDisplayed()
                scroll.performScrollToKey("display-heading")
            }
            if (mode.endsWith("-large") && name.startsWith("overview-dials-")) {
                val nameNode = compose.onNodeWithText("OpenRouter team", useUnmergedTree = true)
                val age = if (mode.startsWith("de")) "OpenRouter · Aktualisiert vor 6 Min." else "OpenRouter · Updated 6m ago"
                val ageNode = compose.onNodeWithText(age, useUnmergedTree = true)
                ageNode.assertIsDisplayed()
                val identity = nameNode.fetchSemanticsNode().boundsInRoot
                val freshness = ageNode.fetchSemanticsNode().boundsInRoot
                assertTrue("freshness takes the full-width line below the inset account label", freshness.left < identity.left)
            }
            // Not onRoot().captureToImage(): under Robolectric 4.16 + compose ui-test 1.7.6 its
            // forceRedraw never sees a frame and times out (tried sdk 34 and 28). Drawing the
            // decor view in software is what the widget captures below do, and it works.
            captureScreen(name, mode)
        }
    }

    /** The independently named 03b credit treatments, captured through Overview's real callbacks. */
    private fun captureOverview03b(locale: String) {
        val current = mutableStateOf<@Composable () -> Unit>({})
        compose.setContent { UsageLimitsTheme { current.value() } }
        val language = if (locale == "de") "de" else "en"

        for (layout in OverviewLayout.entries) {
            val layoutName = if (layout == OverviewLayout.LIST) "list" else "dials"
            val baseSettings = AppSettings(
                overviewLayout = layout,
                overviewCreditLayout = OverviewCreditLayout.NORMAL,
                overviewShowFirst = OverviewShowFirst.USAGE_PROVIDERS,
                overviewShowCredits = true,
            )
            val scenarios = listOf(
                "normal" to baseSettings,
                "compact" to baseSettings.copy(overviewCreditLayout = OverviewCreditLayout.COMPACT),
                "expanded" to baseSettings.copy(overviewCreditLayout = OverviewCreditLayout.COMPACT),
                "hidden" to baseSettings.copy(overviewShowCredits = false),
                "credit-only" to baseSettings.copy(overviewShowFirst = OverviewShowFirst.CREDIT_ACCOUNTS),
            )
            for ((scenario, settings) in scenarios) {
                val name = "overview-03b-$scenario-$layoutName-$language"
                current.value = {
                    androidx.compose.runtime.key(name) {
                        val overviewState = if (scenario == "credit-only") {
                            UsageUiState(settings = settings, credits = credits)
                        } else {
                            state.copy(settings = settings)
                        }
                        OverviewScreen(
                            state = overviewState, nowMs = NOW, zone = utc,
                            onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                            onCreditClick = {}, onAddCredit = {}, onManageCredits = {},
                        )
                    }
                }
                compose.waitForIdle()
                val compactRow = if (language == "de") "Guthaben · 3 Konten" else "Credits · 3 balances"
                when (scenario) {
                    "normal" -> {
                        compose.onNodeWithText(if (language == "de") "Guthaben" else "Credits").assertIsDisplayed()
                        compose.onNodeWithText("OpenRouter team").assertIsDisplayed()
                    }
                    "compact" -> {
                        compose.onNodeWithContentDescription(compactRow, substring = true).assertIsDisplayed()
                        compose.onNodeWithText("OpenRouter team").assertDoesNotExist()
                    }
                    "expanded" -> compose.onNodeWithContentDescription(compactRow, substring = true).assertIsDisplayed()
                    "hidden" -> {
                        compose.onNodeWithText(if (language == "de") "Guthaben" else "Credits").assertDoesNotExist()
                        compose.onNodeWithText("OpenRouter team").assertDoesNotExist()
                    }
                    "credit-only" -> {
                        compose.onNodeWithText(compose.activity.getString(com.usagelimits.R.string.overview_no_accounts_yet)).assertIsDisplayed()
                        compose.onNodeWithText("Work").assertDoesNotExist()
                        compose.onNodeWithText("OpenRouter team").assertIsDisplayed()
                        compose.onNodeWithContentDescription(compactRow, substring = true).assertDoesNotExist()
                    }
                }
                if (scenario == "expanded") {
                    compose.onNodeWithText("OpenRouter team").assertDoesNotExist()
                    compose.onNodeWithContentDescription(compactRow, substring = true).performClick()
                    compose.waitForIdle()
                    compose.onNodeWithText("OpenRouter team").assertIsDisplayed()
                    compose.onNodeWithText(if (language == "de") "Einklappen" else "Collapse").assertIsDisplayed()
                }
                val root = compose.activity.window.decorView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).useOnce {
                    root.draw(Canvas(it))
                    it.save("screen-$name")
                }
            }
        }
    }

    private data class AppearanceScenario(
        val settings: AppSettings,
        val suffix: String,
        val locale: Locale = Locale.US,
        val fontScale: Float = 1f,
        val checkFonts: Boolean = false,
    )

    // Font rows show a full 100% and an ordinary 12%; theme evidence keeps its original accounts.
    private val fontDialAccounts = listOf(
        claude.copy(snapshot = claude.snapshot!!.copy(windows = listOf(
            window("5h", "5h limit", WindowCategory.FIVE_HOUR, 88.0, 18_000, 2 * HOUR + 12 * 60_000),
        ))),
        codex.copy(snapshot = codex.snapshot!!.copy(windows = listOf(
            window("5h", "5h limit", WindowCategory.FIVE_HOUR, 0.0, 18_000, 5 * HOUR),
        ))),
    )

    private fun appearanceScreens(scenario: AppearanceScenario): List<Pair<String, @Composable () -> Unit>> {
        val appearanceState = state.copy(settings = scenario.settings)
        return listOf(
            "A1-overview" to {
                OverviewScreen(state = appearanceState.copy(accounts = listOf(claude, expiredCodex, kimi)),
                    nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                    onPercentDisplayChange = {}, onCreditClick = {}, onAddCredit = {})
            },
            "A2-dials" to {
                OverviewScreen(
                    state = appearanceState.copy(
                        accounts = if (scenario.checkFonts) fontDialAccounts else listOf(claude, expiredCodex, kimi),
                        settings = scenario.settings.copy(overviewLayout = OverviewLayout.DIALS)),
                    nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                    onPercentDisplayChange = {}, onCreditClick = {}, onAddCredit = {})
            },
            "A5-settings" to {
                SettingsScreen(state = appearanceState, onSyncIntervalChange = {}, onNotificationPermissionHandled = {},
                    onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {}, onNotifyResetCredit = {},
                    onNotifyAuthExpired = {}, onNotifyResetApproaching = {}, onNotifyCreditExpiring = {},
                    onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {}, onAppThemeChange = {})
            },
        )
    }

    // Scoped locals keep every row in the existing SDK 34 native-graphics sandbox.
    @Test
    fun `A1 A2 and A5 across themes display modes and font scenarios`() {
        val scenarios = buildList {
            for (appTheme in AppTheme.entries) {
                for (display in PercentDisplay.entries) {
                    add(AppearanceScenario(
                        settings = state.settings.copy(appTheme = appTheme, percentDisplay = display),
                        suffix = "${appTheme.name.lowercase()}-${display.name.lowercase()}",
                    ))
                }
            }
            for (language in listOf(Locale.US, Locale.GERMANY)) {
                for ((textFont, figureFont) in listOf(
                    TextFont.ROBOTO to FigureFont.ROBOTO_MONO,
                    TextFont.FIGTREE to FigureFont.IBM_PLEX_MONO,
                    TextFont.IBM_PLEX_SANS to FigureFont.JETBRAINS_MONO,
                )) {
                    for (fontScale in listOf(1f, 1.5f)) {
                        add(AppearanceScenario(
                            settings = state.settings.copy(appTheme = AppTheme.PURE_BLACK, percentDisplay = PercentDisplay.LEFT,
                                textFont = textFont, figureFont = figureFont),
                            suffix = "fonts-${textFont.name.lowercase()}-${figureFont.name.lowercase()}-${language.language}-" +
                                if (fontScale == 1f) "default" else "large",
                            locale = language,
                            fontScale = fontScale,
                            checkFonts = true,
                        ))
                    }
                }
            }
        }
        val scenario = mutableStateOf(scenarios.first())
        val current = mutableStateOf<@Composable () -> Unit>({})
        val screen = mutableStateOf("A1-overview")
        compose.setContent {
            val row = scenario.value
            val baseContext = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val density = LocalDensity.current.density
            val configuration = remember(baseConfiguration, row.locale, row.fontScale) {
                Configuration(baseConfiguration).apply {
                    setLocale(row.locale)
                    fontScale = row.fontScale
                }
            }
            val context = remember(baseContext, configuration) {
                ContextThemeWrapper(baseContext, 0).apply { applyOverrideConfiguration(configuration) }
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(density, fontScale = row.fontScale),
            ) {
                UsageLimitsTheme(appTheme = row.settings.appTheme, textFont = row.settings.textFont, figureFont = row.settings.figureFont) {
                    CompositionLocalProvider(LocalMotionEnabled provides false) {
                        key(row, screen.value) { current.value() }
                    }
                }
            }
        }
        val originalLocale = Locale.getDefault()
        try {
            for (row in scenarios) {
                Locale.setDefault(row.locale)
                val resources = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                    setLocale(row.locale)
                }).resources
                for ((name, content) in appearanceScreens(row)) {
                    compose.runOnIdle {
                        scenario.value = row
                        screen.value = name
                        current.value = content
                    }
                    compose.waitForIdle()
                    captureScreen(name, row.suffix)
                    if (!row.checkFonts) continue
                    when (name) {
                        "A1-overview" -> {
                            fun figure(token: String) = com.usagelimits.ui.text.percentFigure(token, resources)
                            val full = assertTextFits(figure("100"))
                            val digitAdvance = full.getBoundingBox(0).width
                            val twoDigitAdvance = full.getLineRight(0) - full.getLineLeft(0) - digitAdvance
                            assertTextFits(figure("4"))
                            for (token in listOf("66", "29", "12", "48")) {
                                val layout = assertTextFits(figure(token))
                                val advance = layout.getLineRight(0) - layout.getLineLeft(0)
                                assertEquals("${row.suffix}: ${figure(token)} has the List figure's mono advance",
                                    twoDigitAdvance, advance, 0.1f)
                            }
                            // Larger text can put Credits below even the tall capture's fold.
                            val amount = CreditFormat.string(BigDecimal("18.40"), "USD", row.locale)
                            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(amount))
                            compose.waitForIdle()
                            if (row.fontScale == 1.5f) assertTextFits(amount)
                            captureScreen("A1-credits", row.suffix)
                        }
                        "A2-dials" -> {
                            for (token in listOf("100", "12")) {
                                assertTextFits(com.usagelimits.ui.text.percentFigure(token, resources))
                            }
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `English percentages draw distinct figure faces with equal cell advances`() {
        val font = mutableStateOf(FigureFont.ROBOTO_MONO)
        compose.setContent {
            UsageLimitsTheme(textFont = TextFont.FIGTREE, figureFont = font.value) {
                Column {
                    Text("61%", style = UsageType.FigureLarge)
                    Text("000", style = UsageType.FigureLarge)
                }
            }
        }

        val masks = FigureFont.entries.map { face ->
            compose.runOnIdle { font.value = face }
            val figure = assertTextFits("61%")
            assertEquals("$face three-cell advance", assertTextFits("000").getLineRight(0), figure.getLineRight(0), 0.1f)
            val ink = compose.runOnIdle {
                buildList {
                    Bitmap.createBitmap(figure.size.width, figure.size.height, Bitmap.Config.ARGB_8888).useOnce { bitmap ->
                        figure.multiParagraph.paint(ComposeCanvas(Canvas(bitmap)), color = Color.White)
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) >= 128) add(x to y)
                        }
                    }
                }
            }
            assertTrue("$face draws ink", ink.isNotEmpty())
            val left = ink.minOf { it.first }
            val top = ink.minOf { it.second }
            ink.map { (x, y) -> (x - left) to (y - top) }
        }
        assertEquals("every figure face draws distinct 61% glyphs", FigureFont.entries.size, masks.toSet().size)
    }

    private fun captureScreen(name: String, suffix: String) {
        val root = compose.activity.window.decorView
        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).useOnce {
            root.draw(Canvas(it))
            it.save("screen-$name-$suffix")
        }
    }

    private fun assertTextFits(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertEquals("one text layout for $text", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("$text stays on one line", 1, layout.lineCount)
        assertFalse("$text is cut off vertically", layout.didOverflowHeight)
        assertFalse("$text is ellipsized", layout.isLineEllipsized(0))
        assertTrue("$text exceeds its layout width ${layout.size.width}: ${layout.getLineRight(0)}",
            layout.getLineLeft(0) >= -0.5f && layout.getLineRight(0) <= layout.size.width + 0.5f)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$text has a visible size: $bounds", bounds.width > 0f && bounds.height > 0f)
        assertTrue("$text is inside the screen: $bounds in $root",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom)
        return layout
    }

    @Test fun `overview credits 03b`() = captureOverview03b("en")

    @Config(qualifiers = "+de")
    @Test fun `overview credits 03b German`() = captureOverview03b("de")

    @Test fun `screens light`() = captureAll("light")

    @Config(qualifiers = "+night")
    @Test fun `screens dark`() = captureAll("dark")

    // A1, C1 and C2 in German: the same screens, the app's German words.
    @Config(qualifiers = "+de")
    @Test fun `screens german`() = captureAll("de")

    @Config(fontScale = 1.6f)
    @Test fun `dials and settings at larger text`() = captureAll("en-large", dialScreens())

    @Config(qualifiers = "+de", fontScale = 1.6f)
    @Test fun `dials and settings in German at larger text`() = captureAll("de-large", dialScreens())

    // 05a: the Claude Code figure (CH-3), with motion off so every capture shows still poses.

    /** A second Claude account, 96 % left: Dancing, beside Work's Working (29 % left in its weekly window). */
    private val claudeSide = AccountUsage(
        account("claude-side", ProviderId.CLAUDE, "Side", "Pro"),
        UsageSnapshot("claude-side", NOW - 2 * 60_000, SnapshotStatus.OK, windows = listOf(
            window("5h", "5h limit", WindowCategory.FIVE_HOUR, 4.0, 18_000, 3 * HOUR),
            window("week", "Weekly", WindowCategory.WEEKLY, 2.0, 604_800, 5 * 24 * HOUR),
        )),
    )

    private fun claudeScreens(): List<Pair<String, @Composable () -> Unit>> {
        val twoClaudes = state.copy(accounts = listOf(claude, claudeSide, codex))
        val settings: @Composable (AppSettings) -> Unit = { settings ->
            SettingsScreen(twoClaudes.copy(settings = settings), onSyncIntervalChange = {}, onNotificationPermissionHandled = {},
                onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {}, onNotifyResetCredit = {},
                onNotifyAuthExpired = {}, onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {},
                onOverviewLayoutChange = {}, onPercentDisplayChange = {}, nowMs = NOW)
        }
        return listOf(
            "claude-overview-list" to {
                OverviewScreen(twoClaudes, nowMs = NOW, zone = utc, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                    onPercentDisplayChange = {})
            },
            "claude-overview-dials" to {
                OverviewScreen(twoClaudes.copy(settings = AppSettings(overviewLayout = OverviewLayout.DIALS)), nowMs = NOW,
                    zone = utc, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
            },
            "claude-detail" to {
                AccountDetailScreen(usage = claudeSide, nowMs = NOW, staleAfterMs = state.staleAfterMs,
                    percentDisplay = PercentDisplay.LEFT, resetInFlight = false, supportsResetCredits = false, onRefresh = {},
                    onConsumeResetCredit = {}, onRemove = {}, notificationsEnabled = true, onNotificationsChange = {})
            },
            "claude-resets" to { ResetsScreen(state = twoClaudes, nowMs = NOW) },
            "claude-settings" to { settings(AppSettings()) },
            "claude-settings-monochrome" to { settings(AppSettings(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode"))) },
            "claude-settings-preview-list" to { settings(AppSettings()) },
            "claude-settings-preview-dials" to { settings(AppSettings(overviewLayout = OverviewLayout.DIALS)) },
        )
    }

    /** Every mood's still at every slot size, in Color and in Monochrome, on the row surface. */
    @Composable
    private fun ClaudeStillSheet() {
        val slots = listOf(FigureSlot.DIAL_PREVIEW, FigureSlot.LIST_PREVIEW, FigureSlot.LIST_ROW, FigureSlot.DIAL, FigureSlot.DETAIL)
        Column(Modifier.background(UsageColors.Ground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((fillName, fill) in listOf("Color" to ClaudeCodeBrand, "Monochrome" to UsageColors.Ink)) {
                Text("$fillName · 16 / 20 / 28 / 34 / 36 dp", color = UsageColors.Muted)
                for (mood in ClaudeCodeMood.entries) {
                    Row(Modifier.background(UsageColors.Surface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mood.name.lowercase(), Modifier.width(72.dp), color = UsageColors.Ink)
                        for (slot in slots) {
                            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                                ClaudeCodeFigure(mood, fill, slot, Modifier.size(slot.size))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun `claude code figure stills and screens`() {
        val current = mutableStateOf<@Composable () -> Unit>({})
        val theme = mutableStateOf(AppTheme.PURE_BLACK)
        compose.setContent {
            UsageLimitsTheme(appTheme = theme.value) {
                CompositionLocalProvider(LocalMotionEnabled provides false) { current.value() }
            }
        }
        val cases = listOf<Pair<String, @Composable () -> Unit>>("claude-still-sheet" to { ClaudeStillSheet() }) + claudeScreens()
        // Light paper: the Monochrome figure takes the theme's ink and the "z" its muted text.
        val paper = cases.filter { (name, _) -> name in setOf("claude-still-sheet", "claude-overview-list", "claude-settings-monochrome") }
        for ((index, case) in (cases + paper).withIndex()) {
            val light = index >= cases.size
            theme.value = if (light) AppTheme.LIGHT_PAPER else AppTheme.PURE_BLACK
            val (name, content) = case
            current.value = { androidx.compose.runtime.key(name) { content() } }
            compose.waitForIdle()
            if (name.startsWith("claude-settings-preview")) {
                compose.onNode(hasScrollToNodeAction()).performScrollToKey("overview-heading")
            }
            captureScreen(case.first, theme.value.name.lowercase())
        }
    }
}

/** The Glance widgets through a real host view, as WidgetAppearanceRenderingTest does it, at 2x. */
@OptIn(ExperimentalGlanceApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignWidgetCaptureTest {

    @Before fun initializeApplication() {
        runBlocking { ApplicationProvider.getApplicationContext<UsageLimitsApp>().startupJob?.join() }
    }

    @After fun drain() {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
        } finally {
            resetTestWorkManager()
        }
    }

    private data class PlacedScene(
        val name: String,
        val size: DpSize,
        val singleAccount: Boolean = false,
        val metrics: WidgetMetrics = WidgetMetrics(),
        val fontScale: Float = 1f,
    )

    @Test fun `B1 B2 and B4 placed widgets across sizes panels languages and display modes`() = runTest(timeout = 10.minutes) {
        val app = ApplicationProvider.getApplicationContext<UsageLimitsApp>()
        val repository = app.container.repository
        val now = System.currentTimeMillis()
        val seeded = listOf(
            Triple(ProviderId.CLAUDE, "Work", listOf(34.0, 71.0)),
            Triple(ProviderId.CODEX, "Home", listOf(0.0, 96.0)),
            Triple(ProviderId.KIMI, "Side project", listOf(12.0, 52.0)),
        ).map { (provider, name, used) ->
            val account = repository.upsertFromLogin(provider, "capture-$name", "$name@example.invalid", name, "Pro", emptyMap())
            repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
                UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, used[0], 18_000, now + 2 * HOUR, false),
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, used[1], 604_800, now + 50 * HOUR, false),
            )))
            account.localId
        }
        listOf(
            Triple(CreditProviderId.OPENROUTER, "OpenRouter team", "18.40"),
            Triple(CreditProviderId.DEEPSEEK, "DeepSeek", "3.12"),
            Triple(CreditProviderId.FAL, "fal renders", "0.00"),
        ).forEachIndexed { i, (provider, label, amount) ->
            val id = "capture-credit-$i"
            app.container.creditRepository.upsert(
                CreditAccount(id, provider, label, "credit:$id", createdAt = now),
                CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(amount), total = BigDecimal(50))), fetchedAt = now),
            )
        }
        val placementMetrics = WidgetMetrics(70f, 131f, 132f, 66f)
        val scenes = mapOf(
            "CompactUsageWidget" to listOf(
                PlacedScene("four-tiles", DpSize(420.dp, 130.dp)),
                PlacedScene("summary", DpSize(280.dp, 130.dp)),
                PlacedScene("focus", DpSize(180.dp, 150.dp)),
            ),
            "DetailedUsageWidget" to listOf(
                PlacedScene("multi", DpSize(300.dp, 360.dp)),
                PlacedScene("compact-single", DpSize(180.dp, 110.dp), singleAccount = true),
                PlacedScene("resized-multi", DpSize(250.dp, 180.dp)),
            ),
            "MinimalUsageWidget" to listOf(
                PlacedScene("small", DpSize(180.dp, 110.dp)),
                PlacedScene("wide", DpSize(320.dp, 300.dp)),
                PlacedScene("tall", DpSize(180.dp, 360.dp)),
                PlacedScene("small-large-text", DpSize(180.dp, 110.dp), fontScale = 1.6f),
            ),
            "MiniRingsWidget" to listOf(
                PlacedScene("minimum", DpSize(56.dp, 56.dp)),
                PlacedScene("landscape", DpSize(256.dp, 58.dp), metrics = placementMetrics),
                PlacedScene("portrait", DpSize(62.dp, 254.dp), metrics = placementMetrics),
                PlacedScene("minimum-large-text", DpSize(56.dp, 56.dp), fontScale = 1.6f),
            ),
            "CreditsWidget" to listOf(
                PlacedScene("small", DpSize(110.dp, 110.dp)),
                PlacedScene("medium", DpSize(250.dp, 110.dp)),
                PlacedScene("large", DpSize(250.dp, 250.dp)),
            ),
        )
        val styles = mapOf(
            "charcoal" to WidgetStyle(),
            "transparent" to WidgetStyle(WidgetBackground(opacityPercent = 0)),
            "navy" to WidgetStyle(WidgetBackground(0xFF16213A.toInt(), 100)),
        )
        val manager = AppWidgetManager.getInstance(app)
        val glanceManager = GlanceAppWidgetManager(app)
        val originalMode = app.container.settingsStore.settings.first().percentDisplay
        val originalLocale = Locale.getDefault()
        // The exhaustive evidence matrix is opt-in; ordinary runs keep each kind, both usage
        // modes, real collection children, the 56dp Mini and all three responsive Credits sizes.
        val languages = if (captureDir == null) listOf(Locale.US) else listOf(Locale.US, Locale.GERMANY)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (language in languages) {
                Locale.setDefault(language)
                for ((widgetIndex, placed) in WidgetUpdater.allWidgets.withIndex()) {
                    val credits = placed.name == "CreditsWidget"
                    // Credits has neither a saved style nor a percent mode: one default panel
                    // and one mode cover it. Repeating Left/Used would produce identical images.
                    val widgetStyles = if (credits || captureDir == null) styles.entries.take(1) else styles.entries
                    val modes = if (credits) listOf(PercentDisplay.LEFT) else PercentDisplay.entries
                    val widgetScenes = scenes.getValue(placed.name).let {
                        if (captureDir == null && !credits) it.take(1) else it
                    }
                    for (mode in modes) {
                        app.container.settingsStore.setPercentDisplay(mode)
                        for ((styleIndex, entry) in widgetStyles.withIndex()) {
                            val (styleName, style) = entry
                            for (scene in widgetScenes) {
                                val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                                    setLocale(language)
                                    fontScale = scene.fontScale
                                })
                                val density = context.resources.displayMetrics.density
                                val selected = if (scene.singleAccount) seeded.take(1) else seeded
                                val config = WidgetConfigEntity(300 + widgetIndex * 10 + styleIndex, "CUSTOM", null, null, now,
                                    customAccountIdsJson = selected.joinToString(",", "[", "]") { "\"$it\"" },
                                    backgroundArgb = style.background.argb, backgroundOpacity = style.background.clampedOpacity,
                                    textTone = style.textTone.name, layoutMetricsJson = scene.metrics.toJson())
                                shadowOf(manager).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
                                    provider = ComponentName(app, placed.receiver)
                                })
                                app.container.widgetConfigDao.upsert(config)
                                val side = if (credits) "balance" else mode.name.lowercase()
                                val size = scene.size
                                val name = "widget-${placed.name}-${scene.name}-${size.width.value.toInt()}x${size.height.value.toInt()}-" +
                                    "$styleName-${language.language}-$side"
                                // first cancels and joins runComposition's session and registration.
                                // Draw while that session still owns its real collection adapter.
                                coroutineScope {
                                    placed.widget().runComposition(context, id = glanceManager.getGlanceIdBy(config.appWidgetId),
                                        sizes = listOf(size)).first { remote ->
                                        val host = AppWidgetHostView(context).apply { setAppWidget(config.appWidgetId, null) }
                                        val view = remote.apply(context, host)
                                        host.addView(view)
                                        remote.reapply(context, view)
                                        ShadowLooper.idleMainLooper()
                                        draw(view, (size.width.value * density).toInt(), (size.height.value * density).toInt())
                                            .useOnce { it.saveWidget(name, style.background.isTransparent) }
                                        assertCollectionChildren(view, placed.name, name, selected.size)
                                        true
                                    }
                                    assertTrue("$name leaves no running composition jobs", currentCoroutineContext().job.children.none())
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                app.container.settingsStore.setPercentDisplay(originalMode)
            } finally {
                Locale.setDefault(originalLocale)
                Dispatchers.resetMain()
            }
        }
    }

    private fun assertCollectionChildren(view: View, widget: String, name: String, accountCount: Int) {
        if (widget != "DetailedUsageWidget" && widget != "MinimalUsageWidget") return
        val collections = adapterViews(view)
        assertEquals("$name has its real account collection", 1, collections.size)
        val collection = collections.single()
        assertTrue("$name binds account rows", (collection.adapter?.count ?: 0) > 0)
        if (widget == "DetailedUsageWidget") {
            assertEquals("$name binds the selected accounts", accountCount, collection.adapter?.count)
        }
        assertTrue("$name lays out real collection children", collection.childCount > 0)
        assertTrue("$name draws the first account, not only an empty panel",
            textViews(collection).any { it.text.toString() == "Work" && it.width > 0 && it.height > 0 })
    }

    private fun adapterViews(view: View): List<AdapterView<*>> = buildList {
        if (view is AdapterView<*>) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(adapterViews(view.getChildAt(index)))
    }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    private fun Bitmap.saveWidget(name: String, transparent: Boolean) {
        if (captureDir == null) return
        if (!transparent) {
            save(name)
            return
        }
        for ((backdrop, color) in listOf("dark" to 0xFF242322.toInt(), "light" to 0xFFF3F0E8.toInt())) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).useOnce { frame ->
                val canvas = Canvas(frame)
                canvas.drawColor(color)
                canvas.drawBitmap(this, 0f, 0f, null)
                frame.save("$name-on-$backdrop")
            }
        }
    }

    private fun draw(view: View, width: Int, height: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
