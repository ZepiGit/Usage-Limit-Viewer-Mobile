package com.usagelimits.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import com.usagelimits.R
import com.usagelimits.core.di.AppContainer
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.credits.AddCreditScreen
import com.usagelimits.feature.credits.AddCreditViewModel
import com.usagelimits.feature.credits.CreditDetailScreen
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AccountsScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.accounts.AddAccountViewModel
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.ConstrainedContent
import com.usagelimits.ui.NavigationLayout
import com.usagelimits.ui.theme.AxisShift
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.axisIn
import com.usagelimits.ui.theme.axisOut
import com.usagelimits.ui.theme.barIn
import com.usagelimits.ui.theme.barOut
import com.usagelimits.ui.theme.railIn
import com.usagelimits.ui.theme.railOut
import com.usagelimits.ui.theme.swapIn
import com.usagelimits.ui.theme.swapOut
import com.usagelimits.ui.AppIcons

/**
 * How often relative times refresh.
 *
 * Countdowns are rendered at minute resolution, so a shorter tick would recompose without
 * changing a single character; a longer one would let "1m" sit on screen after it became "now".
 */
private const val TICK_INTERVAL_MS = 30_000L

private sealed class Destination(val route: String) {
    data object Overview : Destination("overview")
    data object Accounts : Destination("accounts")
    data object Resets : Destination("resets")
    data object Settings : Destination("settings")
    data object AddAccount : Destination("add-account")
    data object AddCredit : Destination("add-credit")
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
    TabItem(Destination.Accounts, R.string.tab_accounts, AppIcons.People),
    TabItem(Destination.Resets, R.string.tab_resets, AppIcons.Schedule),
    TabItem(Destination.Settings, R.string.tab_settings, Icons.Default.Settings),
)

private fun NavBackStackEntry.isTab() = TABS.any { it.destination.route == destination.route }

/** Both ends of the change are tabs: peers, with no deeper or back between them. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    initialState.isTab() && targetState.isTab()

/**
 * The bottom bar or the side rail, leaving and returning with the screens it belongs to.
 *
 * Nothing is emitted while it is hidden and at rest: the scaffold tells an empty bottom-bar slot
 * from a zero-height bar, and pads for the system navigation bar only when the slot is empty.
 */
@Composable
private fun NavigationChrome(
    routeKnown: Boolean,
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit,
) {
    if (!routeKnown) return
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    if (state.currentState || state.targetState) {
        AnimatedVisibility(state, enter = enter, exit = exit) { content() }
    }
}

/**
 * The app shell: four tabs plus two pushed routes.
 *
 * `nowMs` is read once per recomposition and threaded down rather than each countdown calling
 * the clock itself, so every relative time on screen is consistent with the others.
 */
@Composable
fun UsageLimitsNavigation(container: AppContainer, windowSizeClass: WindowSizeClass,
    requestedAccountId: String? = null, onAccountHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val viewModel: UsageViewModel =
        viewModel(factory = UsageViewModel.Factory(container, appContext))
    val state by viewModel.state.collectAsState()
    LaunchedEffect(requestedAccountId, state.accounts) {
        if (requestedAccountId != null && state.accounts.any { it.account.localId == requestedAccountId }) {
            navController.navigate(Destination.AccountDetail.of(requestedAccountId)) { launchSingleTop = true }
            onAccountHandled()
        }
    }
    val resetInFlight by viewModel.resetCreditInFlight.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Nav chrome is hidden on the pushed routes (add-account, account detail) so those
    // screens own the full window.
    val showNavigation = TABS.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.destination.route } == true
    }
    val navigationLayout = NavigationLayout.forWidth(windowSizeClass.widthSizeClass)
    val showBottomBar = showNavigation && navigationLayout == NavigationLayout.BOTTOM_BAR
    val showSideRail = showNavigation && navigationLayout == NavigationLayout.SIDE_RAIL
    // The first composition has no back stack entry yet. The chrome waits for one and then
    // appears at once, so a launch or a rotation does not play its entrance.
    val routeKnown = currentRoute != null
    val motion = UsageMotion.current
    val shift = with(LocalDensity.current) { AxisShift.roundToPx() }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            // Keep a single copy of each tab on the back stack and restore where the user
            // was when they return to it.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize().background(UsageColors.Ground)) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationChrome(routeKnown, showSideRail, motion.railIn(), motion.railOut()) {
                NavigationRail(containerColor = UsageColors.Ground) {
                    TABS.forEach { tab ->
                        val selected = currentRoute?.hierarchy
                            ?.any { it.route == tab.destination.route } == true
                        NavigationRailItem(
                            selected = selected,
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationChrome(routeKnown, showBottomBar, motion.barIn(shift), motion.barOut(shift)) {
                    NavigationBar(
                        containerColor = UsageColors.Ground,
                        tonalElevation = 0.dp,
                        // A 1px rule on the top edge, not a tone step or a shadow.
                        modifier = Modifier.drawBehind {
                            drawLine(UsageColors.Rule, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        },
                    ) {
                        TABS.forEach { tab ->
                            val selected = currentRoute?.hierarchy
                                ?.any { it.route == tab.destination.route } == true
                            NavigationBarItem(
                                selected = selected,
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
            // A plain System.currentTimeMillis() read is not a Compose state read, so with no
            // data change every countdown on screen froze at whatever the tab was composed with.
            // For an app made of countdowns that is visible within a minute.
            val nowMs by produceState(initialValue = System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(TICK_INTERVAL_MS)
                }
            }

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
                val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -shift else shift
                NavHost(
                    navController = navController,
                    startDestination = Destination.Overview.route,
                    enterTransition = { if (betweenTabs()) motion.swapIn() else motion.axisIn(forward) },
                    exitTransition = { if (betweenTabs()) motion.swapOut() else motion.axisOut(-forward) },
                    popEnterTransition = { if (betweenTabs()) motion.swapIn() else motion.axisIn(-forward) },
                    popExitTransition = { if (betweenTabs()) motion.swapOut() else motion.axisOut(forward) },
                ) {
                    composable(Destination.Overview.route) {
                        OverviewScreen(
                            state = state,
                            nowMs = nowMs,
                            onRefresh = viewModel::refresh,
                            onAccountClick = { navController.navigate(Destination.AccountDetail.of(it)) },
                            onAddAccount = { navController.navigate(Destination.AddAccount.route) },
                            onReorder = viewModel::reorderAccounts,
                            onPercentDisplayChange = viewModel::setPercentDisplay,
                            onCreditClick = { navController.navigate(Destination.CreditDetail.of(it)) },
                            onAddCredit = { navController.navigate(Destination.AddCredit.route) },
                        )
                    }

                    composable(Destination.Accounts.route) {
                        AccountsScreen(
                            state = state,
                            nowMs = nowMs,
                            onAccountClick = { navController.navigate(Destination.AccountDetail.of(it)) },
                            onAddAccount = { navController.navigate(Destination.AddAccount.route) },
                            onRefresh = viewModel::refresh,
                        )
                    }

                    composable(Destination.Resets.route) {
                        ResetsScreen(state = state, nowMs = nowMs)
                    }

                    composable(Destination.Settings.route) {
                        SettingsScreen(
                            state = state,
                            onSyncIntervalChange = viewModel::setSyncInterval,
                            onNotificationPermissionHandled = viewModel::recordNotificationPermissionHandled,
                            onProviderIconChange = viewModel::setProviderIcon,
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
                            onShowRenewal = viewModel::setShowRenewalTime,
                            onPercentDisplayChange = viewModel::setPercentDisplay,
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
            }
        }
        }
    LaunchOverlay()
    }
}
