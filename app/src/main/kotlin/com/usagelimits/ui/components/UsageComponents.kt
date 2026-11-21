package com.usagelimits.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.percentSpokenClauseRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.text.windowLabelRes
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.fillFraction
import com.usagelimits.core.model.percentToken
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.readingValidity
import com.usagelimits.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType
import kotlin.math.roundToInt

private val BarRadius = 3.dp
private val BarShape = RoundedCornerShape(BarRadius)
@Composable
private fun segmentColors(): List<Color> {
    val palette = com.usagelimits.ui.theme.LocalUsagePalette.current
    return remember(palette) { listOf(palette.Data, palette.Ink, palette.Faint, palette.Muted) }
}

/**
 * A quota bar for one presented window.
 *
 * Fill length encodes the displayed figure: what is left in LEFT mode, so a long bar reads as
 * good — the battery-meter direction — and what is used in USED, where full means spent, as
 * Claude and Codex draw it. The colour comes from the presentation's fill state in both
 * modes: stale is grey and unknown, failed or exhausted is the bare track, except in USED, where
 * an exhausted window *is* full and its fill takes the data hue rather than vanishing next to a
 * "100" figure. Only the length animates.
 */
@Composable
fun UsageBar(
    presentation: WindowPresentation,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    // The length tracks the figure even while nothing is painted, so a bar that starts painting
    // grows from its figure, not from empty.
    QuotaBarTrack((presentation.fill ?: 0.0).toFloat(), SeverityPalette.fill(presentation.fillState), modifier, height)
}

/**
 * A LEFT-mode quota bar for a figure that is not a usage window's percentage.
 *
 * The credit balance and the loading skeletons sit outside the percentage toggle, so they have no
 * mode to follow and no exhaustion to flip it; a usage window's bar comes from its presentation.
 */
@Composable
fun UsageBar(
    remainingPercent: Double?,
    severity: Severity,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val fractionValue = fillFraction(remainingPercent, false, PercentDisplay.LEFT)
    val target = (fractionValue ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    QuotaBarTrack(target, SeverityPalette.barInkFor(severity, fractionValue, PercentDisplay.LEFT), modifier, height)
}

/**
 * The track and its fill, shared by both bars so they clip and animate identically.
 *
 * The animated length is read only while drawing. Read in composition it would recompose and
 * remeasure the bar on every frame of the fill, and a display-mode flip moves every bar on
 * screen at once.
 */
@Composable
private fun QuotaBarTrack(target: Float, fill: Color?, modifier: Modifier, height: Dp) {
    val motion = UsageMotion.current
    val animated = animateFloatAsState(targetValue = target, animationSpec = motion.move(), label = "usageBar")
    val track = UsageColors.Rule

    Box(
        modifier = modifier
            .height(height)
            .clip(BarShape)
            .drawBehind {
                drawRect(track)
                // A snapped animation still lands one frame late; with motion off the bar takes its value at once.
                val fraction = if (motion.enabled) animated.value else target
                if (fraction > 0f && fill != null) {
                    // Whole pixels, as the layout this replaces produced: the edge stays crisp at every step.
                    val width = (size.width * fraction).roundToInt().toFloat()
                    // From the start edge, which is the right one in a right-to-left layout.
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRoundRect(fill, topLeft = Offset(left, 0f), size = Size(width, size.height),
                        cornerRadius = CornerRadius(BarRadius.toPx()))
                }
            },
    )
}

/** A severity dot: the only place a status hue appears. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** The "Low" / "Reconnect" mark: a dot and a word, no fill. Not drawn for a healthy account. */
@Composable
fun StatusMark(severity: Severity, modifier: Modifier = Modifier, label: String? = null) {
    if (severity == Severity.HEALTHY) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(SeverityPalette.accent(severity))
        Text(
            text = label ?: stringResource(SeverityPalette.labelRes(severity)),
            style = MaterialTheme.typography.labelMedium,
            color = UsageColors.Muted,
        )
    }
}

