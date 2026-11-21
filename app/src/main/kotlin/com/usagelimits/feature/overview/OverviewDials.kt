package com.usagelimits.feature.overview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.presentation
import com.usagelimits.core.notifications.NotificationEvaluator
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.ProviderLogo
import com.usagelimits.ui.components.FadingFigure
import com.usagelimits.ui.percentSpokenClauseRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.text.storedMessageText
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.UsageType
import com.usagelimits.ui.theme.itemMotion
import java.time.ZoneId

private val DialGap = 10.dp
private val DialRadius = 16.dp

/** The grid only: the shared header, empty state and final caption belong to OverviewScreen. */
internal fun LazyListScope.overviewDials(
    entries: List<OverviewEntry>,
    settings: AppSettings,
    nowMs: Long,
    zone: ZoneId,
    staleAfterMs: Long,
    columnCount: Int,
    motion: UsageMotion,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    if (entries.isEmpty()) return
    val columns = columnCount.coerceAtLeast(1)
    val rowCount = (entries.size + columns) / columns
    val identities = dialIdentities(entries.map { it.usage.account })
    items(
        count = rowCount,
        key = { row ->
            buildString {
                append("overview-dials:")
                for (index in row * columns until minOf((row + 1) * columns, entries.size + 1)) {
                    if (index == entries.size) append("add;") else {
                        val id = entries[index].usage.account.localId
                        append(id.length).append(':').append(id).append(';')
                    }
                }
            }
        },
        contentType = { "overview-dials-row" },
    ) { row ->
        Row(
            itemMotion(motion).padding(top = if (row == 0) 12.dp else DialGap)
                .fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(DialGap),
        ) {
            repeat(columns) { column ->
                val index = row * columns + column
                val tile = Modifier.weight(1f).fillMaxHeight()
                when {
                    index < entries.size -> {
                        val entry = entries[index]
                        val id = entry.usage.account.localId
                        key(id) {
                            OverviewDialTile(
                                entry, settings.percentDisplay, nowMs, zone, staleAfterMs, tile,
                                identity = identities[id], onClick = { onAccountClick(id) },
                            )
                        }
                    }
                    index == entries.size -> AddDialAccountTile(onAddAccount, tile)
                    else -> Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The real tile at app or Settings-preview scale; previews have no account action. */
@Composable
internal fun OverviewDialTile(
    entry: OverviewEntry,
    mode: PercentDisplay,
    nowMs: Long,
    zone: ZoneId,
    staleAfterMs: Long,
    modifier: Modifier = Modifier,
    identity: DialIdentity? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val account = entry.usage.account
    val snapshot = entry.usage.snapshot
    val window = snapshot?.windows?.firstOrNull { it.id == entry.headlineWindowId }
    val expired = snapshot?.connectionStatus == ConnectionStatus.RECONNECT_REQUIRED
    val validity = when {
        window == null -> ReadingValidity.ERROR
        expired -> ReadingValidity.STALE
        snapshot.status == SnapshotStatus.FAILED -> ReadingValidity.ERROR
        else -> entry.usage.validityOf(window, nowMs, staleAfterMs)
    }
    val presentation = window.presentation(validity, mode)
    val figure = percentFigureText(presentation.token)
    val identityLabel = when (identity) {
        is DialIdentity.Email -> identity.address
        is DialIdentity.Number -> stringResource(R.string.overview_dial_account_number, identity.position)
        DialIdentity.ProviderOnly, null -> null
    }
    val texts = rememberCountdownTexts()
    val locale = LocalConfiguration.current.locales[0]
    val stamp = if (validity == ReadingValidity.RESET_DUE) texts.resetDue()
        else window?.resetAt?.let { Countdown.absoluteResetStamp(it, nowMs, zone, locale, texts) }
    val label = window?.let { windowLabel(it) }
    val headlineLine = when {
        label == null -> stringResource(R.string.overview_no_usage_data)
        stamp != null -> stringResource(R.string.overview_dial_headline_line, label, stamp)
        else -> label
    }
    val validityLabel = when {
        expired -> stringResource(R.string.status_reconnect)
        window == null -> null
        validity == ReadingValidity.STALE -> stringResource(SeverityPalette.labelRes(Severity.STALE))
        validity == ReadingValidity.ERROR -> stringResource(SeverityPalette.labelRes(Severity.ERROR))
        else -> null
    }
    val message = snapshot?.errorMessage
        ?.takeUnless { expired && it == NotificationEvaluator.SIGN_IN_EXPIRED_MESSAGE }
        ?.let { stored ->
            val text = storedMessageText(stored)
            when (snapshot.status) {
                SnapshotStatus.PARTIAL -> stringResource(R.string.error_partial_update, text)
                SnapshotStatus.FAILED -> if (window != null) stringResource(R.string.error_showing_last_known, text) else text
                SnapshotStatus.OK -> text
            }
        }
    val spokenFigure = "$figure ${stringResource(percentSpokenClauseRes(mode))}"
    val spokenHeadline = label?.let {
        stringResource(R.string.overview_headline_spoken, listOfNotNull(window.group, it).joinToString(", "))
    }
    val spokenSeverity = if (validity == ReadingValidity.CURRENT && window != null &&
        window.severity != Severity.HEALTHY && window.severity != Severity.UNKNOWN) {
        stringResource(SeverityPalette.labelRes(window.severity))
    } else null
    val spoken = listOfNotNull(
        account.provider.displayName,
        identityLabel,
        spokenHeadline,
        spokenFigure,
        stamp,
        stringResource(R.string.overview_no_usage_data).takeIf { window == null },
        stringResource(SeverityPalette.labelRes(Severity.UNKNOWN)).takeIf { window != null && !presentation.hasFigure },
        validityLabel,
        stringResource(R.string.overview_sign_in_expired).takeIf { expired },
        spokenSeverity,
        message,
        stringResource(R.string.usage_conflicting_availability).takeIf { window?.hasConflictingAvailability == true },
    ).joinToString(", ")
    val figureColor = if (expired || !presentation.hasFigure || validity != ReadingValidity.CURRENT) UsageColors.Faint
        else SeverityPalette.figureColor(window?.severity ?: Severity.UNKNOWN)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(DialRadius)
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .clip(shape).background(UsageColors.Surface)
            .then(if (onClick != null) Modifier.indication(interaction, ripple()) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) {
                    role = Role.Button
                    semanticsOnClick { onClick(); true }
                }
            }
            .padding(start = 12.dp, end = 12.dp, top = if (compact) 12.dp else 16.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DialGap),
    ) {
        // A different headline starts with its own arc and figure.
        key(account.localId, entry.headlineWindowId) {
            DialReading(account.provider, window, presentation, expired, figure, figureColor, compact)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                account.provider.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 20.sp),
                color = UsageColors.Ink, textAlign = TextAlign.Center,
            )
            if (identityLabel != null) Text(identityLabel, style = MaterialTheme.typography.bodySmall,
                color = UsageColors.Muted, textAlign = TextAlign.Center)
            if (!compact) Text(headlineLine, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint,
                textAlign = TextAlign.Center)
            if (validityLabel != null && (expired || message == null)) {
                Text(validityLabel, style = MaterialTheme.typography.bodySmall,
                    color = if (expired) UsageColors.NegativeText else UsageColors.Muted, textAlign = TextAlign.Center)
            }
            if (message != null) Text(message, style = MaterialTheme.typography.bodySmall,
                color = UsageColors.Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DialReading(
    provider: ProviderId,
    window: UsageWindow?,
    presentation: WindowPresentation,
    expired: Boolean,
    figure: String,
    figureColor: Color,
    compact: Boolean,
) {
    val diameter = if (compact) 64.dp else 104.dp
    val stroke = if (compact) 5.dp else 8.dp
    val figureStyle = UsageType.Figure.copy(fontSize = if (compact) 13.sp else 22.sp, lineHeight = if (compact) 16.sp else 28.sp)
    val figureOutside = LocalDensity.current.fontScale >= 1.2f
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.Center) {
            DialRing(presentation, window?.severity ?: Severity.UNKNOWN, expired, diameter, stroke)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
                ProviderLogo(provider, Modifier.size(if (compact) 16.dp else 30.dp))
                if (!figureOutside) DialFigure(window, presentation, figure, figureColor, figureStyle)
            }
        }
        if (figureOutside) DialFigure(window, presentation, figure, figureColor, figureStyle)
    }
}

@Composable
private fun DialFigure(window: UsageWindow?, presentation: WindowPresentation, figure: String, color: Color, style: TextStyle) {
    key(figureFade(window?.remainingPercent != null, presentation.exhausted)) {
        FadingFigure(figure, color, style)
    }
}

@Composable
private fun DialRing(presentation: WindowPresentation, severity: Severity, expired: Boolean, diameter: Dp, stroke: Dp) {
    val motion = UsageMotion.current
    val target = (presentation.fill ?: 0.0).toFloat()
    val progress = animateFloatAsState(target, motion.move(), label = "overviewDial")
    val track = UsageColors.Rule
    val fill = when {
        presentation.fill == null || presentation.fillState == FillState.NO_FILL -> null
        expired -> UsageColors.Faint
        presentation.fillState == FillState.DATA && (severity == Severity.LOW || severity == Severity.EXHAUSTED) -> UsageColors.Warning
        else -> SeverityPalette.fill(presentation.fillState)
    }
    Box(Modifier.size(diameter).drawWithCache {
        val width = stroke.toPx()
        val inset = Offset(width / 2, width / 2)
        val bounds = Size(size.width - width, size.height - width)
        val outline = Stroke(width, cap = StrokeCap.Round)
        onDrawBehind {
            drawCircle(track, radius = (size.minDimension - width) / 2, style = outline)
            val fraction = if (motion.enabled) progress.value else target
            if (fill != null && fraction > 0f) {
                drawArc(fill, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                    topLeft = inset, size = bounds, style = outline)
            }
        }
    })
}

@Composable
private fun AddDialAccountTile(onAddAccount: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.overview_add_account)
    val interaction = remember { MutableInteractionSource() }
    val contour = UsageColors.Control
    Column(
        modifier.heightIn(min = 178.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onAddAccount)
            .clip(RoundedCornerShape(DialRadius))
            .indication(interaction, ripple())
            .drawWithCache {
                val width = 1.dp.toPx()
                val inset = width / 2
                val outline = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                val radius = DialRadius.toPx() - inset
                onDrawBehind {
                    drawRoundRect(contour, topLeft = Offset(inset, inset), size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(radius), style = outline)
                }
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                semanticsOnClick { onAddAccount(); true }
            }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DialGap, Alignment.CenterVertically),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = UsageColors.Muted, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted, textAlign = TextAlign.Center)
    }
}
