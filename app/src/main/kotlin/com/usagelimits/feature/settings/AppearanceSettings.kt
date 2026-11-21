package com.usagelimits.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.windowPresentation
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.components.FigureWithSide
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType
import com.usagelimits.ui.theme.colors
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

private val PREVIEW_AMOUNT = BigDecimal("12.40")
private val PREVIEW_TIME = LocalDate.of(2026, 1, 1).atTime(23, 31).toInstant(ZoneOffset.UTC).toEpochMilli()

@Composable
fun AppearanceSettings(settings: AppSettings, onAppThemeChange: (AppTheme) -> Unit) {
    UsageCard {
        AppearancePreview(settings)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_appearance_theme),
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
        Text(stringResource(R.string.settings_appearance_theme_subtitle),
            style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0..1) {
                        val theme = AppTheme.entries[row * 2 + column]
                        ThemeTile(theme, settings.appTheme == theme, { onAppThemeChange(theme) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearancePreview(settings: AppSettings) {
    val locale = LocalConfiguration.current.locales[0]
    val presentation = windowPresentation(18.0, Severity.LOW, ReadingValidity.CURRENT, settings.percentDisplay)
    val stamp = remember(locale) { Countdown.asOfStamp(PREVIEW_TIME, ZoneOffset.UTC, locale) }
    val duration = Countdown.format(78 * 60_000L, rememberCountdownTexts())
    val amount = remember(locale) { CreditFormat.string(PREVIEW_AMOUNT, "USD", locale) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(UsageColors.Ground)
            .padding(12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_appearance_preview).uppercase(locale),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.66.sp), color = UsageColors.Faint)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_appearance_preview_account), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = UsageColors.Ink)
            FigureWithSide(percentFigureText(presentation.token), stringResource(percentSideWordRes(presentation.side)),
                SeverityPalette.figureColor(Severity.LOW), UsageType.Figure.copy(fontSize = 18.sp, lineHeight = 22.sp),
                MaterialTheme.typography.bodySmall, spacing = 8.dp)
        }
        UsageBar(presentation, Modifier.fillMaxWidth())
        Text(stringResource(R.string.settings_appearance_preview_values, stamp, duration, amount),
            style = UsageType.Figure.copy(fontSize = 12.sp, lineHeight = 16.sp), color = UsageColors.Muted)
    }
}

@Composable
private fun ThemeTile(theme: AppTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val palette = theme.colors
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .border(when { focused -> 3.dp; selected -> 2.dp; else -> 1.dp },
                if (selected || focused) UsageColors.Ink else UsageColors.Rule, shape)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, interactionSource = null, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.size(36.dp, 24.dp).clip(RoundedCornerShape(4.dp))
                .background(palette.Ground).border(1.dp, palette.Contour, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.Ink))
            Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.Data))
        }
        Text(stringResource(themeLabel(theme)), Modifier.weight(1f),
            style = (if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall)
                .copy(fontSize = 13.sp, lineHeight = 16.sp), color = UsageColors.Ink)
    }
}

@StringRes
private fun themeLabel(theme: AppTheme): Int = when (theme) {
    AppTheme.WARM_DARK -> R.string.settings_appearance_warm_dark
    AppTheme.PURE_BLACK -> R.string.settings_appearance_pure_black
    AppTheme.COOL_GRAPHITE -> R.string.settings_appearance_cool_graphite
    AppTheme.LIGHT_PAPER -> R.string.settings_appearance_light_paper
}
