package com.usagelimits.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.fillMaxHeight
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.usagelimits.MainActivity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.percentLabel
import com.usagelimits.core.time.Countdown
import kotlinx.coroutines.flow.first

/**
 * The style of the widget being composed: its panel and the ink derived from it.
 *
 * Every colour a widget draws comes from here, never from a constant. Glance runs in the
 * launcher's process and cannot read the app's Compose theme, and the panel is the user's
 * per-widget choice, so the palette has to travel with the composition. See [WidgetStyle]
 * for how the ink follows the panel.
 */
internal val LocalWidgetStyle = staticCompositionLocalOf { WidgetStyle() }

/**
 * A usage bar.
 *
 * Glance's own LinearProgressIndicator fills the width it is given, so the bar is correct at
 * every launcher grid size. The previous hand-rolled version took a fixed dp width, which was
 * wider than a tile actually gets: the fill was clipped, and anything above roughly half
 * remaining painted as a full bar — a confidently wrong number on the home screen.
 *
 * The fill is the data hue, grey when stale; a failed, unknown or exhausted row is the bare track.
 */
@Composable
internal fun UsageBar(row: WidgetRow, modifier: GlanceModifier = GlanceModifier, validity: Severity? = null, height: Dp = 4.dp) {
    val style = LocalWidgetStyle.current
    val fill = style.bar(row, validity)
    val fraction = if (fill == null) 0f else ((row.remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()

    LinearProgressIndicator(
        progress = fraction,
        modifier = modifier.height(height),
        color = ColorProvider(fill ?: style.track),
        backgroundColor = ColorProvider(style.track),
    )
}

private fun percentText(row: WidgetRow): String = percentLabel(row.remainingPercent)

/** Text on a widget: sizes from the scale (11, 12, 14, 16, 22), figures in the system mono. */
internal fun widgetText(color: Color, size: Int, weight: FontWeight = FontWeight.Normal, mono: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = weight,
    fontFamily = if (mono) FontFamily.Monospace else null,
)

/** The panel's corner: the launcher's own radius where the system states it, 16dp before API 31. */
internal fun GlanceModifier.panelCorner(): GlanceModifier =
    if (android.os.Build.VERSION.SDK_INT >= 31) cornerRadius(android.R.dimen.system_app_widget_background_radius)
    else cornerRadius(16.dp)

/** A 6dp severity dot. */
@Composable
internal fun SeverityDot(color: Color, size: Dp = 6.dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).background(color)) {}
}

/** A 1dp hairline in the track colour, between tiles. */
@Composable
private fun TileDivider(height: Dp = 36.dp) {
    Box(GlanceModifier.width(1.dp).height(height).background(LocalWidgetStyle.current.track)) {}
}

/** "As of 14:50", or nothing when the widget has never been fed. */
private fun asOf(updatedAt: Long?): String? =
    updatedAt?.let { Countdown.asOfLabel(it, nowMs = System.currentTimeMillis()) }

/**
 * The refresh control both list widgets carry: a bare glyph in a touch target.
 *
 * The glyph is tinted with the widget's ink rather than shipped white: on a light panel a
 * white arrow is invisible.
 */
@Composable
internal fun RefreshChip(touchSize: Dp) {
    val style = LocalWidgetStyle.current
    Box(
        modifier = GlanceModifier
            .size(touchSize)
            .clickable(actionRunCallback<RefreshWidgetAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(com.usagelimits.R.drawable.ic_refresh),
            "Refresh usage",
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(ColorProvider(style.textSecondary)),
        )
    }
}

/**
 * The compact 1×4 widget: a glanceable row of headline numbers.
 *
 * The launcher may supply any width from the declared 180 dp up, and four labelled tiles
 * cannot survive the narrow end — so the content is chosen by the width actually available
 * (see [WidgetLayout.compactLayout]): the full four tiles when they fit, two metrics when two
 * fit, and one focused readout below that. Every layout names the account its numbers come
 * from where the width allows: the headline belongs to the account leading the snapshot, and
 * when that leadership legitimately changes — a closest-resets or most-critical scope follows
 * the data — a tile with no name on it makes the change look like the content swapped at
 * random.
 */
