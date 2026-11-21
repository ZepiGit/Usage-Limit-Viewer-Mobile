package com.usagelimits.feature.overview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.readingValidity
import com.usagelimits.core.model.title
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.AppIcons
import com.usagelimits.ui.ProviderLogo
import com.usagelimits.ui.components.EmptyBox
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.SkeletonBar
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.FadingFigure
import com.usagelimits.ui.components.FadingLabel
import com.usagelimits.ui.components.segmentLegends
import com.usagelimits.ui.components.ConflictingAvailabilityNote
import com.usagelimits.ui.components.SegmentLegend
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.percentFlipLabelRes
import com.usagelimits.ui.percentSpokenClauseRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.text.storedMessageText
import com.usagelimits.ui.text.windowLabelRes
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.UsageType
import com.usagelimits.ui.theme.itemMotion
import java.time.ZoneId
import java.util.Locale

/** The gap between Overview's blocks; the List rows inside one card touch. */
private val BlockGap = 12.dp
private val CardRadius = 8.dp

/**
 * Overview: a shared header and rank, a filterable List or unfiltered Dials, then Credits.
 *
 * The ranking, the filters and the header come from [overviewAt] at [nowMs] in [zone], so the
 * screen and the shared vectors cannot disagree. With no accounts the screen is C1: the fixed
 * brand mark and both add paths, no chips. Until the store has answered it is a skeleton, and a
 * filter that matches nothing says so inside the card; the three are never confused.
 */
@Composable
fun OverviewScreen(
    state: UsageUiState,
    nowMs: Long,
    onRefresh: () -> Unit,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
    onPercentDisplayChange: (PercentDisplay) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    filter: OverviewFilter = OverviewFilter.ALL,
    onFilterChange: (OverviewFilter) -> Unit = {},
    onCreditClick: (String) -> Unit = {},
    onAddCredit: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Content arriving for the first time replaces the skeleton in one cut. It is not a list
    // change, and its fade-in would leave the list blank for the delay, right after launch.
    // Items take the list motion from the composition after that.
    var contentShown by remember { mutableStateOf(false) }
    SideEffect { if (state.loaded) contentShown = true }
    val listMotion = if (contentShown) UsageMotion.current else UsageMotion.Reduced

    val dials = state.settings.overviewLayout == OverviewLayout.DIALS
    val projection = state.accounts.overviewAt(if (dials) OverviewFilter.ALL else filter, nowMs, zone, state.staleAfterMs)
    val hasAccounts = state.loaded && state.accounts.isNotEmpty()
    val lines = if (dials) emptyMap() else listWindowLines(projection.entries, state, nowMs, zone)
    val columns = if (dials) null else rememberGridColumns(lines.values)

    BoxWithConstraints(modifier.fillMaxSize().background(UsageColors.Ground)) {
        val dialColumns = dialColumnCount(maxWidth - 32.dp, LocalDensity.current.fontScale)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "header") {
                OverviewHeader(state, projection, nowMs, zone, showModeChip = hasAccounts,
                    onRefresh = onRefresh, onPercentDisplayChange = onPercentDisplayChange)
            }

            // Until the first emission `accounts` is empty only because nothing has arrived, so the
            // final layout is drawn in grey rather than flashing the empty state.
            if (!state.loaded) {
                item(key = "loading") { ListSkeleton(Modifier.padding(top = BlockGap)) }
                return@LazyColumn
            }

            if (state.accounts.isEmpty()) {
                item(key = "empty") { EmptyOverview(onAddAccount, onAddCredit, itemMotion(listMotion).padding(top = BlockGap)) }
            } else if (dials) {
                overviewDials(projection.entries, state.settings, nowMs, zone, state.staleAfterMs, dialColumns,
                    listMotion, onAccountClick, onAddAccount)
            } else {
                item(key = "filters") {
                    FilterChips(filter, projection.attentionCount, onFilterChange, itemMotion(listMotion).padding(top = 4.dp))
                }
                accountList(projection.entries, state, nowMs, lines, checkNotNull(columns), listMotion, onRefresh, onAccountClick)
                item(key = "caption") {
                    Text(stringResource(R.string.overview_headline_caption), itemMotion(listMotion).padding(top = BlockGap),
                        style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
                }
                item(key = "add-account") {
                    OutlineButton(stringResource(R.string.overview_add_account), onAddAccount,
                        itemMotion(listMotion).padding(top = BlockGap).fillMaxWidth(),
                        leading = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp)) })
                }
            }

            // Prepaid balances sit below the usage accounts: a different kind of number (money left,
            // no windows, no reset), so they are not mixed into the ranked list above.
            item(key = "credits-header") {
                SectionHeader(title = stringResource(R.string.overview_section_credits), modifier = itemMotion(listMotion))
            }
            if (state.credits.isEmpty()) {
                item(key = "credits-empty") {
                    EmptyBox(stringResource(R.string.overview_no_credits), itemMotion(listMotion).padding(top = 8.dp))
                }
            }
            items(items = state.credits, key = { "credit-${it.id}" }) { entry ->
                com.usagelimits.feature.credits.CreditCard(
                    entry = entry, nowMs = nowMs, staleAfterMs = state.staleAfterMs,
                    modifier = itemMotion(listMotion).padding(top = 8.dp),
                    onClick = { onCreditClick(entry.id) },
                    onRetry = onRefresh,
                )
            }
            item(key = "add-credit") {
                AddButton(stringResource(R.string.overview_add_credit), stringResource(R.string.overview_add_credit_hint),
                    onAddCredit, itemMotion(listMotion).padding(top = 8.dp))
            }
            if (dials && hasAccounts) {
                item(key = "dials-caption") {
                    Text(stringResource(R.string.overview_dials_caption), itemMotion(listMotion).padding(top = BlockGap),
                        style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
                }
            }
        }
    }
}

