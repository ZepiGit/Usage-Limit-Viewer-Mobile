package com.usagelimits.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import com.usagelimits.R
import com.usagelimits.navigation.ExternalRequest
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import kotlinx.coroutines.flow.first

/**
 * Prepaid balances: one account at 2x2, as many as fit at 4x2 and 4x4, worst first.
 *
 * Reads the credit cache through [WidgetUpdater.observeCredits] only, so no key can reach the
 * home screen. No configuration: it shows every credit account, and tapping opens the app.
 */
class CreditsWidget : GlanceAppWidget() {

    // Three discrete sizes rather than Exact: the count shown is a design decision per size
    // (1, 3, 6), not a function of whatever height the launcher happens to report.
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val views = WidgetUpdater.observeCredits(context)
        val initial = views.first()
        provideContent {
            val snapshot by views.collectAsState(initial)
            val size = LocalSize.current
            val style = WidgetStyle()
            val texts = ResourceCreditWidgetTexts(context.resources)
            // What the height can hold, so the last row is never cut mid-glyph; the rest is a "+N more".
            val capacity = CreditWidgetBuilder.capacity(size.width.value, size.height.value)
            var fit = minOf(capacity, ((size.height.value - HEADER_DP) / ROW_DP).toInt().coerceAtLeast(1))
            if (fit < snapshot.rows.size && capacity > 1 && fit * ROW_DP + MORE_DP > size.height.value - HEADER_DP) fit = (fit - 1).coerceAtLeast(1)
            val shown = snapshot.rows.take(fit)
            val more = snapshot.rows.size - shown.size
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetStyle provides style) {
                    Column(
                        GlanceModifier.fillMaxSize().panelCorner().background(style.background.color).padding(PANEL_INSET.dp)
                            .clickable(androidx.glance.appwidget.action.actionStartActivity(ExternalRequest.openOverview(context))),
                    ) {
                        when {
                            snapshot.rows.isEmpty() -> Text(
                                context.getString(R.string.widget_credits_empty),
                                style = widgetText(style.textSecondary, 12),
                            )
                            size == SMALL -> SmallTile(shown.first(), style, texts)
                            shown.size == 1 && more == 0 -> Single(shown.first(), style, texts)
                            else -> {
                                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        context.getString(R.string.widget_label_credits), GlanceModifier.defaultWeight(),
                                        style = widgetText(style.textSecondary, 12, FontWeight.Medium),
                                    )
                                    snapshot.rows.mapNotNull { it.fetchedAt }.maxOrNull()?.let {
                                        Text(
                                            Countdown.asOfLabel(it, nowMs = System.currentTimeMillis(), texts = texts), maxLines = 1,
                                            style = widgetText(style.textSecondary, 11, mono = true),
                                        )
                                    }
                                }
                                Spacer(GlanceModifier.height(4.dp))
                                shown.forEach { Line(it, style, texts) }
                                if (more > 0) Text(context.getString(R.string.widget_credits_more, more), style = widgetText(style.textSecondary, 11))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallTile(row: CreditWidgetRow, style: WidgetStyle, texts: CreditWidgetTexts) {
        Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(style.accent(row.severity))
                Spacer(GlanceModifier.width(6.dp))
                Text(row.title, maxLines = 1, style = widgetText(style.textSecondary, 12, FontWeight.Medium))
            }
            Column(GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally) {
                val resources = LocalContext.current.resources
                val amount = row.balances.firstOrNull()?.let {
                    CreditFormat.string(it.amount, it.currency, resources.configuration.locales[0])
                } ?: "—"
                Text(amount, maxLines = 1, style = widgetText(style.textPrimary, 28, FontWeight.Bold, mono = true)
                    .copy(fontSize = fittedMonoSize(amount, 28f, SMALL.width - (2 * PANEL_INSET).dp, resources).sp))
                val failedRefresh = refreshFailed(row)
                val status = when {
                    row.severity == Severity.ERROR -> texts.couldNotBeRead()
                    failedRefresh -> resources.getString(R.string.widget_credits_refresh_failed)
                    row.fetchedAt == null -> texts.notReadYet()
                    row.severity == Severity.STALE -> resources.getString(statusWordRes(Severity.STALE))
                    else -> {
                        val asOf = Countdown.asOfLabel(row.fetchedAt, nowMs = System.currentTimeMillis(), texts = texts)
                        if (row.balances.firstOrNull()?.unlimited == true) {
                            // The tile already draws the amount above, so it needs a standalone label instead of the amount sentence.
                            resources.getString(R.string.widget_credits_spent_this_month) + " · " + asOf
                        } else asOf
                    }
                }
                Text(
                    status,
                    maxLines = 2,
                    style = widgetText(if (failedRefresh) style.textPrimary else style.textSecondary, 11)
                        .copy(textAlign = TextAlign.Center),
                )
            }
        }
    }