class CompactUsageWidget : GlanceAppWidget() {

    // Exact rather than Responsive: the launcher reports the real size, and the tiles share
    // whatever width that is, so the widget stays correct on tablets and unfolded foldables.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val views = WidgetUpdater.observe(context, id)
        val initial = views.first()
        provideContent {
            val view by views.collectAsState(initial)
            val snapshot = view.snapshot
            val style = view.style
            val width = LocalSize.current.width.value
            val fontScale = androidx.glance.LocalContext.current.resources.configuration.fontScale
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetStyle provides style) {
                    val root = GlanceModifier
                        .fillMaxSize()
                        .panelCorner()
                        .background(style.background.color)
                        .padding(10.dp)
                        .clickable(actionStartActivity<MainActivity>())
                    when (WidgetLayout.compactLayout(width, fontScale)) {
                        WidgetLayout.CompactLayout.FOUR_TILES -> FourTileRow(root, snapshot, style)
                        WidgetLayout.CompactLayout.SUMMARY -> TwoMetricRow(root, snapshot, style)
                        WidgetLayout.CompactLayout.FOCUS -> FocusReadout(root, snapshot, style)
                    }
                }
            }
        }
    }

    /** The full row: 5h, the long horizon, next reset, overall status, refresh. */
    @Composable
    private fun FourTileRow(root: GlanceModifier, snapshot: WidgetSnapshot, style: WidgetStyle) {
        Row(modifier = root, verticalAlignment = Alignment.CenterVertically) {
            Tile(
                label = snapshot.headlineShort?.label ?: "5h limit",
                value = snapshot.headlineShort?.let(::percentText) ?: "—",
                row = snapshot.headlineShort,
                validity = snapshot.leadValidity,
                showOwner = true,
                owner = snapshot.accounts.firstOrNull()?.let { it.accountLabel ?: it.title },
                modifier = GlanceModifier.defaultWeight(),
            )
            TileDivider()
            Tile(
                // The row already knows what it is. Hardcoding "Weekly" here
                // labelled a monthly credit bucket as a week: the user paced their
                // spending against a reset days away that was actually a month
                // away, while the larger widget beside it, using the row's own
                // label, said "Monthly".
                label = snapshot.headlineLong?.label ?: "Weekly",
                value = snapshot.headlineLong?.let(::percentText) ?: "—",
                row = snapshot.headlineLong,
                validity = snapshot.leadValidity,
                modifier = GlanceModifier.defaultWeight(),
            )
            TileDivider()
            Tile(
                // A wall-clock instant rather than a countdown: a widget recomposes
                // only when its worker runs, so "in 20m" rendered half an hour ago
                // is not stale but wrong — the limit has already reset.
                label = "Next reset",
                value = snapshot.nextResetAt
                    ?.let {
                        Countdown.absoluteResetLabel(it, System.currentTimeMillis())
                            ?.removePrefix("Resets ")
                    }
                    ?: "—",
                row = null,
                // One step down from the percentage tiles so "4:50 PM" stays whole at 420dp.
                valueSize = 14,
                modifier = GlanceModifier.defaultWeight(),
            )
            TileDivider()
            Tile(
                label = snapshot.selectionStatusLabel,
                value = statusWord(snapshot.overallSeverity),
                row = null,
                modifier = GlanceModifier.defaultWeight(),
                dot = style.accent(snapshot.overallSeverity),
                warning = if (snapshot.hasPartialUpdate) "Sync issue" else null,
            )
            // No weight: the four tiles share the width and this takes what it
            // needs, rather than a fifth of the row for one glyph.
            RefreshChip(touchSize = 40.dp)
        }
    }

    /** Two metrics when the width fits two tiles: the 5h headline and the overall status. */
    @Composable
    private fun TwoMetricRow(root: GlanceModifier, snapshot: WidgetSnapshot, style: WidgetStyle) {
        val row = snapshot.headlineShort ?: snapshot.headlineLong
        Row(modifier = root, verticalAlignment = Alignment.CenterVertically) {
            Tile(
                label = row?.label ?: "Remaining",
                value = row?.let(::percentText) ?: "—",
                row = row,
                validity = snapshot.leadValidity,
                showOwner = true,
                owner = snapshot.accounts.firstOrNull()?.let { it.accountLabel ?: it.title },
                modifier = GlanceModifier.defaultWeight(),
            )
            TileDivider()
            Tile(
                label = snapshot.selectionStatusLabel,
                value = statusWord(snapshot.overallSeverity),
                row = null,
                modifier = GlanceModifier.defaultWeight(),
                dot = style.accent(snapshot.overallSeverity),
                warning = if (snapshot.hasPartialUpdate) "Sync issue" else null,
            )
            RefreshChip(touchSize = 40.dp)
        }
    }

    /** One focused readout: whose numbers, the 5h value with its bar, reset and status. */
    @Composable
    private fun FocusReadout(root: GlanceModifier, snapshot: WidgetSnapshot, style: WidgetStyle) {
        val lead = snapshot.accounts.firstOrNull()
        val row = snapshot.headlineShort ?: snapshot.headlineLong
        Column(modifier = root) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lead?.let { it.accountLabel ?: it.title } ?: "Usage Limits",
                    style = widgetText(style.textPrimary, 14, FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                RefreshChip(touchSize = 32.dp)
            }
            Text(
                text = row?.label ?: "Remaining",
                style = widgetText(style.textSecondary, 11),
                maxLines = 1,
            )
            val value = row?.let(::percentText) ?: "—"
            Text(
                text = value,
                style = if (value == "—") widgetText(style.textSecondary, 12)
                else widgetText((snapshot.leadValidity ?: row?.validity)?.let(style::textColor)
                    ?: row?.severity?.let(style::textColor) ?: style.textPrimary, 22, FontWeight.Medium, mono = true),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
            UsageBar(row ?: WidgetRow("5h limit", com.usagelimits.core.model.WindowCategory.FIVE_HOUR, null, null, Severity.STALE),
                GlanceModifier.fillMaxWidth(), validity = snapshot.leadValidity)
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    snapshot.nextResetAt
                        ?.let { Countdown.absoluteResetLabel(it, System.currentTimeMillis())?.removePrefix("Resets ") },
                    "${snapshot.selectionStatusLabel}: ${statusWord(snapshot.overallSeverity)}",
                ).joinToString(" · ").ifEmpty { "—" },
                style = widgetText(style.textSecondary, 11),
                maxLines = 1,
            )
            if (snapshot.hasPartialUpdate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityDot(WidgetStyle.Amber)
                    Spacer(GlanceModifier.width(4.dp))
                    Text("Sync issue", style = widgetText(style.textPrimary, 11))
                }
            }
        }
    }

    /**
     * One tile: a quiet label over a figure, with a 3dp bar for a quota row. Every tile reserves
     * the same owner line and bar slot, so the four figures share a baseline whatever they carry.
     */
    @Composable
    private fun Tile(
        label: String,
        value: String,
        row: WidgetRow?,
        modifier: GlanceModifier = GlanceModifier,
        dot: Color? = null,
        validity: Severity? = null,
        showOwner: Boolean = false,
        owner: String? = null,
        warning: String? = null,
        valueSize: Int = 16,
    ) {
        val style = LocalWidgetStyle.current
        // The account's validity first, then the row's own: a passed reset makes the number
        // as untrustworthy as a stale fetch would.
        val effectiveValidity = validity ?: row?.validity
        Column(modifier = modifier.padding(horizontal = 10.dp)) {
            if (showOwner && owner != null) {
                Text(
                    text = owner,
                    style = widgetText(style.textPrimary, 11, FontWeight.Medium),
                    maxLines = 1,
                )
            } else {
                Spacer(GlanceModifier.height(14.dp))
            }
            Text(
                text = label,
                style = widgetText(style.textSecondary, 11),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            when {
                // A word with a dot: the status. Severity is the dot, not a coloured word.
                dot != null -> Row(GlanceModifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeverityDot(dot)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(value, style = widgetText(style.textPrimary, 12), maxLines = 1)
                }
                // A missing value is a quiet dash, not a figure-sized one.
                value == "—" -> Text(value, style = widgetText(style.textSecondary, 12), maxLines = 1, modifier = GlanceModifier.height(22.dp))
                else -> Text(
                    text = value,
                    // Validity outranks decoration here too: the value colour follows the
                    // account's data state, not just the quota severity.
                    style = widgetText(
                        effectiveValidity?.let(style::textColor) ?: row?.let { style.barText(it) } ?: style.textPrimary,
                        valueSize, FontWeight.Medium, mono = true,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.height(22.dp),
                )
            }
            Spacer(GlanceModifier.height(3.dp))
            if (row != null) UsageBar(row, GlanceModifier.fillMaxWidth(), validity = effectiveValidity, height = 3.dp)
            else Spacer(GlanceModifier.height(3.dp))
            if (warning != null) {
                Text(warning, style = widgetText(style.textPrimary, 11), maxLines = 1)
            }
        }
    }

    private fun statusWord(severity: Severity) = when (severity) {
        Severity.HEALTHY -> "OK"
        Severity.MEDIUM -> "Fair"
        Severity.LOW -> "Low"
        Severity.EXHAUSTED -> "Out"
        Severity.STALE -> "Stale"
        Severity.ERROR -> "Error"
        Severity.UNKNOWN -> "Unknown"
        Severity.LIMITED -> "Limited"
    }

}

/**
 * The larger 2×4 widget: a header plus one block per account, each with its 5h and weekly bars.
 *
 * A single selected account fills the widget and carries its own refresh control; several
 * scroll, split by hairlines.
 */
class DetailedUsageWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val views = WidgetUpdater.observe(context, id)
        val initial = views.first()
        provideContent {
            val view by views.collectAsState(initial)
            val snapshot = view.snapshot
            val style = view.style
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetStyle provides style) {
                    Column(
                        GlanceModifier.fillMaxSize().panelCorner()
                            .background(style.background.color).padding(10.dp)
                            .clickable(actionStartActivity<MainActivity>()),
                    ) {
                        if (snapshot.accounts.size != 1) {
                            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(GlanceModifier.defaultWeight()) {
                                    Text("Usage Limits", style = widgetText(style.textPrimary, 14, FontWeight.Medium))
                                    Text(
                                        listOfNotNull("${snapshot.accountCount} accounts", asOf(snapshot.updatedAt)).joinToString(" · "),
                                        style = widgetText(style.textSecondary, 11), maxLines = 1,
                                    )
                                }
                                RefreshChip(40.dp)
                            }
                            Spacer(GlanceModifier.height(6.dp))
                        }
                        if (snapshot.accounts.isEmpty()) {
                            Text(
                                "No matching accounts. Edit this widget to choose an account.",
                                style = widgetText(style.textSecondary, 12),
                            )
                        } else {
                            LazyColumn(GlanceModifier.fillMaxSize()) {
                                itemsIndexed(snapshot.accounts, itemId = { _, it -> it.accountId.hashCode().toLong() }) { index, account ->
                                    Column {
                                        if (index > 0) {
                                            Spacer(GlanceModifier.height(6.dp))
                                            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(style.track)) {}
                                            Spacer(GlanceModifier.height(6.dp))
                                        }
                                        AccountBlock(context, account, snapshot.accounts.size == 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountBlock(context: Context, account: WidgetAccount, single: Boolean) {
        val style = LocalWidgetStyle.current
        val openAccount = androidx.glance.appwidget.action.actionStartActivity(
            android.content.Intent(context, MainActivity::class.java).putExtra("accountId", account.accountId),
        )
        // The account's data validity — stale, failed or needing reconnection — outranks the
        // quota decoration on every row this block draws.
        val validity = account.dataValidity
        Column(GlanceModifier.fillMaxWidth().clickable(openAccount)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.usagelimits.core.model.ProviderId.fromId(account.providerId)?.let { provider ->
                    WidgetLogo(provider, account.iconChoiceId, 20.dp)
                    Spacer(GlanceModifier.width(8.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(account.title, maxLines = 1, style = widgetText(style.textPrimary, 14, FontWeight.Medium))
                        if (account.severity != Severity.HEALTHY) {
                            Spacer(GlanceModifier.width(6.dp))
                            SeverityDot(style.accent(account.severity))
                        }
                    }
                    Text(
                        account.accountLabel ?: account.subtitle.orEmpty(), maxLines = 1,
                        style = widgetText(style.textSecondary, 11),
                    )
                }
                if (single) RefreshChip(40.dp)
            }
            if (account.requiresReauthentication || account.severity == Severity.ERROR || account.severity == Severity.STALE) {
                Text(
                    when {
                        account.requiresReauthentication -> "Reconnect account"
                        account.severity == Severity.ERROR -> "Update failed · last known data"
                        else -> "Data is out of date"
                    },
                    style = widgetText(style.textSecondary, 11),
                )
            }
            if (account.partialUpdate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityDot(WidgetStyle.Amber)
                    Spacer(GlanceModifier.width(6.dp))
                    Text("Some limits could not be updated", style = widgetText(style.textPrimary, 11))
                }
            }
            AccountLimitRows(context, account.rows, validity)
        }
    }

    @Composable
    private fun AccountLimitRows(context: Context, rows: List<WidgetRow>, validity: Severity?) {
        val style = LocalWidgetStyle.current
        // Glance truncates a Column after ten direct children. Keep each limit together,
        // and split larger provider lists so neither the block nor a row group hits that cap.
        Column(GlanceModifier.fillMaxWidth()) {
            if (rows.size > 10) {
                val middle = rows.size / 2
                AccountLimitRows(context, rows.subList(0, middle), validity)
                AccountLimitRows(context, rows.subList(middle, rows.size), validity)
            } else {
                rows.forEach { row ->
                    Column(GlanceModifier.fillMaxWidth()) {
                        Spacer(GlanceModifier.height(6.dp))
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.label, GlanceModifier.defaultWeight(), maxLines = 1,
                                style = widgetText(style.textSecondary, 12),
                            )
                            val percent = percentText(row)
                            Text(percent, style = if (percent == "—") widgetText(style.textSecondary, 12)
                                else widgetText(style.barText(row, validity), 12, FontWeight.Medium, mono = true))
                        }
                        Spacer(GlanceModifier.height(3.dp))
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            UsageBar(row, GlanceModifier.defaultWeight(), validity = validity)
                            Spacer(GlanceModifier.width(8.dp))
                            Text(
                                row.resetAt?.let { compactRingReset(context, it) } ?: "—", modifier = GlanceModifier.width(56.dp), maxLines = 1,
                                style = widgetText(style.textSecondary, 11, mono = true),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A provider mark. Codex, Grok and Kimi ship white artwork, which vanishes on a light panel, so
 * on one the mark is drawn in the panel's ink instead.
 */
@Composable
internal fun WidgetLogo(provider: com.usagelimits.core.model.ProviderId, iconChoiceId: String?, size: Dp) {
    val style = LocalWidgetStyle.current
    Image(
        ImageProvider(com.usagelimits.ui.providerLogoResource(provider, iconChoiceId)), null, GlanceModifier.size(size),
        colorFilter = if (!style.lightInk && provider in WHITE_ARTWORK) ColorFilter.tint(ColorProvider(style.textPrimary)) else null,
    )
}

private val WHITE_ARTWORK = setOf(
    com.usagelimits.core.model.ProviderId.CODEX, com.usagelimits.core.model.ProviderId.XAI, com.usagelimits.core.model.ProviderId.KIMI,
)

class CompactUsageWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactUsageWidget()
}

class DetailedUsageWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DetailedUsageWidget()
}
