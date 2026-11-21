package com.usagelimits.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.ProviderId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usagelimits.ui.theme.LocalAppTheme
import com.usagelimits.ui.theme.UsageColors

val LocalProviderIcons = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }

fun providerLogoResource(provider: ProviderId, iconId: String? = null): Int = ProviderIconCatalog.selected(provider, iconId).drawable

/**
 * The provider's chosen logo. Where the place is one of CH-3's [figure] slots and Claude shows one
 * of its two Claude Code icons, the Claude Code figure in [mood] takes the logo's place instead.
 */
@Composable
fun ProviderLogo(
    provider: ProviderId,
    modifier: Modifier = Modifier,
    figure: FigureSlot? = null,
    mood: ClaudeCodeMood = ClaudeCodeMood.IDLE,
) {
    val choice = ProviderIconCatalog.selected(provider, LocalProviderIcons.current[provider.id])
    val fill = if (figure != null) choice.claudeCodeFill() else null
    if (fill != null && figure != null) {
        ClaudeCodeFigure(mood, fill, figure, modifier, contentDescription = provider.displayName)
    } else {
        ProviderArtwork(choice, provider.displayName, modifier)
    }
}

@Composable
internal fun ProviderArtwork(choice: ProviderIconChoice, description: String?, modifier: Modifier) =
    ProviderArtwork(choice.drawable, choice.whiteInk, description, modifier)

@Composable
internal fun ProviderArtwork(@androidx.annotation.DrawableRes drawable: Int, whiteInk: Boolean, description: String?, modifier: Modifier) {
    val tint = if (whiteInk && LocalAppTheme.current.isLight) ColorFilter.tint(UsageColors.Ink) else null
    Image(painterResource(drawable), description, modifier, colorFilter = tint)
}

/** The logo at 70 % of a [size] box; the Claude Code figure, where one applies, takes the whole box. */
@Composable
fun ProviderBadge(
    provider: ProviderId,
    size: Dp = 40.dp,
    figure: FigureSlot? = null,
    mood: ClaudeCodeMood = ClaudeCodeMood.IDLE,
) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val figureShown = figure != null &&
            ProviderIconCatalog.selected(provider, LocalProviderIcons.current[provider.id]).claudeCodeFill() != null
        ProviderLogo(provider, if (figureShown) Modifier.fillMaxSize() else Modifier.size(size * 0.70f), figure, mood)
    }
}