/**
 * One quota row: label and figure, bar, reset countdown.
 *
 * The whole row is collapsed into a single spoken sentence for screen readers, because
 * hearing four disconnected fragments per window is unusable once an account has five of them.
 */
@Composable
fun UsageWindowRow(
    window: UsageWindow,
    nowMs: Long,
    mode: PercentDisplay,
    modifier: Modifier = Modifier,
    fetchedAt: Long? = null,
    /** The whole account is old or failed: the figure and bar go grey. */
    stale: Boolean = false,
) {
    // Past its reset since the fetch, the figure is the spent window's: stale treatment, as on widgets.
    val validity = window.readingValidity(stale, fetchedAt, nowMs)
    val severity = if (validity == ReadingValidity.CURRENT) window.severity else Severity.STALE
    val p = window.presentation(validity, mode)
    // The mode-aware figure token, laid out by the language's percent template.
    val percent = percentFigureText(p.token)
    val shownSide = stringResource(percentSideWordRes(mode))
    val spokenClause = stringResource(percentSpokenClauseRes(mode))
    // Names the app gives a window are words of the app and are translated; a label the provider
    // reported is data and is shown as it came.
    val appLabelRes = windowLabelRes(window.label, window.category, window.scope)
    val label = when {
        window.scope != UsageScope.GLOBAL -> window.label
        appLabelRes != null -> stringResource(appLabelRes)
        window.group != null && window.category == WindowCategory.FIVE_HOUR ->
            stringResource(R.string.window_label_five_hour)
        window.group != null && window.category == WindowCategory.WEEKLY ->
            stringResource(R.string.window_label_weekly)
        window.group != null && window.category == WindowCategory.MONTHLY ->
            stringResource(R.string.window_label_monthly)
        window.label.equals("Five Hour Limit Remaining", ignoreCase = true) ->
            stringResource(R.string.window_label_five_hour)
        window.label.equals("Weekly Limit Remaining", ignoreCase = true) -> stringResource(R.string.window_label_weekly)
        else -> window.label
    }
    val reset = Countdown.resetLabel(window.resetAt, nowMs, rememberCountdownTexts())
    // A segment is a usage breakdown: its figure is always the used share, and says so in both modes.
    val segmentLegends = segmentLegends(window)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$label, $percent $spokenClause" +
            (reset?.let { ", $it" } ?: "") +
            segmentLegends.joinToString("") { ", $it" }
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted, maxLines = 2)
            // A value the provider never stated is a quiet dash, not a figure-sized one. A bare
            // percentage is ambiguous, so the small word after the figure says which side it counts.
            if (!p.hasFigure) {
                Text(percent, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
            } else {
                FigureWithSide(percent, shownSide, SeverityPalette.figureColor(severity), UsageType.Figure,
                    MaterialTheme.typography.bodySmall, spacing = 4.dp, alignment = Alignment.BottomEnd)
            }
        }
        if (window.segments.isEmpty()) {
            UsageBar(p, Modifier.fillMaxWidth())
        } else {
            SegmentedUsageBar(window, Modifier.fillMaxWidth())
            Text(stringResource(R.string.usage_breakdown), style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            SegmentLegend(window, segmentLegends)
        }
        if (window.hasConflictingAvailability) ConflictingAvailabilityNote()
        if (reset != null) Text(reset, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
    }
}

/**
 * A window's reported breakdown as one bar: each segment's used share in its own colour, then the
 * rest of the used total that no segment names, on the track. Segments count what is used in both
 * display modes, as the provider reports them.
 */