private fun dialColumnCount(width: Dp, fontScale: Float): Int =
    if (width >= 280.dp && fontScale < 1.4f) 2 else 1

@Composable
internal fun OverviewLayoutPreview(accounts: List<AccountUsage>, settings: AppSettings, nowMs: Long, zone: ZoneId) {
    val state = UsageUiState(accounts = accounts, settings = settings)
    val entries = accounts.overviewAt(OverviewFilter.ALL, nowMs, zone, state.staleAfterMs).entries
    if (settings.overviewLayout == OverviewLayout.DIALS) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = dialColumnCount(maxWidth, LocalDensity.current.fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in entries.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (entry in row) {
                            key(entry.usage.account.localId) {
                                OverviewDialTile(entry, settings.percentDisplay, nowMs, zone, state.staleAfterMs,
                                    Modifier.weight(1f), compact = true)
                            }
                        }
                    }
                }
            }
        }
    } else {
        val lines = listWindowLines(entries, state, nowMs, zone)
        val columns = rememberGridColumns(lines.values, compact = true)
        for ((index, entry) in entries.withIndex()) {
            if (index > 0) RuleDivider()
            key(entry.usage.account.localId) {
                OverviewAccountRow(entry, state, nowMs, lines.getValue(entry.usage.account.localId), columns,
                    MaterialTheme.shapes.medium, compact = true)
            }
        }
    }
}

@Composable
private fun listWindowLines(entries: List<OverviewEntry>, state: UsageUiState, nowMs: Long, zone: ZoneId) =
    entries.associate { entry ->
        entry.usage.account.localId to key(entry.usage.account.localId) { windowLines(entry, state, nowMs, zone) }
    }

/**
 * The List card. Each row is its own lazy item, so a row that moves in the rank takes the list's
 * item motion; together they draw one card: the first rounds the top, the last the bottom.
 */
private fun LazyListScope.accountList(
    entries: List<OverviewEntry>,
    state: UsageUiState,
    nowMs: Long,
    lines: Map<String, List<WindowLine>>,
    columns: GridColumns,
    listMotion: UsageMotion,
    onRefresh: () -> Unit,
    onAccountClick: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        item(key = "no-match") {
            Box(
                itemMotion(listMotion).padding(top = BlockGap).fillMaxWidth().heightIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.medium).background(UsageColors.Surface).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(stringResource(R.string.overview_no_match), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
            }
        }
        return
    }
    itemsIndexed(entries, key = { _, entry -> entry.usage.account.localId }) { index, entry ->
        val first = index == 0
        val last = index == entries.lastIndex
        val shape = RoundedCornerShape(
            topStart = if (first) CardRadius else 0.dp, topEnd = if (first) CardRadius else 0.dp,
            bottomStart = if (last) CardRadius else 0.dp, bottomEnd = if (last) CardRadius else 0.dp,
        )
        Column(itemMotion(listMotion).padding(top = if (first) BlockGap else 0.dp)) {
            if (!first) Box(Modifier.fillMaxWidth().background(UsageColors.Surface).padding(start = 16.dp, end = 12.dp)) { RuleDivider() }
            val id = entry.usage.account.localId
            OverviewAccountRow(entry, state, nowMs, lines.getValue(id), columns, shape, onRefresh) { onAccountClick(id) }
        }
    }
}

