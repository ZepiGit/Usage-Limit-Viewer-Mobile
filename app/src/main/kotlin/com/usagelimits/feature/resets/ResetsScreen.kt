package com.usagelimits.feature.resets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UpcomingReset
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.EmptyBox
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.text.windowLabelRes
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every upcoming rollover, chronologically.
 *
 * Grouped by calendar day ("Today", "Tomorrow", then a weekday name) because a flat list of
 * countdowns gives no sense of *when* — "in 19h" and "tomorrow 08:20" answer different questions.
 * One card per day, rows split by hairlines; the first reset still to come carries the data hue.
 */
@Composable
fun ResetsScreen(
    state: UsageUiState,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val resets = state.upcomingResets(nowMs)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val next = resets.firstOrNull()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle(
                title = stringResource(R.string.resets_title),
                stamp = if (resets.isEmpty()) stringResource(R.string.resets_none_upcoming)
                    else stringResource(R.string.resets_upcoming_count, resets.size),
            )
        }

        if (resets.isEmpty()) {
            item {
                EmptyBox(stringResource(R.string.resets_empty))
            }
        }

        resets.groupBy { Instant.ofEpochMilli(it.resetAt).atZone(zone).toLocalDate() }
            .forEach { (date, dayResets) ->
                item(key = "header-$date") {
                    Text(
                        text = dayLabel(date, today),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = UsageColors.Muted,
                    )
                }
                // The day is the LazyColumn item; duplicate window IDs inside its card are safe.
                item(key = "day-$date") {
                    UsageCard(contentPadding = PaddingValues(0.dp)) {
                        dayResets.forEachIndexed { index, reset ->
                            if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                            ResetRow(reset, nowMs, zone, isNext = reset == next)
                        }
                    }
                }
            }
    }
}

@Composable
private fun ResetRow(reset: UpcomingReset, nowMs: Long, zone: ZoneId, isNext: Boolean) {
    val time = remember(reset.resetAt) {
        Instant.ofEpochMilli(reset.resetAt).atZone(zone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val statusLabel = stringResource(SeverityPalette.labelRes(reset.severity))
    val countdown = Countdown.format(reset.resetAt - nowMs, rememberCountdownTexts())
    // The app's own horizon names in the user's language, as the window rows show them; a label the
    // provider reported is shown as it came.
    val windowLabel = windowLabelRes(reset.windowLabel, reset.windowCategory, reset.windowScope)
        ?.let { stringResource(it) } ?: reset.windowLabel
    val description = stringResource(
        R.string.resets_row_description, time, windowLabel, reset.accountLabel, statusLabel, countdown,
    )

    Row(
        // The severity dot is a bare Box: it emits no semantics node, so the status was
        // absent from the accessibility tree entirely and the row was spoken as four
        // disconnected fragments. One merged sentence, status included — the same treatment
        // UsageWindowRow gives a quota row. The status word lives here only, not on screen.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            }
            .drawBehind {
                if (isNext) drawRect(UsageColors.Data, Offset(0f, 8.dp.toPx()), Size(2.dp.toPx(), size.height - 16.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = time, style = UsageType.Figure, color = UsageColors.Ink, modifier = Modifier.width(48.dp))
        Spacer(Modifier.width(8.dp))
        Dot(SeverityPalette.accent(reset.severity), size = 6.dp)
        Spacer(Modifier.width(12.dp))
        com.usagelimits.ui.ProviderBadge(provider = reset.provider, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = windowLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Ink,
                maxLines = 1,
            )
            Text(
                text = reset.accountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = UsageColors.Muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = countdown,
            style = UsageType.Figure,
            color = if (isNext) UsageColors.Data else UsageColors.Muted,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.resets_today)
    today.plusDays(1) -> stringResource(R.string.resets_tomorrow)
    // The pattern is a resource because the shape of a dated heading differs per language.
    else -> date.format(
        DateTimeFormatter.ofPattern(stringResource(R.string.resets_day_pattern), LocalConfiguration.current.locales[0]),
    )
}