@Composable
fun SegmentedUsageBar(window: UsageWindow, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = segmentColors()
    val track = UsageColors.Rule
    val other = UsageColors.Faint
    Box(modifier.height(height).clip(BarShape).drawBehind {
        drawRect(track)
        var offset = 0.0
        val total = (window.usedPercent ?: window.segments.sumOf { it.usedPercent }).coerceIn(0.0, 100.0)
        window.segments.forEachIndexed { index, segment ->
            val end = (offset + segment.usedPercent).coerceIn(offset, total)
            drawRect(colors[index % colors.size],
                topLeft = Offset(size.width * (offset / 100).toFloat(), 0f),
                size = Size(size.width * ((end - offset) / 100).toFloat(), size.height))
            offset = end
        }
        if (total - offset >= 0.5) {
            drawRect(other,
                topLeft = Offset(size.width * (offset / 100).toFloat(), 0f),
                size = Size(size.width * ((total - offset) / 100).toFloat(), size.height))
        }
    })
}

/**
 * One line per segment of [window]: its colour and [legends] ("Grok 4 usage: 30%"), in order.
 * A reading that is not [current] (expired, failed, old or past its reset) shows its legend in
 * faint ink, without the segment colours, as the rest of its figures.
 */
@Composable
fun SegmentLegend(window: UsageWindow, legends: List<String>, modifier: Modifier = Modifier, current: Boolean = true) {
    val colors = segmentColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        window.segments.forEachIndexed { index, _ ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(if (current) colors[index % colors.size] else UsageColors.Faint, size = 6.dp)
                Text(legends[index], style = MaterialTheme.typography.bodySmall,
                    color = if (current) UsageColors.Muted else UsageColors.Faint)
            }
        }
    }
}

/** The provider calls a limit blocked although it reports some of it left. */
@Composable
fun ConflictingAvailabilityNote(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(UsageColors.Warning, size = 6.dp)
        Text(stringResource(R.string.usage_conflicting_availability),
            style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
    }
}

/** The spoken and shown legend of each segment of [window]: what each part used. */
@Composable
fun segmentLegends(window: UsageWindow): List<String> = window.segments.map {
    stringResource(R.string.usage_segment_legend, it.label,
        percentFigureText(percentToken(it.usedPercent, exhausted = false, mode = PercentDisplay.LEFT)))
}

/**
 * Fade through when [contentKey] changes; other state updates replace the content in place.
 * The slot resizes around [alignment]. Outgoing content is drawn but not exposed to accessibility.
 */
@Composable
private fun <T> FadeThrough(
    state: T,
    modifier: Modifier,
    alignment: Alignment,
    contentKey: (T) -> Any?,
    content: @Composable (T) -> Unit,
) {
    val motion = UsageMotion.current
    if (!motion.enabled) {
        // Even a snapped transition keeps the outgoing content for one more frame.
        Box(modifier, contentAlignment = alignment) { content(state) }
        return
    }
    // AnimatedContent keeps calling the lambda it was given for content on its way out, so the
    // current state is read through a State, not captured.
    val current by rememberUpdatedState(state)
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            fadeIn(motion.enterAfterExit()) togetherWith fadeOut(motion.exit()) using
                SizeTransform(clip = false) { from, to ->
                    // A slot that narrows waits until the old, wider content has left, or its
                    // neighbours would slide over it; one that widens makes room at once.
                    if (to.width < from.width) motion.moveAfterExit() else motion.move()
                }
        },
        contentAlignment = alignment,
        contentKey = contentKey,
        label = "fadeThrough",
    ) { shown ->
        Box(if (contentKey(shown) == contentKey(current)) Modifier else Modifier.clearAndSetSemantics {}) { content(shown) }
    }
}

private data class FigureReading(val figure: String, val side: String, val color: Color) {
    val words = figure to side
}

/**
 * A percentage and the word that says which side it counts ("60% left"), as one unit.
 *
 * They change together, by fading through: on a display-mode flip the old figure leaves with its
 * own word before the new pair arrives, so no frame shows a figure beside the other side's word.
 * The colour travels with the figure: an outgoing figure keeps the colour it had. A colour that
 * changes under the same figure and word (a reading going stale) changes in place: nothing that
 * is read has changed, so nothing leaves.
 *
 * The unit animates from its own previous reading. Where the same slot can come to show another
 * window or another account, the caller gives it that identity with `key`, so one window's figure
 * never fades out under another window's name.
 */