/** A full-width secondary button with its one-line hint beneath: the "add" row of a section. */
@Composable
fun AddButton(label: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlineButton(label, onClick, Modifier.fillMaxWidth(),
            leading = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) })
        Text(hint, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
    }
}

@Composable
private fun OverviewHeader(
    state: UsageUiState,
    projection: OverviewProjection,
    nowMs: Long,
    zone: ZoneId,
    showModeChip: Boolean,
    onRefresh: () -> Unit,
    onPercentDisplayChange: (PercentDisplay) -> Unit,
) {
    val subtitle = when {
        state.isRefreshing -> stringResource(R.string.overview_refreshing)
        !state.loaded -> stringResource(R.string.overview_loading_usage)
        state.accounts.isEmpty() -> stringResource(R.string.overview_no_accounts_yet)
        else -> headerSubtitle(projection, nowMs, zone)
    }
    // The actions wrap under the title when a large font leaves no room beside it.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).widthIn(min = 160.dp).align(Alignment.CenterVertically)) {
            Text(stringResource(R.string.app_name), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge, color = UsageColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
        }
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            if (showModeChip) ModeChip(state.settings.percentDisplay, onPercentDisplayChange)
            RefreshAction(state.isRefreshing, onRefresh)
        }
    }
}

/**
 * The header's words: the claim (attention or room), then the next reset; either may be missing,
 * and with neither there is no subtitle. The reset uses the app's absolute stamp, in [zone].
 */
@Composable
private fun headerSubtitle(projection: OverviewProjection, nowMs: Long, zone: ZoneId): String? {
    val header = projection.header
    val claim = when (header.kind) {
        OverviewHeaderKind.ATTENTION ->
            pluralStringResource(R.plurals.overview_need_attention, projection.attentionCount, projection.attentionCount)
        OverviewHeaderKind.ROOM -> stringResource(R.string.overview_all_have_room)
        OverviewHeaderKind.RESET_ONLY, OverviewHeaderKind.NONE -> null
    }
    val texts = rememberCountdownTexts()
    val stamp = header.resetAt?.let { Countdown.absoluteResetStamp(it, nowMs, zone, currentLocale(), texts) }
    return when {
        claim != null && stamp != null -> stringResource(R.string.overview_claim_next_reset, claim, stamp)
        stamp != null -> stringResource(R.string.overview_next_reset, stamp)
        else -> claim
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * The display chip: what the figures count now ("% left"). One focus stop whose label says what a
 * tap switches to; touch, keyboard and screen reader reach the same action. It writes the one
 * stored choice Settings writes too.
 */
@Composable
private fun ModeChip(mode: PercentDisplay, onPercentDisplayChange: (PercentDisplay) -> Unit) {
    val flipTo = if (mode == PercentDisplay.USED) PercentDisplay.LEFT else PercentDisplay.USED
    val flipLabel = stringResource(percentFlipLabelRes(mode))
    val shown = stringResource(if (mode == PercentDisplay.USED) R.string.overview_mode_used else R.string.overview_mode_left)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(interaction, indication = null, role = Role.Button) { onPercentDisplayChange(flipTo) }
            .clearAndSetSemantics {
                contentDescription = flipLabel
                stateDescription = shown
                role = Role.Button
                onClick(label = flipLabel) { onPercentDisplayChange(flipTo); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 32.dp).clip(MaterialTheme.shapes.small)
                .border(BorderStroke(1.dp, UsageColors.Control), MaterialTheme.shapes.small)
                .indication(interaction, ripple())
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // In step with the figures: the old side's word leaves with the old figures.
            FadingLabel(shown, MaterialTheme.typography.labelMedium, UsageColors.Muted)
        }
    }
}

@Composable
private fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            // The 48dp square takes the press, corners included; the highlight alone is round,
            // as an icon action's is.
            .clickable(interaction, indication = null, enabled = !refreshing, role = Role.Button, onClick = onRefresh)
            .clip(CircleShape)
            .indication(interaction, ripple()),
        contentAlignment = Alignment.Center,
    ) {
        // The icon gives way to the spinner in the same slot, and back, rather than cutting.
        Crossfade(refreshing, animationSpec = UsageMotion.current.stateChange(), label = "refresh") { busy ->
            if (busy) {
                Spinner()
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.overview_refresh_usage),
                    tint = UsageColors.Muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** All · Needs attention · n · Resets today: single select, wrapping on narrow widths. */
@Composable
private fun FilterChips(selected: OverviewFilter, attentionCount: Int, onSelect: (OverviewFilter) -> Unit, modifier: Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in OverviewFilter.entries) {
            val label = when (option) {
                OverviewFilter.ALL -> stringResource(R.string.overview_filter_all)
                OverviewFilter.NEEDS_ATTENTION -> stringResource(R.string.overview_filter_attention)
                OverviewFilter.RESETS_TODAY -> stringResource(R.string.overview_filter_resets_today)
            }
            FilterChip(label, count = attentionCount.takeIf { option == OverviewFilter.NEEDS_ATTENTION && it > 0 },
                selected = selected == option) { onSelect(option) }
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val motion = UsageMotion.current
    // Read only while drawing: the fill fades without recomposing the chip.
    val selection by animateFloatAsState(if (selected) 1f else 0f, motion.stateChange(), label = "chip")
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            // 8dp above and below keep a 48dp target around the 32dp chip.
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(shape)
                .drawChipFill { if (motion.enabled) selection else if (selected) 1f else 0f }
                .border(1.dp, if (selected) UsageColors.Muted else UsageColors.Rule, shape)
                .indication(interaction, ripple())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
            val color = if (selected) UsageColors.Ink else UsageColors.Muted
            Text(label, style = style, color = color)
            if (count != null) Text(count.toString(), style = style.copy(fontFeatureSettings = "tnum"), color = color)
        }
    }
}

