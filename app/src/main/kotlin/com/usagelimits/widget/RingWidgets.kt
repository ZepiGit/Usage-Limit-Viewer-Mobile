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
import com.usagelimits.MainActivity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.ui.providerLogoResource
import kotlinx.coroutines.flow.first

open class AccountRingsWidget(private val mini: Boolean) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val views = WidgetUpdater.observe(context, id)
        val initial = views.first()
        provideContent {
            val view by views.collectAsState(initial)
            val style = view.style
            val size = LocalSize.current
            val fontScale = LocalContext.current.resources.configuration.fontScale
            val grid = WidgetLayout.miniGrid(size.width.value, size.height.value, view.metrics)
            val columns = if (mini) grid.columns else WidgetLayout.accountColumns(size.width.value, size.height.value, view.metrics, fontScale)
            val selected = if (mini) view.snapshot.accounts.take(grid.capacity) else view.snapshot.accounts
            val groups = selected.chunked(columns)
            val rowHeight = if (mini) ((size.height.value - 8f) / WidgetLayout.miniVisibleRows(grid, selected.size)).coerceAtLeast(20f).dp
                else maxOf(44f, 32f * fontScale + 8f).dp
            val ringSize = if (mini) minOf((size.width.value - 8f) / columns, rowHeight.value).minus(8f).coerceIn(16f, 52f).dp else 36.dp
            CompositionLocalProvider(LocalWidgetStyle provides style) {
                Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                    .background(style.background.color)
                    .padding(if (mini) 4.dp else 10.dp)) {
                    if (groups.isEmpty()) Text("No matching accounts", style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 12.sp))
                    else if (mini) {
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

@Composable
private fun RingAccountRow(context: Context, group: List<WidgetAccount>, columns: Int,
    rowHeight: androidx.compose.ui.unit.Dp, ringSize: androidx.compose.ui.unit.Dp, mini: Boolean) {
    val style = LocalWidgetStyle.current
    Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        group.forEach { account ->
            val action = actionStartActivity(Intent(context, MainActivity::class.java).putExtra("accountId", account.accountId))
            if (mini) Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), contentAlignment = Alignment.Center) {
                RingMark(account, ringSize)
            } else Row(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp).clickable(action), verticalAlignment = Alignment.CenterVertically) {
                RingMark(account, ringSize)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(account.accountLabel ?: account.subtitle ?: account.title, maxLines = 1,
                        style = TextStyle(color = ColorProvider(style.textPrimary), fontSize = 11.sp, fontWeight = FontWeight.Medium))
                    Text(when {
                        account.requiresReauthentication -> "Reconnect"
                        account.severity == Severity.EXHAUSTED && account.partialUpdate -> "Out · Partial update"
                        account.partialUpdate -> "Partial update"
                        account.restrictions.isNotEmpty() -> "Limited: ${account.restrictions.joinToString()}"
                        ringLimit(account)?.remainingPercent == null -> "Quota unknown"
                        else -> ringLimit(account)?.resetAt?.let { compactRingReset(context, it) } ?: "No reset time"
                    }, maxLines = 1,
                        style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp))
                }
            }
        }
        repeat(columns - group.size) { Spacer(GlanceModifier.defaultWeight()) }
    }
}

internal fun ringLimit(account: WidgetAccount): WidgetRow? = account.representative

internal fun compactRingReset(context: Context, resetAt: Long): String {
    val now = System.currentTimeMillis()
    if (resetAt <= now) return "Reset due"
    val zone = java.time.ZoneId.systemDefault()
    val reset = java.time.Instant.ofEpochMilli(resetAt).atZone(zone)
    return if (reset.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(resetAt))
    } else reset.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
}

@Composable
private fun RingMark(account: WidgetAccount, size: androidx.compose.ui.unit.Dp) {
    val style = LocalWidgetStyle.current
    val row = ringLimit(account)
    val accent = ringAccent(account)
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(UsageRing.draw(160, row?.remainingPercent, accent.toArgb(), style.trackArgb)),
            "${account.title}, ${account.subtitle.orEmpty()}, ${com.usagelimits.core.model.percentLabel(row?.remainingPercent)} remaining" +
                listOfNotNull(
                    ", reconnect required".takeIf { account.requiresReauthentication },
                    ", some limits could not be updated".takeIf { account.partialUpdate },
                    ", limited: ${account.restrictions.joinToString()}".takeIf { account.restrictions.isNotEmpty() },
                ).joinToString(""),
            GlanceModifier.fillMaxSize())
        ProviderId.fromId(account.providerId)?.let {
            Image(ImageProvider(providerLogoResource(it, account.iconChoiceId)), null, GlanceModifier.size(size * 0.46f))
        }
        if (account.partialUpdate) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                Box(GlanceModifier.size(12.dp).cornerRadius(6.dp).background(WidgetStyle.Amber), contentAlignment = Alignment.Center) {
                    Text("!", style = TextStyle(color = ColorProvider(androidx.compose.ui.graphics.Color(0xFF1A1918)),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

internal fun ringAccent(account: WidgetAccount): androidx.compose.ui.graphics.Color {
    val row = ringLimit(account)
    return when {
        account.requiresReauthentication -> WidgetStyle.Red
        account.severity == Severity.ERROR || account.severity == Severity.STALE -> WidgetStyle.Slate
        // A reset has passed since the fetch: the arc is the spent window's, drawn in the
        // stale treatment beside "Reset due" rather than in a colour that vouches for it.
        row?.resetElapsed == true -> WidgetStyle.Slate
        row?.remainingPercent == null -> WidgetStyle.Slate
        row?.severity == Severity.EXHAUSTED -> WidgetStyle.Red
        account.severity == Severity.LIMITED || account.partialUpdate -> WidgetStyle.Amber
        row?.severity == Severity.LOW || row?.severity == Severity.MEDIUM -> WidgetStyle.Amber
        else -> WidgetStyle.Green
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
