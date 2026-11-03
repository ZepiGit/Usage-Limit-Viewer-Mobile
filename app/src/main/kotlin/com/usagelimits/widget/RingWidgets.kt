package com.usagelimits.widget

import android.content.Context
import android.content.Intent
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
import com.usagelimits.ui.percentSideCaptionRes
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
            val grid = WidgetLayout.miniGrid(size.width.value, size.height.value, view.metrics)
            val columns = if (mini) grid.columns else WidgetLayout.accountColumns(size.width.value, size.height.value, view.metrics, fontScale)
            // The caption above the grid states which side every arc counts. Mini grids
            // reserve its line before counting rows; non-mini grids scroll, so it is only an inset.
            val captionHeight = WidgetLayout.ringCaptionHeight(fontScale)
            val selected = if (mini) {
                val rows = WidgetLayout.miniCaptionedRows(grid, view.snapshot.accounts.size, size.height.value, fontScale)
                view.snapshot.accounts.take(columns * rows)
            } else view.snapshot.accounts
            val groups = selected.chunked(columns)
            val rowHeight = if (mini) (((size.height.value - 8f - captionHeight) / WidgetLayout.miniVisibleRows(grid, selected.size))).coerceAtLeast(20f).dp
                else maxOf(48f, 32f * fontScale + 8f).dp
            val ringSize = if (mini) minOf((size.width.value - 8f) / columns, rowHeight.value).minus(8f).coerceIn(16f, 52f).dp else 40.dp
            CompositionLocalProvider(LocalWidgetStyle provides style, LocalPercentMode provides mode) {
                Column(GlanceModifier.fillMaxSize().panelCorner()
                    .background(style.background.color)
                    .padding(if (mini) 4.dp else 10.dp)) {
                    if (groups.isEmpty()) Text(view.snapshot.emptyText(context.resources) ?: context.getString(R.string.widget_no_matching_accounts), style = widgetText(style.textSecondary, 12))
                    else {
                        ModeCaption()
                        if (mini) {
                            // Fixed visible capacity also avoids sharing a collection adapter between
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
}

/**
 * The one-word hint that keeps a bare ring unambiguous: a figure alone does not say which
 * side it counts. Same placement on every ring widget, independent of any branch below it.
 */
@Composable
private fun ModeCaption() {
    val style = LocalWidgetStyle.current
    val mode = LocalPercentMode.current
    Text(
        text = LocalContext.current.resources.getString(percentSideCaptionRes(mode)),
        style = widgetText(style.textSecondary, 9),
        maxLines = 1,
        modifier = GlanceModifier.padding(horizontal = 2.dp).height(((9 * LocalContext.current.resources.configuration.fontScale + 4).dp)),
    )
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
                        ProviderId.fromId(account.providerId)?.let {
                            WidgetLogo(it, account.iconChoiceId, 16.dp)
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

/**
 * The figure a ring shows in [mode], with its sign.
 *
 * The raw severity decides exhaustion, so a window the provider called closed still reads "100%"
 * in USED beside whatever colour an aged severity gives the arc.
 */
internal fun ringFigure(resources: android.content.res.Resources, account: WidgetAccount, mode: PercentDisplay): String =
    percentFigure(ringLimit(account).presentation(null, mode).token, resources)

/**
 * What a screen reader hears for one ring. Shared by the placed widgets and the configuration
 * preview so the preview cannot describe a ring differently from the widget it stands for.
 */
internal fun ringDescription(resources: android.content.res.Resources, account: WidgetAccount, mode: PercentDisplay): String =
    "${account.title}, ${account.subtitle.orEmpty()}, ${ringFigure(resources, account, mode)} ${resources.getString(percentSpokenClauseRes(mode))}" +
        listOfNotNull(
            R.string.widget_ring_desc_reconnect.takeIf { account.requiresReauthentication }
                ?.let { ", " + resources.getString(it) },
            R.string.widget_ring_desc_partial.takeIf { account.partialUpdate }
                ?.let { ", " + resources.getString(it) },
            R.string.widget_ring_desc_limited.takeIf { account.restrictions.isNotEmpty() }
                ?.let { ", " + resources.getString(it, account.restrictions.joinToString()) },
        ).joinToString("")

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
 * their centre; the 1x1 rings have no room for text, so they carry the provider mark instead.
 * A partial update is a 6dp amber dot at the ring's 2 o'clock, outside the stroke.
 */
@Composable
private fun RingMark(account: WidgetAccount, size: androidx.compose.ui.unit.Dp, mini: Boolean) {
    val style = LocalWidgetStyle.current
    val mode = LocalPercentMode.current
    val p = ringLimit(account).presentation(null, mode)
    val resources = LocalContext.current.resources
    val percent = percentFigure(p.token, resources)
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(UsageRing.draw(
                160,
                p.fill?.times(100.0),
                ringAccent(account, style).toArgb(), style.trackArgb)),
            ringDescription(resources, account, mode),
            GlanceModifier.fillMaxSize())
        if (mini) {
            ProviderId.fromId(account.providerId)?.let {
                WidgetLogo(it, account.iconChoiceId, size * 0.46f)
            }
        } else if (p.hasFigure) {
            Text(percent, style = widgetText(style.textPrimary, 12, FontWeight.Bold, mono = true), maxLines = 1)
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