private fun Modifier.drawChipFill(alpha: () -> Float) =
    this.then(Modifier.drawBehind { drawRect(UsageColors.Raised, alpha = alpha()) })

/** Whether a row's figures are greyed (an old or failed reading) and whether its sign-in expired. */
private fun rowState(entry: OverviewEntry, state: UsageUiState, nowMs: Long): Pair<Boolean, Boolean> {
    val snapshot = entry.usage.snapshot
    val severity = snapshot?.severityAt(nowMs, state.staleAfterMs) ?: Severity.STALE
    return (severity == Severity.STALE || severity == Severity.ERROR) to
        (snapshot?.connectionStatus == ConnectionStatus.RECONNECT_REQUIRED)
}

/**
 * One account in the List card: logo, title, mark and chevron, then one line per window, the
 * headline emphasised. An expired sign-in keeps its last figures in faint and says so; a failed,
 * partial or paused reading keeps its stored message; an old or failed account greys its figures.
 */
@Composable
private fun OverviewAccountRow(
    entry: OverviewEntry,
    state: UsageUiState,
    nowMs: Long,
    lines: List<WindowLine>,
    columns: GridColumns,
    shape: Shape,
    onRetry: (() -> Unit)? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val usage = entry.usage
    val snapshot = usage.snapshot
    val (_, expired) = rowState(entry, state, nowMs)
    val severity = snapshot?.severityAt(nowMs, state.staleAfterMs) ?: Severity.STALE
    val windows = snapshot?.windows.orEmpty()
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            // The whole row takes the press; its highlight is drawn inside the card's shape.
            .then(if (onClick == null) Modifier else Modifier.clickable(interaction, indication = null, onClick = onClick))
            .clip(shape)
            .background(UsageColors.Surface)
            .then(if (onClick == null) Modifier else Modifier.indication(interaction, ripple()))
            .padding(start = if (compact) 8.dp else 16.dp, end = if (compact) 8.dp else 12.dp,
                top = if (compact) 8.dp else 14.dp, bottom = if (compact) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
    ) {
        val logoSize = if (compact) 20.dp else 28.dp
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = logoSize)) {
            ProviderLogo(usage.account.provider, Modifier.size(logoSize))
            Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
            // Title and mark take the free width, so the chevron ends every row at the same x.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usage.account.title(state.settings.showSubscriptionTier),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (compact) 13.sp else 15.sp, lineHeight = if (compact) 18.sp else 20.sp),
                    color = UsageColors.Ink, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                AccountMark(usage, expired, if (compact) 7.dp else 8.dp)
            }
            if (onClick != null) {
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = UsageColors.Faint, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(start = if (compact) 28.dp else 38.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!compact) {
                // The age of the reading belongs in detail, not on the List row (package 016).
                Text(usage.account.maskedEmail ?: usage.account.label,
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (windows.isEmpty()) {
                Text(snapshot?.errorMessage?.let { storedMessageText(it) } ?: stringResource(R.string.overview_no_usage_data),
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            } else {
                WindowGrid(lines, columns)
            }
            if (expired) {
                Text(stringResource(R.string.overview_sign_in_expired),
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.NegativeText)
            }
            // A failed, partial or paused reading keeps the message it was stored with, worded by
            // its status; the expired line above already says what the sign-in message would.
            val message = snapshot?.errorMessage
                ?.takeIf { windows.isNotEmpty() && !(expired && it == NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE) }
                ?.let { stored ->
                    val text = storedMessageText(stored)
                    when (snapshot.status) {
                        SnapshotStatus.OK -> text
                        SnapshotStatus.PARTIAL -> stringResource(R.string.error_partial_update, text)
                        SnapshotStatus.FAILED -> stringResource(R.string.error_showing_last_known, text)
                    }
                }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
            if (!snapshot?.restrictedWindows.isNullOrEmpty()) {
                Text(stringResource(R.string.restricted_windows, snapshot!!.restrictedWindows.joinToString { it.label }),
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
            // Lack of fresh data is not an expired sign-in: an old or unreadable account says which.
            // A stored message already says why; the bare word would repeat it.
            if (!expired && message == null && windows.isNotEmpty() && severity in setOf(Severity.STALE, Severity.ERROR, Severity.UNKNOWN)) {
                Text(stringResource(SeverityPalette.labelRes(severity)), style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
            val credits = snapshot?.heldResetCredits ?: 0
            if (credits > 0) {
                Text("${stringResource(R.string.reset_credits_title)} · ${stringResource(R.string.reset_credits_available_count, credits)}",
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
            if (snapshot?.status == SnapshotStatus.FAILED && !expired && onRetry != null) {
                TextAction(stringResource(R.string.action_retry), onRetry, color = UsageColors.Muted)
            }
        }
    }
}

/**
 * The 8dp account mark: the worst raw window, an expired sign-in overriding it. Above 50% left no
 * mark; above 20 a hollow amber ring; above 0 filled amber; exhausted or expired filled red. A
 * scoped pool running dry reads as limited, not as the whole account exhausted. It speaks its state.
 */
@Composable
private fun AccountMark(usage: AccountUsage, expired: Boolean, size: Dp) {
    val snapshot = usage.snapshot
    val raw = snapshot?.windows.orEmpty().map { it.severity }.filter { it != Severity.UNKNOWN }.maxOrNull() ?: Severity.UNKNOWN
    val mark = when {
        expired -> Severity.ERROR
        // The scope rule, not the snapshot's overall verdict, which a failed read overrides: a
        // retained exhausted model window beside an open global one is limited, not exhausted.
        raw == Severity.EXHAUSTED && snapshot?.quotaSeverity == Severity.LIMITED -> Severity.LIMITED
        else -> raw
    }
    if (mark == Severity.HEALTHY || mark == Severity.UNKNOWN) return
    val label = if (expired) stringResource(R.string.status_reconnect) else stringResource(SeverityPalette.labelRes(mark))
    val color = SeverityPalette.accent(mark)
    Box(
        Modifier.size(size).semantics { contentDescription = label }
            .then(if (mark == Severity.MEDIUM) Modifier.border(1.5.dp, color, CircleShape) else Modifier.clip(CircleShape).background(color)),
    )
}

/** What one window line shows, worked out once so the grid can size its columns from every line. */
private class WindowLine(
    val window: UsageWindow,
    val label: String,
    val figure: String,
    val figureColor: androidx.compose.ui.graphics.Color,
    val stamp: String?,
    val presentation: com.usagelimits.core.model.WindowPresentation,
    val emphasised: Boolean,
    val spoken: String,
    /** The reported breakdown, one legend per segment; empty when the provider gives none. */
    val legends: List<String>,
    /** The reading is current: not expired, failed, old or past its reset. */
    val current: Boolean,
    /** The figure in the other display mode, so the column fits both and a flip keeps the layout. */
    val otherFigure: String,
) {
    val id: String get() = window.id
    val group: String? get() = window.group
}

/**
 * Every window of one account as grid lines: label | flexible 4dp bar | figure | reset stamp.
 *
 * The columns are shared by every line of every row in the card ([GridColumns]): the label column
 * is as wide as the widest label (58dp at least), the figure column as the widest figure (42dp at
 * least) and the stamp column as the widest stamp (66dp at least), and the bar takes what is left,
 * 24dp at least. So within the card every bar starts and every figure ends at the same place,
 * whether a stamp reads "14:05" or "Thu 02:49". When that does not fit the width, the windows
 * stack instead of cutting a column (see [WindowLines]).
 */
/** The figure cell's type: mono 13sp, as the hand-off's grid. */
private val GridFigureStyle = UsageType.Figure.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** The window lines of one row, worked out once, so the card can size its columns from every row. */
@Composable
private fun windowLines(entry: OverviewEntry, state: UsageUiState, nowMs: Long, zone: ZoneId): List<WindowLine> {
    val snapshot = entry.usage.snapshot ?: return emptyList()
    val (stale, expired) = rowState(entry, state, nowMs)
    val texts = rememberCountdownTexts()
    val locale = currentLocale()
    val spokenSide = stringResource(percentSpokenClauseRes(state.settings.percentDisplay))
    val staleWord = stringResource(SeverityPalette.labelRes(Severity.STALE))
    val conflictNote = stringResource(R.string.usage_conflicting_availability)
    return snapshot.windows.map { window ->
        val validity = when {
            stale || expired -> ReadingValidity.STALE
            else -> window.readingValidity(stale = false, fetchedAt = snapshot.fetchedAt, nowMs = nowMs)
        }
        val severity = if (validity == ReadingValidity.CURRENT) window.severity else Severity.STALE
        val p = window.presentation(validity, state.settings.percentDisplay)
        val figure = percentFigureText(p.token)
        val other = if (state.settings.percentDisplay == PercentDisplay.LEFT) PercentDisplay.USED else PercentDisplay.LEFT
        val otherFigure = percentFigureText(window.presentation(validity, other).token)
        val label = windowLabel(window)
        val headline = window.id == entry.headlineWindowId
        val stamp = if (validity == ReadingValidity.RESET_DUE) texts.resetDue()
            else window.resetAt?.let { Countdown.absoluteResetStamp(it, nowMs, zone, locale, texts) }
        // A pool's windows can share a label ("Weekly" in two pools), so the pool is spoken with it.
        val reading = listOfNotNull(window.group, "$label, $figure $spokenSide").joinToString(", ")
        val legends = segmentLegends(window)
        val spoken = (listOfNotNull(
            if (headline) stringResource(R.string.overview_headline_spoken, reading) else reading,
            stamp,
            staleWord.takeIf { validity == ReadingValidity.STALE && !expired },
        ) + legends + listOfNotNull(conflictNote.takeIf { window.hasConflictingAvailability })).joinToString(", ")
        WindowLine(
            window = window, label = label, figure = figure, legends = legends, otherFigure = otherFigure,
            current = validity == ReadingValidity.CURRENT && !expired,
            figureColor = when {
                !p.hasFigure || expired -> UsageColors.Faint
                !headline -> UsageColors.Muted
                else -> SeverityPalette.figureColor(severity)
            },
            stamp = stamp, presentation = p, emphasised = headline && !expired, spoken = spoken,
        )
    }
}

/**
 * The widths, in px, of the widest label, figure and stamp across every row of the card. Each
 * row's grid takes at least these, so bars start and figures end in one column card-wide.
 */
private class GridColumns(
    val label: Int, val figure: Int, val stamp: Int, val compact: Boolean,
    val labelStyle: TextStyle, val figureStyle: TextStyle,
) {
    val gap = if (compact) 8.dp else 10.dp
}

@Composable
private fun rememberGridColumns(lines: Collection<List<WindowLine>>, compact: Boolean = false): GridColumns {
    val measurer = rememberTextMeasurer()
    val stampStyle = MaterialTheme.typography.bodySmall
    val labelStyle = if (compact) stampStyle.copy(fontSize = 11.sp, lineHeight = 14.sp) else stampStyle
    val figureStyle = if (compact) GridFigureStyle.copy(fontSize = 11.sp, lineHeight = 14.sp) else GridFigureStyle
    fun widest(texts: List<String>, style: TextStyle) =
        texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
    val all = lines.flatten()
    val density = LocalDensity.current
    return with(density) {
        GridColumns(
            label = maxOf((if (compact) 50.dp else 58.dp).roundToPx(),
                widest(all.map { it.label }, labelStyle.copy(fontWeight = FontWeight.Medium))),
            // Both sides' figures fit, so a mode flip cannot change the columns.
            figure = maxOf((if (compact) 34.dp else 42.dp).roundToPx(),
                widest(all.flatMap { listOf(it.figure, it.otherFigure) }, figureStyle)),
            stamp = if (compact) 0 else maxOf(66.dp.roundToPx(), widest(all.mapNotNull { it.stamp }, stampStyle)),
            compact = compact, labelStyle = labelStyle, figureStyle = figureStyle,
        )
    }
}

@Composable
private fun WindowGrid(lines: List<WindowLine>, columns: GridColumns) {
    // Ungrouped windows first, as the account's own limits; then each pool under its name, so two
    // pools whose windows share a label ("Weekly") do not look alike.
    val groups = lines.groupBy { it.group }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups[null]?.let { WindowLines(it, columns) }
        for ((group, pool) in groups) {
            if (group == null) continue
            Text(group, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium, color = UsageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            WindowLines(pool, columns)
        }
    }
}

/**
 * Window lines in the card's shared columns, each followed by its breakdown and a blocked-limit
 * note when it has them. When the four columns do not fit (a narrow window, a large font) every
 * window stacks instead: label and figure on one line, the bar, then the stamp, all readable.
 */
@Composable
private fun WindowLines(lines: List<WindowLine>, columns: GridColumns) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val fits = with(density) {
            val gaps = if (columns.compact) 2 else 3
            columns.label + 24.dp.roundToPx() + columns.figure + columns.stamp +
                gaps * columns.gap.roundToPx() <= constraints.maxWidth
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in lines) {
                key(line.id) {
                    if (fits) GridLine(line, columns) else StackedLine(line, columns)
                    if (line.legends.isNotEmpty()) {
                        SegmentLegend(line.window, line.legends, Modifier.clearAndSetSemantics {}, current = line.current)
                    }
                    if (line.window.hasConflictingAvailability) ConflictingAvailabilityNote(Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

/** The label: it carries the whole line for a screen reader; the other cells are silent. */
@Composable
private fun LineLabel(line: WindowLine, columns: GridColumns, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(line.label, modifier.semantics(mergeDescendants = true) { contentDescription = line.spoken },
        style = columns.labelStyle,
        fontWeight = if (line.emphasised) FontWeight.Medium else FontWeight.Normal,
        color = if (line.emphasised) UsageColors.Ink else UsageColors.Faint,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * The quota bar, always from the window's presentation: it follows the display mode and the
 * reading's validity. Off the headline it recedes, its fill at 40 % over the track. A reported
 * breakdown is not drawn here; it stays as the legend under the line (the detail draws its bar).
 */
@Composable
private fun LineBar(line: WindowLine, columns: GridColumns, modifier: Modifier = Modifier) {
    UsageBar(line.presentation, modifier.clearAndSetSemantics {}.alpha(if (line.emphasised) 1f else 0.4f),
        height = if (columns.compact) 3.dp else 4.dp)
}

/**
 * The figure: hidden from a screen reader (the label speaks the line) but still drawn as text, and
 * it fades through like every other figure. A pure mode change fades with the chip, including a
 * blocked window whose figure is "—" in one mode and "100%" in the other: keyed on the reading,
 * the node survives the flip. A new reading that gains or loses its figure is a different node and
 * replaces the old one at once.
 *
 * What the figure's fade is keyed on, from the reading and not from the display mode:
 * - [FigureFade.MEASURED]: the window has a figure, whether or not it is blocked;
 * - [FigureFade.BLOCKED]: blocked and without a measurement, so one mode shows "—" and the other "100%";
 * - [FigureFade.UNKNOWN]: neither.
 *
 * A pure mode change stays in one state and fades with the chip. A reading that gains or loses its
 * figure changes state, blocked or not, and the figure is replaced at once.
 */
internal enum class FigureFade { MEASURED, BLOCKED, UNKNOWN }

internal fun figureFade(hasMeasurement: Boolean, blocked: Boolean): FigureFade = when {
    hasMeasurement -> FigureFade.MEASURED
    blocked -> FigureFade.BLOCKED
    else -> FigureFade.UNKNOWN
}

@Composable
private fun LineFigure(line: WindowLine, columns: GridColumns) {
    key(figureFade(line.window.remainingPercent != null, line.presentation.exhausted)) {
        FadingFigure(line.figure, line.figureColor, columns.figureStyle, SilentCell)
    }
}

/**
 * A cell a screen reader does not reach, because the label's line already says it: its text
 * stays in its own node, hidden, and is not merged into the pressable row's description.
 */
private val SilentCell = Modifier.semantics(mergeDescendants = true) { hideFromAccessibility() }

/** The reset stamp: drawn as text, silent, as the label's line speaks it. */
@Composable
private fun LineStamp(line: WindowLine, modifier: Modifier = Modifier, align: TextAlign = TextAlign.End) {
    Text(line.stamp ?: "", modifier.then(SilentCell), style = MaterialTheme.typography.bodySmall,
        color = UsageColors.Faint, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One window as the hand-off's grid line: label | bar | figure | stamp, in the card's columns. */
@Composable
private fun GridLine(line: WindowLine, columns: GridColumns) {
    Layout(content = {
        LineLabel(line, columns)
        LineBar(line, columns)
        LineFigure(line, columns)
        if (!columns.compact) LineStamp(line)
    }) { measurables, constraints ->
        val gap = columns.gap.roundToPx()
        val width = constraints.maxWidth
        val gaps = if (columns.compact) 2 else 3
        val barWidth = width - columns.label - columns.figure - columns.stamp - gaps * gap
        val label = measurables[0].measure(Constraints.fixedWidth(columns.label))
        val bar = measurables[1].measure(Constraints.fixedWidth(barWidth))
        // A figure measures its text, not the column; then its right edge can stay fixed.
        val figure = measurables[2].measure(Constraints(maxWidth = columns.figure))
        val stamp = measurables.getOrNull(3)?.measure(Constraints.fixedWidth(columns.stamp))
        val h = maxOf(label.height, bar.height, figure.height, stamp?.height ?: 0)
        layout(width, h) {
            label.placeRelative(0, (h - label.height) / 2)
            bar.placeRelative(columns.label + gap, (h - bar.height) / 2)
            figure.placeRelative(columns.label + barWidth + 2 * gap + columns.figure - figure.width,
                (h - figure.height) / 2)
            stamp?.placeRelative(width - columns.stamp, (h - stamp.height) / 2)
        }
    }
}

/** One window stacked, when the columns do not fit: label and figure, the bar, then the stamp. */
@Composable
private fun StackedLine(line: WindowLine, columns: GridColumns) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columns.gap), verticalAlignment = Alignment.CenterVertically) {
            LineLabel(line, columns, Modifier.weight(1f), maxLines = 2)
            LineFigure(line, columns)
        }
        LineBar(line, columns, Modifier.fillMaxWidth())
        if (!columns.compact && line.stamp != null) LineStamp(line, Modifier.fillMaxWidth(), align = TextAlign.Start)
    }
}

@Composable
internal fun windowLabel(window: UsageWindow): String {
    val appLabel = windowLabelRes(window.label, window.category, window.scope)
    return when {
        window.scope != UsageScope.GLOBAL -> window.label
        appLabel != null -> stringResource(appLabel)
        window.group != null && window.category == WindowCategory.FIVE_HOUR -> stringResource(R.string.window_label_five_hour)
        window.group != null && window.category == WindowCategory.WEEKLY -> stringResource(R.string.window_label_weekly)
        window.group != null && window.category == WindowCategory.MONTHLY -> stringResource(R.string.window_label_monthly)
        else -> window.label
    }
}

/**
 * C1: no accounts yet. The fixed default brand mark, never the alternate launcher icon a person
 * chose, then both ways to start. No chips: there is nothing to filter or count.
 */
@Composable
private fun EmptyOverview(onAddAccount: () -> Unit, onAddCredit: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The 192dp transparent foreground in a 128dp slot: its safe-zone margin overlaps the edges.
        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                modifier = Modifier.requiredSize(192.dp))
        }
        Text(stringResource(R.string.overview_empty_title), Modifier.padding(top = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp), color = UsageColors.Ink)
        Text(stringResource(R.string.overview_empty_body), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        Button(
            onClick = onAddAccount,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = UsageColors.Ink, contentColor = UsageColors.OnInk),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.overview_add_account), style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
            onClick = onAddCredit,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, UsageColors.Control),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = UsageColors.Ink),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(stringResource(R.string.overview_track_credit), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The List card at its real size, in grey, while the store has not answered. */
@Composable
private fun ListSkeleton(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.overview_loading_usage)
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(UsageColors.Surface)
            .semantics { contentDescription = loading }
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(2) { index ->
            if (index > 0) RuleDivider(Modifier.padding(vertical = 4.dp))
            SkeletonBar(16.dp, 0.4f)
            repeat(2) {
                Row(Modifier.padding(start = 38.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBar(12.dp, 0.15f)
                    UsageBar(null, Severity.UNKNOWN, Modifier.weight(1f), height = 4.dp)
                }
            }
        }
    }
}
