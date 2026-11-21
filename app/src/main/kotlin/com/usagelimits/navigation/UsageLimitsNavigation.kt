package com.usagelimits.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.usagelimits.R
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.credits.AddCreditScreen
import com.usagelimits.feature.credits.AddCreditViewModel
import com.usagelimits.feature.credits.CreditDetailScreen
import com.usagelimits.feature.credits.ManageCreditsScreen
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.accounts.AddAccountViewModel
import com.usagelimits.feature.overview.OverviewFilter
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.overview.nextOverviewBoundary
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.ConstrainedContent
import com.usagelimits.ui.NavigationLayout
import com.usagelimits.ui.theme.AxisShift
import com.usagelimits.ui.theme.LocalAppTheme
import com.usagelimits.ui.theme.LocalFigureFont
import com.usagelimits.ui.theme.LocalTextFont
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.axisIn
import com.usagelimits.ui.theme.axisOut
import com.usagelimits.ui.theme.peekIn
import com.usagelimits.ui.theme.peelOut
import com.usagelimits.ui.theme.swapIn
import com.usagelimits.ui.theme.swapOut
import com.usagelimits.ui.AppIcons

internal sealed class Destination(val route: String) {
    data object Overview : Destination("overview")
    data object Resets : Destination("resets")
    data object Settings : Destination("settings")
    data object AddAccount : Destination("add-account")
    data object AddCredit : Destination("add-credit")
    data object ManageCredits : Destination("manage-credits")
    data object CreditDetail : Destination("credit/{creditId}") {
        fun of(creditId: String) = "credit/$creditId"
    }
    data object AccountDetail : Destination("account/{accountId}") {
        fun of(accountId: String) = "account/$accountId"
    }
}

private data class TabItem(
    val destination: Destination,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabItem(Destination.Overview, R.string.tab_overview, Icons.Default.Home),
    TabItem(Destination.Resets, R.string.tab_resets, AppIcons.Schedule),
    TabItem(Destination.Settings, R.string.tab_settings, Icons.Default.Settings),
)

private fun NavBackStackEntry.isTab() = TABS.any { it.destination.route == destination.route }

/** Both ends of the change are tabs: peers, with no deeper or back between them. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    initialState.isTab() && targetState.isTab()

/**
 * Chrome that is on its way out or not yet in is a picture of the tabs, not the tabs: it takes
 * no tap and no focus, and a screen reader does not find it. Its screen is gone, or not there yet.
 */
private fun Modifier.inert(): Modifier = this
    .clearAndSetSemantics {}
    .focusProperties { canFocus = false }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** Where the chrome sits, and so which way it gives way. */
private enum class ChromeEdge {
    /** The bottom bar: shifts down by the distance the screens shift, keeping its height. */
    BOTTOM,

    /** The side rail: gives its width back, because the content is centred in what is left. */
    START,
}

/**
 * The bottom bar or the side rail, leaving and returning with the screens it belongs to.
 *
 * [active] selects the chrome for the current width, so a layout change does not animate.
 * A settled hidden bar emits nothing: Scaffold distinguishes an empty slot from a zero-height bar.
 * Retarget opacity and position from their current values so a reversal does not jump.
 * Controls are usable only from the end of entrance to the start of exit. With motion off,
 * visibility and interaction both follow the route immediately.
 */
@Composable
private fun NavigationChrome(
    active: Boolean,
    visible: Boolean,
    motion: UsageMotion,
    edge: ChromeEdge,
    shiftPx: Int,
    content: @Composable () -> Unit,
) {
    if (!active) return
    if (!motion.enabled) {
        if (visible) content()
        return
    }
    val opacity = remember { Animatable(if (visible) 1f else 0f) }
    // 0 in place, 1 out of the way.
    val away = remember { Animatable(if (visible) 0f else 1f) }
    var present by remember { mutableStateOf(visible) }
    var arrived by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) present = true else arrived = false
        coroutineScope {
            launch {
                when {
                    !visible -> opacity.animateTo(0f, motion.exit())
                    // A real entrance waits for the screen that is leaving; a return that was
                    // interrupted on its way out comes straight back with its position.
                    opacity.value == 0f -> opacity.animateTo(1f, motion.enterAfterExit())
                    else -> opacity.animateTo(1f, motion.move())
                }
            }
            launch { away.animateTo(if (visible) 0f else 1f, motion.move()) }
        }
        if (visible) arrived = true else present = false
    }
    if (!visible && !present) return

    val placed = when (edge) {
        ChromeEdge.BOTTOM -> Modifier.graphicsLayer {
            alpha = opacity.value
            translationY = away.value * shiftPx
        }
        ChromeEdge.START -> Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * (1f - away.value)).roundToInt()
                // Anchored to its inner edge, so it leaves towards the edge of the window.
                layout(width, placeable.height) { placeable.placeRelative(width - placeable.width, 0) }
            }
            .graphicsLayer { alpha = opacity.value }
    }
    Box(if (visible && arrived) placed else placed.inert()) { content() }
}

