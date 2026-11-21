package com.usagelimits.widget

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.usagelimits.R
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.percentSpokenClauseRes
import com.usagelimits.ui.text.ResourceCountdownTexts
import com.usagelimits.ui.text.percentFigure
import kotlinx.coroutines.flow.first

open class AccountRingsWidget(private val mini: Boolean) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val views = WidgetUpdater.observe(context, id)
        val initial = views.first()
        provideContent {
            val view by views.collectAsState(initial)
            val style = view.style
            val mode = view.percentDisplay
            val size = LocalSize.current
            val fontScale = LocalContext.current.resources.configuration.fontScale
            val accounts = view.snapshot.accounts
            val grid = if (mini) WidgetLayout.miniGrid(size.width.value, size.height.value, view.metrics) else null
            val columns = grid?.columns ?: WidgetLayout.accountColumns(size.width.value, size.height.value, view.metrics, fontScale)
            val contentHeight = size.height.value - 8f
            val groups = if (grid == null) accounts.chunked(columns) else {
                val rows = WidgetLayout.miniRows(grid, accounts.size, contentHeight)
                accounts.take(columns * rows).chunked(columns)
            }
            val rowHeight = if (mini) (contentHeight / groups.size.coerceAtLeast(1)).dp
                else maxOf(48f, 32f * fontScale + 8f).dp
            val ringSize = if (mini) (minOf((size.width.value - 8f) / columns, rowHeight.value) - 8f).coerceIn(16f, 52f).dp
                else minOf(rowHeight - 8.dp, (size.width - 20.dp) / columns - 96.dp).coerceAtLeast(40.dp)
            val verticalInset = if (mini) 4.dp else ((size.height - rowHeight) / 2).coerceIn(0.dp, 10.dp)
            CompositionLocalProvider(LocalWidgetStyle provides style, LocalPercentMode provides mode) {
                Column(GlanceModifier.fillMaxSize().panelCorner()
                    .background(style.background.color)
                    .padding(horizontal = if (mini) 4.dp else 10.dp, vertical = verticalInset)) {
                    if (groups.isEmpty()) {
                        Text(view.snapshot.emptyText(context.resources) ?: context.getString(R.string.widget_no_matching_accounts), style = widgetText(style.textSecondary, 12))
                    } else if (mini) {
                        // Fixed visible capacity avoids sharing a collection adapter between
                        // differently sized portrait and landscape RemoteViews.
                        groups.forEach { group -> RingAccountRow(context, group, columns, rowHeight, ringSize, true) }
                    } else LazyColumn(GlanceModifier.fillMaxSize()) {
                        items(groups, itemId = { it.first().accountId.hashCode().toLong() }) { group ->
                            RingAccountRow(context, group, columns, rowHeight, ringSize, false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RingAccountRow(context: Context, group: List<WidgetAccount>, columns: Int,
    rowHeight: androidx.compose.ui.unit.Dp, ringSize: androidx.compose.ui.unit.Dp, mini: Boolean) {
    val style = LocalWidgetStyle.current
    Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        group.forEach { account ->
            val action = actionStartActivity(Intent(context, UsageLimitsActivity::class.java).putExtra("accountId", account.accountId))
            if (mini) Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), contentAlignment = Alignment.Center) {
                RingMark(account, ringSize, mini = true)
            } else Row(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), verticalAlignment = Alignment.CenterVertically) {
                RingMark(account, ringSize, mini = false)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ProviderId.fromId(account.providerId) != null) {
                            WidgetLogo(account, 18.dp)
                            Spacer(GlanceModifier.width(6.dp))
                        }
                        Text(account.accountLabel ?: account.subtitle ?: account.title, maxLines = 1,
                            style = widgetText(style.textPrimary, 12, FontWeight.Medium))
                    }
                    val resources = context.resources
                    Text(when {
                        account.requiresReauthentication -> resources.getString(R.string.status_reconnect)
                        account.severity == Severity.EXHAUSTED && account.partialUpdate -> resources.getString(R.string.widget_ring_out_partial)
                        account.partialUpdate -> resources.getString(R.string.widget_ring_partial)
                        account.restrictions.isNotEmpty() -> resources.getString(R.string.widget_ring_limited, account.restrictions.joinToString())
                        ringLimit(account)?.remainingPercent == null -> resources.getString(R.string.widget_ring_quota_unknown)
                        else -> ringLimit(account)?.let { limit ->
                            limit.resetAt?.let { "${limit.labelText(resources)} · ${compactRingReset(context, it)}" }
                        } ?: resources.getString(R.string.widget_ring_no_reset)
                    }, maxLines = 1,
                        style = widgetText(style.textSecondary, 11))
                }
            }
        }
        repeat(columns - group.size) { Spacer(GlanceModifier.defaultWeight()) }
    }
}

internal fun ringLimit(account: WidgetAccount): WidgetRow? = account.representative

/** What a ring writes in its centre: the figure with its sign, the figure alone, or nothing. */
internal enum class RingCentre { SIGNED, BARE, NOTHING }

/**
 * The centre for a presentation's [token] and [side] on a ring that does or does not draw its arc
 * (#38). In USED a 100 is the exhausted window and is written bare, which fits where "100%" does
 * not. In LEFT a 100 is the full allowance: under its closed arc nothing is written, and where no
 * arc is drawn it is written bare, never with the sign. Every other token keeps its sign.
 *
 * The same rule, on the same inputs, is the iOS kit's `RingCentre`; both are held to
 * `shared/test-vectors/ring-centre.json`. The presentation keeps its "100" token, so the
 * description and every other surface still say the full figure.
 */