    @Composable
    private fun Single(row: CreditWidgetRow, style: WidgetStyle, texts: CreditWidgetTexts) {
        Column(GlanceModifier.fillMaxSize()) {
            Text(
                row.title, maxLines = 1,
                style = widgetText(style.textSecondary, 12, FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    amountText(row, texts), maxLines = 1,
                    style = widgetText(style.textPrimary, 22, FontWeight.Bold, mono = true),
                )
                if (row.severity != Severity.HEALTHY) {
                    Spacer(GlanceModifier.width(6.dp))
                    SeverityDot(style.accent(row.severity))
                }
            }
            Text(
                statusText(row, texts), maxLines = 1,
                style = widgetText(if (refreshFailed(row)) style.textPrimary else style.textSecondary, 11),
            )
        }
    }

    @Composable
    private fun Line(row: CreditWidgetRow, style: WidgetStyle, texts: CreditWidgetTexts) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            SeverityDot(style.accent(row.severity))
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    row.title, maxLines = 1,
                    style = widgetText(style.textPrimary, 14),
                )
                // Independent of the balance: a stale or failed read must not hide behind it.
                if (refreshFailed(row)) {
                    Text(androidx.glance.LocalContext.current.getString(R.string.widget_credits_refresh_failed), maxLines = 1, style = widgetText(style.textPrimary, 11))
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                amountText(row, texts), maxLines = 1,
                style = widgetText(style.textPrimary, 14, FontWeight.Bold, mono = true),
            )
        }
    }

    private companion object {
        const val PANEL_INSET = 10
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
        // Panel padding plus the header line, a list row (14sp + 3dp padding each side), the "+N more" line.
        const val HEADER_DP = 40f
        const val ROW_DP = 26f
        const val MORE_DP = 14f
    }
}

/** The figure on the right of a row: the worst balance, or a quiet dash when there is none. */
internal fun amountText(row: CreditWidgetRow, texts: CreditWidgetTexts = EnglishCreditWidgetTexts): String =
    row.balances.firstOrNull()?.let { CreditFormat.line(it, spentThisMonth = texts::spentThisMonth) } ?: "—"

/** The last refresh failed but a cached balance is still shown; severity then describes the balance only. */
internal fun refreshFailed(row: CreditWidgetRow): Boolean = row.statusMessage != null && row.balances.isNotEmpty()

/**
 * The line under the tile's amount: when it was read, or why it was not.
 *
 * A failure's reason is the message stored with the snapshot when the refresh failed, in English;
 * [CreditWidgetTexts.stored] reads it in the screen's language without touching what is stored.
 */
internal fun statusText(row: CreditWidgetRow, texts: CreditWidgetTexts = EnglishCreditWidgetTexts): String {
    val now = System.currentTimeMillis()
    return when {
        row.severity == Severity.ERROR -> row.statusMessage?.let(texts::stored) ?: texts.couldNotBeRead()
        refreshFailed(row) -> texts.refreshFailed(texts.stored(row.statusMessage.orEmpty())) +
            (row.fetchedAt?.let { " · " + Countdown.asOfLabel(it, nowMs = now, texts = texts) } ?: "")
        row.fetchedAt == null -> texts.notReadYet()
        row.severity == Severity.STALE -> texts.outOfDate(Countdown.asOfLabel(row.fetchedAt, nowMs = now, texts = texts))
        else -> row.providerName + " · " + Countdown.asOfStamp(row.fetchedAt, nowMs = now)
    }
}

class CreditsWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CreditsWidget()
}
