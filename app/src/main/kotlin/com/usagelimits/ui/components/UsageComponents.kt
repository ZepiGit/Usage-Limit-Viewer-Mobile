package com.usagelimits.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.core.model.percentLabel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType

private val BarShape = RoundedCornerShape(3.dp)

/**
 * A quota bar.
 *
 * Fill length encodes what is *left*, not what is used, so a long bar always reads as good —
 * the direction people expect from a battery meter. The fill is the data hue; stale is grey and
 * unknown, failed or exhausted is the bare track. Only the length animates.
 */
@Composable
fun UsageBar(
    remainingPercent: Double?,
    severity: Severity,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val target = ((remainingPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val motionEnabled = LocalMotionEnabled.current
    val animatedFraction by animateFloatAsState(targetValue = target, animationSpec = if (motionEnabled) tween(240) else snap(), label = "usageBar")
    val fraction = if (motionEnabled) animatedFraction else target
    val fill = SeverityPalette.barFill(severity)

    Box(
        modifier = modifier
            .height(height)
            .clip(BarShape)
            .background(UsageColors.Rule),
    ) {
        if (fraction > 0f && fill != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(height)
                    .clip(BarShape)
                    .background(fill),
            )
        }
    }
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
            text = label ?: SeverityPalette.label(severity),
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
    modifier: Modifier = Modifier,
    fetchedAt: Long? = null,
    /** The whole account is old or failed: the figure and bar go grey. */
    stale: Boolean = false,
) {
    val remaining = window.remainingPercent
    // Past its reset since the fetch, the figure is the spent window's: stale treatment, as on widgets.
    val severity = if (stale || (fetchedAt != null && window.resetElapsedAt(fetchedAt, nowMs))) Severity.STALE else window.severity
    val percent = percentLabel(remaining)
    val label = when {
        window.scope != com.usagelimits.core.model.UsageScope.GLOBAL -> window.label
        window.group != null && window.category == com.usagelimits.core.model.WindowCategory.FIVE_HOUR -> "5h limit"
        window.group != null && window.category == com.usagelimits.core.model.WindowCategory.WEEKLY -> "Weekly"
        window.group != null && window.category == com.usagelimits.core.model.WindowCategory.MONTHLY -> "Monthly"
        window.label.equals("Five Hour Limit Remaining", ignoreCase = true) -> "5h limit"
        window.label.equals("Weekly Limit Remaining", ignoreCase = true) -> "Weekly"
        else -> window.label
    }
    val reset = Countdown.resetLabel(window.resetAt, nowMs)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$label, $percent remaining" + (reset?.let { ", $it" } ?: "")
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted, maxLines = 2)
            // A value the provider never stated is a quiet dash, not a figure-sized one.
            if (percent == "—") {
                Text(percent, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
            } else {
                Text(percent, style = UsageType.Figure, color = SeverityPalette.figureColor(severity))
            }
        }
        UsageBar(remaining, severity, Modifier.fillMaxWidth())
        if (window.hasConflictingAvailability) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(UsageColors.Warning, size = 6.dp)
                Text("Provider reports this limit as blocked despite a positive remaining value.",
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
        }
        if (reset != null) Text(reset, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
    }
}

/** One card per group: a tone step on the ground, no border, radius 8. */
@Composable
fun UsageCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(UsageColors.Surface)
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
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = UsageColors.Ink,
        )
        trailing?.invoke()
    }
}

/** The page title row: 24/500 title, a one-line stamp under it, one bare action at the right edge. */
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
                color = UsageColors.Ink,
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
            modifier = Modifier.semantics { contentDescription = title },
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
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = UsageColors.Ink) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick),
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
 * A row of mutually exclusive choices in a 1px frame. Cells are 36dp tall but each keeps a 48dp
 * touch target, and the row scrolls sideways rather than wrapping.
 */
@Composable
fun SegmentedRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                val h = 36.dp.toPx()
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    UsageColors.Rule,
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
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) UsageColors.Raised else Color.Transparent)
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

/** A sentence in a dashed 1px box: the empty state. */
@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    UsageColors.Control,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
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
            if (onRetry != null) TextAction("Retry", onRetry)
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