internal fun ringCentre(token: String, side: PercentDisplay, arcDrawn: Boolean): RingCentre = when {
    token != FULL_RING_TOKEN -> RingCentre.SIGNED
    side == PercentDisplay.LEFT && arcDrawn -> RingCentre.NOTHING
    else -> RingCentre.BARE
}

/** The token of a closed ring. ">99" is its own token, so this never swallows a rounded 99.6. */
private const val FULL_RING_TOKEN = "100"

/**
 * What an Android ring writes in its centre for [p], or null for nothing. A ring adopts
 * [ringCentre] by calling this instead of [percentFigure].
 *
 * [UsageRing.draw] paints the presentation's fill whatever its fill state says (the grey of an
 * aged or failed reading is [ringAccent]'s), so the arc is drawn whenever there is a fill. A full
 * allowance therefore always writes nothing here, even where the presentation reads "no fill".
 * An unknown figure writes nothing: an Android ring centre has never shown the dash.
 */
internal fun ringCentreFigure(resources: android.content.res.Resources, p: WindowPresentation): String? {
    if (!p.hasFigure) return null
    return when (ringCentre(p.token, p.side, arcDrawn = p.fill != null)) {
        RingCentre.SIGNED -> percentFigure(p.token, resources)
        RingCentre.BARE -> p.token
        RingCentre.NOTHING -> null
    }
}

/**
 * What a screen reader hears for one ring. Shared by the placed widgets and the configuration
 * preview so the preview cannot describe a ring differently from the widget it stands for.
 */
internal fun ringDescription(resources: Resources, account: WidgetAccount, presentation: WindowPresentation): String {
    val window = ringLimit(account)
    val reset = Countdown.absoluteResetLabel(window?.resetAt, System.currentTimeMillis(),
        locale = resources.configuration.locales[0], texts = ResourceCountdownTexts(resources))
    val reading = if (presentation.hasFigure) {
        "${percentFigure(presentation.token, resources)} ${resources.getString(percentSpokenClauseRes(presentation.side))}"
    } else resources.getString(R.string.widget_ring_quota_unknown)
    val validity = when {
        account.requiresReauthentication -> R.string.widget_ring_desc_reconnect
        account.severity == Severity.ERROR -> R.string.widget_update_failed_last_known
        account.severity == Severity.STALE && window?.resetElapsed != true -> R.string.widget_data_out_of_date
        else -> null
    }
    return listOfNotNull(
        account.title, account.subtitle ?: account.accountLabel, reading,
        window?.labelText(resources), reset ?: resources.getString(R.string.widget_ring_no_reset),
        validity?.let(resources::getString),
        if (account.partialUpdate) resources.getString(R.string.widget_ring_desc_partial) else null,
        if (account.restrictions.isNotEmpty()) resources.getString(R.string.widget_ring_desc_limited, account.restrictions.joinToString()) else null,
    ).joinToString(", ")
}

internal fun compactRingReset(context: Context, resetAt: Long): String {
    val now = System.currentTimeMillis()
    val texts = ResourceCountdownTexts(context.resources)
    if (resetAt <= now) return texts.resetDue()
    val zone = java.time.ZoneId.systemDefault()
    val reset = java.time.Instant.ofEpochMilli(resetAt).atZone(zone)
    return if (reset.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(resetAt))
    } else reset.format(java.time.format.DateTimeFormatter.ofPattern(texts.resetDatePattern(), java.util.Locale.getDefault()))
}

/**
 * One ring: the displayed share as a data-hue arc over a track. The big rings carry the % in
 * their centre, except where [ringCentreFigure] leaves it empty; the 1x1 rings have no room for
 * text, so they carry the provider mark instead.
 * A partial update is a 6dp amber dot at the ring's 2 o'clock, outside the stroke.
 */
@Composable
private fun RingMark(account: WidgetAccount, size: androidx.compose.ui.unit.Dp, mini: Boolean) {
    val style = LocalWidgetStyle.current
    val mode = LocalPercentMode.current
    val p = ringLimit(account).presentation(null, mode)
    val resources = LocalContext.current.resources
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(UsageRing.draw(
                160,
                p.fill?.times(100.0),
                ringAccent(account, style).toArgb(), style.trackArgb)),
            ringDescription(resources, account, p),
            GlanceModifier.fillMaxSize())
        if (mini) {
            WidgetLogo(account, miniLogoSize(size, account.providerId))
        } else ringCentreFigure(resources, p)?.let {
            // Leave room for rounded text advances and for the arc above and below the centreline.
            val clearWidth = size * (1f - 2f * UsageRing.STROKE_FRACTION) - 5.dp
            val figureStyle = widgetText(style.textPrimary, 12, FontWeight.Bold, mono = true)
            Text(it, style = figureStyle.copy(fontSize = fittedMonoSize(it, 12f, clearWidth, resources).sp), maxLines = 1)
        }
        if (account.partialUpdate) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                SeverityDot(WidgetStyle.Amber)
            }
        }
    }
}

/** The arc: the data hue, grey when the reading is old, failed or overtaken by a reset. */
internal fun ringAccent(account: WidgetAccount, style: WidgetStyle): androidx.compose.ui.graphics.Color {
    val row = ringLimit(account)
    return when {
        account.requiresReauthentication -> style.stale
        account.severity == Severity.ERROR || account.severity == Severity.STALE -> style.stale
        // A reset has passed since the fetch: the arc is the spent window's, drawn in the
        // stale treatment beside "Reset due" rather than in a colour that vouches for it.
        row?.resetElapsed == true -> style.stale
        else -> style.data
    }
}

class MinimalUsageWidget : AccountRingsWidget(false)
class MiniRingsWidget : AccountRingsWidget(true)
class MinimalUsageWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinimalUsageWidget()
}
class MiniRingsWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniRingsWidget()
}