/**
 * The frame every route sits in: the tab chrome that fits the window's width, and the motion from
 * one route to the next. Apart from the routes' content, so a test can drive the chrome and the
 * transitions with stand-in screens.
 */
@Composable
internal fun NavigationShell(
    navController: NavHostController,
    layout: NavigationLayout,
    snackbarHost: @Composable () -> Unit = {},
    routes: NavGraphBuilder.() -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // Nav chrome is hidden on the pushed routes (add-account, account detail) so those
    // screens own the full window.
    val currentTab = TABS.firstOrNull { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.destination.route } == true
    }?.destination?.route
    val showNavigation = currentTab != null
    // The tab the chrome marks as selected. While the chrome leaves for a pushed screen it keeps
    // showing the tab it left from, instead of dropping its selection on the way out.
    // A plain holder, not state: it is written and read in the same composition.
    val lastTab = remember { Ref<String>() }
    if (currentTab != null) lastTab.value = currentTab
    val shownTab = lastTab.value
    // The first composition has no back stack entry yet. The chrome waits for one and then
    // appears at once, so a launch or a rotation does not play its entrance.
    val routeKnown = currentRoute != null
    val motion = UsageMotion.current
    val reducedBack = !motion.enabled && routeKnown && navController.previousBackStackEntry != null
    val shift = with(LocalDensity.current) { AxisShift.roundToPx() }
    val rule = UsageColors.Rule

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            // Keep a single copy of each tab on the back stack and restore where the user
            // was when they return to it.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationChrome(routeKnown && layout == NavigationLayout.SIDE_RAIL, showNavigation, motion, ChromeEdge.START, shift) {
            NavigationRail(containerColor = UsageColors.Ground) {
                TABS.forEach { tab ->
                    NavigationRailItem(
                        selected = shownTab == tab.destination.route,
                        onClick = { navigateToTab(tab.destination.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = UsageColors.Ink,
                            selectedTextColor = UsageColors.Ink,
                            indicatorColor = UsageColors.Raised,
                            unselectedIconColor = UsageColors.Faint,
                            unselectedTextColor = UsageColors.Faint,
                        ),
                    )
                }
            }
        }

        Scaffold(
            containerColor = UsageColors.Ground,
            snackbarHost = snackbarHost,
            bottomBar = {
                NavigationChrome(routeKnown && layout == NavigationLayout.BOTTOM_BAR, showNavigation, motion, ChromeEdge.BOTTOM, shift) {
                    NavigationBar(
                        containerColor = UsageColors.Ground,
                        tonalElevation = 0.dp,
                        // A 1px rule on the top edge, not a tone step or a shadow.
                        modifier = Modifier.drawBehind {
                            drawLine(rule, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        },
                    ) {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = shownTab == tab.destination.route,
                                onClick = { navigateToTab(tab.destination.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = UsageColors.Ink,
                                    selectedTextColor = UsageColors.Ink,
                                    indicatorColor = UsageColors.Raised,
                                    unselectedIconColor = UsageColors.Faint,
                                    unselectedTextColor = UsageColors.Faint,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            ConstrainedContent(
                modifier = Modifier
                    .fillMaxSize()
                    .background(UsageColors.Ground)
                    .padding(padding),
            ) {
                // The motion says what the change is. Tab to tab is two peers trading places: they
                // fade through in place. Anything else goes deeper or comes back, so the screens
                // also shift along the reading axis, forward for a push and backward for a pop.
                // (The 700 ms navigation-compose default reads as lag.)
                //
                // The back gesture scrubs through its own form of the pop (see `peekIn`), whichever
                // edge it starts from: the library's default for it (a scale and a spring fade) is
                // a third motion nobody chose.
                val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -shift else shift
                NavHost(
                    navController = navController,
                    startDestination = Destination.Overview.route,
                    enterTransition = { if (betweenTabs()) motion.swapIn() else motion.axisIn(forward) },
                    exitTransition = { if (betweenTabs()) motion.swapOut() else motion.axisOut(-forward) },
                    popEnterTransition = { if (betweenTabs()) motion.swapIn() else motion.axisIn(-forward) },
                    popExitTransition = { if (betweenTabs()) motion.swapOut() else motion.axisOut(forward) },
                    predictivePopEnterTransition = { motion.peekIn(if (betweenTabs()) 0 else -forward) },
                    predictivePopExitTransition = { motion.peelOut(if (betweenTabs()) 0 else forward) },
                    builder = routes,
                )
                // The later handler takes precedence over NavHost. Even 100% seek is not a
                // commit, so with motion off only handle completion, not gesture progress.
                BackHandler(enabled = reducedBack) {
                    navController.popBackStack()
                }
            }
        }
    }
}

/**
 * The app shell: Overview, Resets and Settings, plus the pushed routes (account and credit detail,
 * add account, add credit).
 *
 * One [ScreenClock] supplies the instant the shell passes to Overview, Resets and the account
 * detail, and the instant and zone Overview ranks, filters and heads its list for. Resets keeps
 * its own zone, and Settings reads the clock itself for its diagnostics.
 */
@Composable
fun UsageLimitsNavigation(container: AppContainer, windowSizeClass: WindowSizeClass,
    request: ExternalRequest? = null, onRequestHandled: (ExternalRequest) -> Unit = {},
    /** The wall clock the screens read; a test passes its own. */
    now: () -> Long = System::currentTimeMillis) {
    val navController = rememberNavController()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val viewModel: UsageViewModel =
        viewModel(factory = UsageViewModel.Factory(container, appContext))
    val state by viewModel.state.collectAsState()
    // A status icon or widget row names an account: the link waits for the store's first answer
    // and opens the detail over Overview whichever tab is showing; an account that is gone gets its
    // own detail state with a way back. A usage alert or a whole-widget tap opens Overview, over
    // whatever was showing. Each request is handled once.
    val route = (request as? ExternalRequest.OpenAccount)?.let {
        AccountRoute.resolve(it.accountId, state.accounts.map { usage -> usage.account.localId }.takeIf { state.loaded })
    }
    // The graph exists once the NavHost inside the scaffold has composed: on a cold start the
    // request waits for the host's first back stack entry before it navigates or is confirmed.
    val hostReady = navController.currentBackStackEntryAsState().value != null
    LaunchedEffect(request, route, hostReady) {
        if (!hostReady) return@LaunchedEffect
        when (request) {
            null -> return@LaunchedEffect
            is ExternalRequest.OpenOverview ->
                // A cold launch already shows Overview; a warm one may be on a detail or a tab.
                if (navController.currentDestination?.route != Destination.Overview.route) {
                    navController.navigate(Destination.Overview.route) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
            is ExternalRequest.OpenAccount -> {
                val accountId = when (val target = route ?: return@LaunchedEffect) {
                    is AccountRoute.Detail -> target.accountId
                    is AccountRoute.Unavailable -> target.accountId
                }
                navController.navigate(Destination.AccountDetail.of(accountId)) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }
        }
        onRequestHandled(request)
    }
    val resetInFlight by viewModel.resetCreditInFlight.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // A plain System.currentTimeMillis() read is not a Compose state read, so with no data change
    // every countdown on screen froze at whatever the tab was composed with. The clock moves at the
    // next tick, at Overview's next boundary (a reset falling due, a reading turning stale, the
    // next local day) when that is sooner, on returning to the app and on a time or zone change.
    val clock = rememberScreenClock(now) { at, zone ->
        val current = viewModel.state.value
        var next = current.accounts.nextOverviewBoundary(at, zone, current.staleAfterMs)
        for (entry in current.credits) {
            val snapshot = entry.snapshot ?: continue
            if (snapshot.balances.isEmpty()) continue
            val stale = snapshot.fetchedAt + current.staleAfterMs
            if (stale > at && stale < next) next = stale
        }
        next
    }
    val nowMs = clock.nowMs
    // New readings and a changed staleness threshold can move the next boundary.
    LaunchedEffect(state.accounts, state.credits, state.staleAfterMs) { clock.reread() }
    // Overview's filter is view state: never stored, and All again when another tab is chosen.
    // An account detail opened from the list, and the way back to it, keep the filter.
    var overviewFilter by rememberSaveable { mutableStateOf(OverviewFilter.ALL) }
    val activeEntry by navController.currentBackStackEntryAsState()
    val currentRoute = activeEntry?.destination?.route
    val dials = state.settings.overviewLayout == OverviewLayout.DIALS
    LaunchedEffect(currentRoute, dials) {
        if (dials || (currentRoute != Destination.Overview.route && TABS.any { it.destination.route == currentRoute })) {
            overviewFilter = OverviewFilter.ALL
        }
    }

    Box(Modifier.fillMaxSize().background(UsageColors.Ground)) {
        NavigationShell(
            navController, NavigationLayout.forWidth(windowSizeClass.widthSizeClass),
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) {
            composable(Destination.Overview.route) {
                OverviewScreen(
                    state = state,
                    nowMs = nowMs,
                    zone = clock.zoneId,
                    filter = overviewFilter,
                    onFilterChange = { overviewFilter = it },
                    onRefresh = viewModel::refresh,
                    onAccountClick = { navController.navigate(Destination.AccountDetail.of(it)) },
                    onAddAccount = { navController.navigate(Destination.AddAccount.route) },
                    onPercentDisplayChange = viewModel::setPercentDisplay,
                    onCreditClick = { navController.navigate(Destination.CreditDetail.of(it)) },
                    onAddCredit = { navController.navigate(Destination.AddCredit.route) },
                    onManageCredits = { navController.navigate(Destination.ManageCredits.route) },
                    isCurrentDestination = activeEntry?.destination?.route == Destination.Overview.route,
                )
            }

            composable(Destination.Resets.route) {
                ResetsScreen(state = state, nowMs = nowMs)
            }

            composable(Destination.Settings.route) {
                val appTheme = LocalAppTheme.current
                val textFont = LocalTextFont.current
                val figureFont = LocalFigureFont.current
                val settings = state.settings
                SettingsScreen(
                    state = if (settings.appTheme == appTheme && settings.textFont == textFont && settings.figureFont == figureFont) state
                    else state.copy(settings = settings.copy(appTheme = appTheme, textFont = textFont, figureFont = figureFont)),
                    onSyncIntervalChange = viewModel::setSyncInterval,
                    onNotificationPermissionHandled = viewModel::recordNotificationPermissionHandled,
                    onProviderIconChange = viewModel::setProviderIcon,
                    onAppThemeChange = viewModel::setAppTheme,
                    onTextFontChange = viewModel::setTextFont,
                    onFigureFontChange = viewModel::setFigureFont,
                    onStatusIconsChange = viewModel::setStatusIcons,
                    onPinnedChange = viewModel::setPinned,
                    onNotificationsAllowed = viewModel::notificationsAllowed,
                    onNotifyBelow20 = viewModel::setNotifyBelow20,
                    onNotifyBelow10 = viewModel::setNotifyBelow10,
                    onNotifyExhausted = viewModel::setNotifyExhausted,
                    onNotifyResetCredit = viewModel::setNotifyResetCredit,
                    onNotifyAuthExpired = viewModel::setNotifyAuthExpired,
                    onNotifyResetApproaching = viewModel::setNotifyResetApproaching,
                    onNotifyCreditExpiring = viewModel::setNotifyCreditExpiring,
                    onShowTier = viewModel::setShowSubscriptionTier,
                    onOverviewLayoutChange = viewModel::setOverviewLayout,
                    onPercentDisplayChange = viewModel::setPercentDisplay,
                    onOverviewCreditLayoutChange = viewModel::setOverviewCreditLayout,
                    onOverviewShowFirstChange = viewModel::setOverviewShowFirst,
                    onOverviewShowCreditsChange = viewModel::setOverviewShowCredits,
                    onManageCredits = { navController.navigate(Destination.ManageCredits.route) },
                )
            }

            composable(Destination.ManageCredits.route) {
                ManageCreditsScreen(
                    credits = state.credits,
                    nowMs = nowMs,
                    staleAfterMs = state.staleAfterMs,
                    onAdd = { navController.navigate(Destination.AddCredit.route) },
                    onSelect = { navController.navigate(Destination.CreditDetail.of(it)) },
                    onRetry = viewModel::refresh,
                )
            }

            composable(Destination.AddAccount.route) {
                val addViewModel: AddAccountViewModel =
                    viewModel(factory = AddAccountViewModel.Factory(container))
                val addState by addViewModel.state.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current

                AddAccountScreen(
                    state = addState,
                    providers = addViewModel.availableProviders(),
                    onStart = { addViewModel.startLogin(context, it) },
                    onStartWithKey = { addViewModel.startLogin(context, it, withKey = true) },
                    onStartWithDeviceCode = { addViewModel.startLogin(context, it, withDeviceCode = true) },
                    onChooseRegion = { provider, region -> addViewModel.chooseRegion(context, provider, region) },
                    onSubmitApiKey = addViewModel::submitApiKey,
                    onCancel = {
                        addViewModel.cancel()
                        navController.popBackStack()
                    },
                    onDone = {
                        addViewModel.reset()
                        navController.popBackStack()
                    },
                )
            }

            composable(Destination.AddCredit.route) {
                val addViewModel: AddCreditViewModel =
                    viewModel(factory = AddCreditViewModel.Factory(container, appContext))
                val addState by addViewModel.state.collectAsState()
                AddCreditScreen(
                    state = addState,
                    onSubmit = addViewModel::submit,
                    onCancel = {
                        addViewModel.reset()
                        navController.popBackStack()
                    },
                    onDone = {
                        addViewModel.reset()
                        navController.popBackStack()
                    },
                )
            }

            composable(Destination.CreditDetail.route) { entry ->
                val creditId = entry.arguments?.getString("creditId")
                CreditDetailScreen(
                    entry = state.credits.firstOrNull { it.id == creditId },
                    nowMs = nowMs,
                    staleAfterMs = state.staleAfterMs,
                    onSave = { label, threshold ->
                        creditId?.let { viewModel.updateCredit(it, label, threshold) }
                        navController.popBackStack()
                    },
                    onRefresh = viewModel::refresh,
                    onRemove = {
                        creditId?.let(viewModel::removeCredit)
                        navController.popBackStack()
                    },
                )
            }

            composable(Destination.AccountDetail.route) { entry ->
                val accountId = entry.arguments?.getString("accountId")
                val usage = state.accounts.firstOrNull { it.account.localId == accountId }
                val supportsCredits = usage?.account?.provider
                    ?.let { container.providerRegistry.forId(it)?.supportsResetCredits }
                    ?: false

                AccountDetailScreen(
                    usage = usage,
                    loaded = state.loaded,
                    onBack = { navController.popBackStack() },
                    nowMs = nowMs,
                    staleAfterMs = state.staleAfterMs,
                    percentDisplay = state.settings.percentDisplay,
                    resetInFlight = resetInFlight == accountId,
                supportsResetCredits = supportsCredits,
                onReconnect = { navController.navigate(Destination.AddAccount.route) },
                    onRefresh = { accountId?.let(viewModel::refreshAccount) },
                    onConsumeResetCredit = { accountId?.let(viewModel::consumeResetCredit) },
                    onRemove = {
                        accountId?.let(viewModel::removeAccount)
                        navController.popBackStack()
                    },
                    notificationsEnabled =
                        accountId !in state.settings.mutedAccountIds,
                    onNotificationsChange = { enabled ->
                        accountId?.let { viewModel.setAccountNotifications(it, enabled) }
                    },
                )
            }
        }
        LaunchOverlay()
    }
}
