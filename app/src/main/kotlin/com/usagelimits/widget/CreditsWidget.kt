package com.usagelimits.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.usagelimits.MainActivity
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import kotlinx.coroutines.flow.first

/**
 * Prepaid balances: one account at 2x2, three at 4x2, six at 4x4, worst first.
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
            val shown = snapshot.rows.take(CreditWidgetBuilder.capacity(size.width.value, size.height.value))
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(style.background.color).padding(10.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    when {
                        snapshot.rows.isEmpty() -> Text(
                            "No credit balances. Add one in the app.",
                            style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 12.sp),
                        )
                        shown.size == 1 -> Single(shown.first(), style)
                        else -> {
                            Text(
                                "Credits",
                                style = TextStyle(color = ColorProvider(style.textPrimary), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(GlanceModifier.height(4.dp))
                            shown.forEach { Line(it, style) }
                        }
                    }
                }
            }
        }
    }

    /** The 2x2 tile: one account, its amount large, and as of when. */
    @Composable
    private fun Single(row: CreditWidgetRow, style: WidgetStyle) {
        Text(
            row.title, maxLines = 1,
            style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            amountText(row), maxLines = 1,
            style = TextStyle(color = ColorProvider(style.textColor(row.severity)), fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            statusText(row), maxLines = 2,
            style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 10.sp),
        )
    }

    @Composable
    private fun Line(row: CreditWidgetRow, style: WidgetStyle) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(style.accent(row.severity))) {}
            Spacer(GlanceModifier.width(8.dp))
            Text(
                row.title, GlanceModifier.defaultWeight(), maxLines = 1,
                style = TextStyle(color = ColorProvider(style.textPrimary), fontSize = 12.sp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                amountText(row), maxLines = 1,
                style = TextStyle(color = ColorProvider(style.textColor(row.severity)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

/** The figure on the right of a row: the worst balance, or the state when there is none. */
internal fun amountText(row: CreditWidgetRow): String =
    row.balances.firstOrNull()?.let { CreditFormat.line(it) }
        ?: if (row.severity == Severity.ERROR) "Error" else "—"

/** The line under the tile's amount: when it was read, or why it was not. */
internal fun statusText(row: CreditWidgetRow): String = when {
    row.severity == Severity.ERROR -> row.statusMessage ?: "Could not be read"
    row.fetchedAt == null -> "Not read yet"
    row.severity == Severity.STALE -> "Out of date · ${Countdown.asOfLabel(row.fetchedAt, nowMs = System.currentTimeMillis())}"
    else -> row.providerName + " · " + Countdown.asOfLabel(row.fetchedAt, nowMs = System.currentTimeMillis())
}

class CreditsWidgetReceiver : UsageWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CreditsWidget()
}
