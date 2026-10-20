package com.usagelimits.ui

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
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
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.credits.CreditDetailScreen
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.widget.WidgetBackground
import com.usagelimits.widget.WidgetStyle
import com.usagelimits.widget.WidgetUpdater
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        credit("fal", CreditProviderId.FAL, "fal renders", "0.00", "25", error = "The key was rejected (HTTP 401)."),
    )

    private val state = UsageUiState(accounts = listOf(claude, codex, kimi), settings = AppSettings(), credits = credits)

    private fun screens(): List<Pair<String, @Composable () -> Unit>> = listOf(
        "overview" to {
            OverviewScreen(state = state, nowMs = NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onReorder = {}, onCreditClick = {}, onAddCredit = {})
        },
        "overview-empty" to {
            OverviewScreen(state = UsageUiState(), nowMs = NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onReorder = {})
        },
        "account-detail" to {
            AccountDetailScreen(usage = claude, nowMs = NOW, staleAfterMs = state.staleAfterMs, resetInFlight = false,
                supportsResetCredits = false, onRefresh = {}, onConsumeResetCredit = {}, onRemove = {},
                notificationsEnabled = true, onNotificationsChange = {})
        },
        "account-detail-failed" to {
            AccountDetailScreen(usage = kimi, nowMs = NOW, staleAfterMs = state.staleAfterMs, resetInFlight = false,
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
            SettingsScreen(state = state, onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {}, onShowRenewal = {})
        },
        "credit-detail" to {
            CreditDetailScreen(entry = credits[0], nowMs = NOW, staleAfterMs = state.staleAfterMs,
                onSave = { _, _ -> }, onRefresh = {}, onRemove = {})
        },
    )

    private fun captureAll(mode: String) {
        val current = mutableStateOf<@Composable () -> Unit>({})
        compose.setContent { UsageLimitsTheme { current.value() } }
        for ((name, content) in screens()) {
            current.value = content
            compose.waitForIdle()
            // Not onRoot().captureToImage(): under Robolectric 4.16 + compose ui-test 1.7.6 its
            // forceRedraw never sees a frame and times out (tried sdk 34 and 28). Drawing the
            // decor view in software is what the widget captures below do, and it works.
            val root = compose.activity.window.decorView
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                .also { root.draw(Canvas(it)) }.save("screen-$name-$mode")
        }
    }

    @Test fun `screens light`() = captureAll("light")

    @Config(qualifiers = "+night")
    @Test fun `screens dark`() = captureAll("dark")
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

    @Test fun `widgets light and dark`() = runTest {
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
        val sizes = mapOf(
            "CompactUsageWidget" to listOf(DpSize(420.dp, 130.dp), DpSize(180.dp, 150.dp)),
            "DetailedUsageWidget" to listOf(DpSize(300.dp, 360.dp)),
            "MinimalUsageWidget" to listOf(DpSize(260.dp, 240.dp)),
            "MiniRingsWidget" to listOf(DpSize(62.dp, 180.dp)),
            "CreditsWidget" to listOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)),
        )
        val styles = mapOf(
            "charcoal" to WidgetStyle(),
            "white" to WidgetStyle(WidgetBackground(0xFFFFFFFF.toInt(), 100)),
        )
        val manager = AppWidgetManager.getInstance(app)
        val glanceManager = GlanceAppWidgetManager(app)
        val density = app.resources.displayMetrics.density
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for ((widgetIndex, placed) in WidgetUpdater.allWidgets.withIndex()) {
                // Credits ignores the saved style, so one panel is enough for it.
                val widgetStyles = if (placed.name == "CreditsWidget") styles.entries.take(1) else styles.entries
                for ((styleIndex, entry) in widgetStyles.withIndex()) {
                    val (styleName, style) = entry
                    val config = WidgetConfigEntity(300 + widgetIndex * 10 + styleIndex, "CUSTOM", null, null, now,
                        customAccountIdsJson = seeded.joinToString(",", "[", "]") { "\"$it\"" },
                        backgroundArgb = style.background.argb, backgroundOpacity = style.background.clampedOpacity,
                        textTone = style.textTone.name)
                    shadowOf(manager).addBoundWidget(config.appWidgetId, AppWidgetProviderInfo().apply {
                        provider = ComponentName(app, placed.receiver)
                    })
                    app.container.widgetConfigDao.upsert(config)
                    for (night in listOf(false, true)) {
                        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
                        for ((sizeIndex, size) in sizes.getValue(placed.name).withIndex()) {
                            placed.widget().runComposition(app, id = glanceManager.getGlanceIdBy(config.appWidgetId),
                                sizes = listOf(size)).first { remote ->
                                val host = AppWidgetHostView(app).apply { setAppWidget(config.appWidgetId, null) }
                                val view = remote.apply(app, host)
                                host.addView(view)
                                remote.reapply(app, view)
                                ShadowLooper.idleMainLooper()
                                draw(view, (size.width.value * density).toInt(), (size.height.value * density).toInt())
                                    .save("widget-${placed.name}-$styleName-${if (night) "dark" else "light"}-$sizeIndex")
                                true
                            }
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("+notnight")
            Dispatchers.resetMain()
        }
    }

    private fun draw(view: View, width: Int, height: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