@Composable
fun FigureWithSide(
    figure: String,
    side: String,
    color: Color,
    figureStyle: TextStyle,
    sideStyle: TextStyle,
    spacing: Dp,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomStart,
    /** Lifts the word to the figure's baseline where the two styles differ much in size. */
    sideModifier: Modifier = Modifier,
) {
    FadeThrough(FigureReading(figure, side, color), modifier, alignment, contentKey = FigureReading::words) { reading ->
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Text(reading.figure, style = figureStyle, color = reading.color)
            Text(reading.side, sideModifier, style = sideStyle, color = UsageColors.Muted)
        }
    }
}

/**
 * A figure alone ("60%"), for a row whose side is named once for the whole screen.
 *
 * It fades through when the figure changes (a mode flip, a refresh) and changes colour in place,
 * as [FigureWithSide] does. The caller keys it by window, so another window in the same slot
 * starts from its own figure.
 */
@Composable
fun FadingFigure(figure: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    FadeThrough(FigureReading(figure, "", color), modifier, Alignment.CenterEnd, contentKey = FigureReading::words) { reading ->
        Text(reading.figure, style = style, color = reading.color, maxLines = 1)
    }
}

/**
 * A short label that changes in step with the figures it names ("% left" to "% used"): it fades
 * through on the same timing as [FadingFigure], so the old word stays while the old figures leave
 * and the new word arrives with the new figures. With motion off both change on the same frame.
 */
@Composable
fun FadingLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    FadeThrough(text, modifier, Alignment.Center, contentKey = { it }) { shown ->
        Text(shown, style = style, color = color, maxLines = 1)
    }
}

/**
 * One card per group: a tone step on the ground, no border, radius 8.
 *
 * A card that can be pressed takes [onClick] here rather than a `clickable` on [modifier]. The
 * press is taken by the card's whole box, corners included, as before; only its highlight is
 * drawn inside the clip, so it does not spill past the rounded corners (the rule of #26: clip
 * the indication, not the target).
 */
@Composable
fun UsageCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .background(UsageColors.Surface)
            .then(if (onClick != null) Modifier.indication(interaction, ripple()) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** A 1px hairline between ledger rows inside a card. */
@Composable
fun RuleDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(UsageColors.Rule))
}

/** 32dp above (24 plus the list's 8 gap), 8 below. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
        style = MaterialTheme.typography.titleMedium,
        color = UsageColors.Ink,
    )
}

/** The page title row: a text-face title and subtitle, one bare action at the right edge. */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    stamp: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = UsageColors.Ink)
            if (stamp != null) Text(stamp, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
        }
        action?.invoke()
    }
}

/**
 * A labelled switch.
 *
 * Shared rather than duplicated: the settings screen and one account's detail screen both need
 * exactly this row, and a second copy is how two switches end up looking slightly different.
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    /** What a screen reader calls the switch; the title unless the title alone would not say it. */
    description: String = title,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) UsageColors.Ink else UsageColors.Faint,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = UsageColors.Muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description },
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UsageColors.Ground,
                checkedTrackColor = UsageColors.Ink,
                checkedBorderColor = UsageColors.Ink,
                uncheckedThumbColor = UsageColors.Faint,
                uncheckedTrackColor = UsageColors.Raised,
                uncheckedBorderColor = UsageColors.Control,
            ),
        )
    }
}

// MARK: - Controls

private val ControlPadding = PaddingValues(horizontal = 16.dp)

/** The screen's one filled primary: ink fill, ground label. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = ControlPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = UsageColors.Ink,
            contentColor = UsageColors.OnInk,
            disabledContainerColor = UsageColors.Raised,
            disabledContentColor = UsageColors.Muted,
        ),
        modifier = modifier.height(44.dp),
    ) {
        if (busy) {
            Spinner(color = UsageColors.Muted)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Transparent, 1px control border, ink label. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = UsageColors.Ink,
    leading: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = ControlPadding,
        border = BorderStroke(1.dp, UsageColors.Control),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = UsageColors.Muted),
        modifier = modifier.height(44.dp),
    ) {
        leading?.let { it(); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Text only, flush with the content edge, 44dp touch. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = UsageColors.Ink, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            // The whole box takes the press; its highlight alone gets the corners every control has.
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .clip(MaterialTheme.shapes.small)
            .indication(interaction, ripple()),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** An in-place wait: 16dp, 2dp stroke, muted. */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = UsageColors.Muted) {
    CircularProgressIndicator(modifier.size(16.dp), strokeWidth = 2.dp, color = color)
}

/**
 * A row of mutually exclusive choices in a 1px frame. Cells are 36dp tall at least and grow with the text; each keeps
 * a 48dp touch target, and the row scrolls sideways rather than wrapping.
 */
@Composable
fun SegmentedRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val rule = UsageColors.Rule
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                // The cells' height less their 8dp touch padding plus the 2dp frame margin each side (36dp at base size).
                // Following the cells keeps the frame around a cell that grew with the text.
                val h = size.height - 12.dp.toPx()
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    rule,
                    topLeft = Offset(stroke / 2, (size.height - h) / 2),
                    size = Size(size.width - stroke, h),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
            .padding(horizontal = 2.dp),
    ) { content() }
}

@Composable
fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    // The touch target takes the press; the pill draws it, so the ripple has the pill's size and corners.
    val interaction = remember { MutableInteractionSource() }
    val motion = UsageMotion.current
    // Read only while drawing: the pill fades without recomposing the segment.
    val selection = animateFloatAsState(if (selected) 1f else 0f, motion.stateChange(), label = "segment")
    val raised = UsageColors.Raised
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            // 8dp each side keeps the 48dp touch target at the base height and grows with the cell.
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(MaterialTheme.shapes.small)
                .drawBehind {
                    drawRect(raised, alpha = if (motion.enabled) selection.value else if (selected) 1f else 0f)
                }
                .indication(interaction, ripple())
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = if (selected) UsageColors.Ink else UsageColors.Muted,
            )
        }
    }
}

internal fun Modifier.dashedOutline(color: Color, radius: Dp = 8.dp): Modifier = drawWithCache {
    val width = 1.dp.toPx()
    val inset = width / 2
    val outline = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    val corner = CornerRadius(radius.toPx() - inset)
    onDrawBehind {
        drawRoundRect(color, topLeft = Offset(inset, inset), size = Size(size.width - width, size.height - width),
            cornerRadius = corner, style = outline)
    }
}

/** A sentence in a dashed 1px box: the empty state. */
@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    val contour = UsageColors.Control
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .dashedOutline(contour)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
    }
}

/** A failure inside the block it belongs to: a dot, one sentence, and a way to try again. */
@Composable
fun ErrorBlock(
    message: String,
    modifier: Modifier = Modifier,
    dot: Color = UsageColors.Negative,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(dot, Modifier.padding(top = 7.dp), size = 6.dp)
        Column(Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            if (onRetry != null) TextAction(stringResource(R.string.action_retry), onRetry)
        }
    }
}

/** A grey placeholder at the size of the thing it stands in for. */
@Composable
fun SkeletonBar(height: Dp, fraction: Float = 1f, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(BarShape)
            .background(UsageColors.Raised),
    )
}

/**
 * A text field with its label above and a 48dp box: surface fill, 1px control border, 1.5dp ink
 * border while focused.
 */
@Composable
fun LedgerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = UsageColors.Muted)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = UsageColors.Ink),
            cursorBrush = SolidColor(UsageColors.Ink),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(UsageColors.Surface)
                        .border(
                            if (focused) 1.5.dp else 1.dp,
                            when {
                                isError -> UsageColors.NegativeText
                                focused -> UsageColors.Ink
                                else -> UsageColors.Control
                            },
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = UsageColors.Faint, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) UsageColors.NegativeText else UsageColors.Faint,
            )
        }
    }
}
