package com.usagelimits.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.usagelimits.core.model.ProviderId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usagelimits.ui.theme.LocalAppTheme
import com.usagelimits.ui.theme.UsageColors

val LocalProviderIcons = androidx.compose.runtime.staticCompositionLocalOf<Map<String, String>> { emptyMap() }

fun providerLogoResource(provider: ProviderId, iconId: String? = null): Int = ProviderIconCatalog.selected(provider, iconId).drawable

@Composable
fun ProviderLogo(provider: ProviderId, modifier: Modifier = Modifier) {
    ProviderArtwork(ProviderIconCatalog.selected(provider, LocalProviderIcons.current[provider.id]), provider.displayName, modifier)
}

@Composable
internal fun ProviderArtwork(choice: ProviderIconChoice, description: String?, modifier: Modifier) =
    ProviderArtwork(choice.drawable, choice.whiteInk, description, modifier)

@Composable
internal fun ProviderArtwork(@androidx.annotation.DrawableRes drawable: Int, whiteInk: Boolean, description: String?, modifier: Modifier) {
    val tint = if (whiteInk && LocalAppTheme.current.isLight) ColorFilter.tint(UsageColors.Ink) else null
    Image(painterResource(drawable), description, modifier, colorFilter = tint)
}

@Composable
fun ProviderBadge(provider: ProviderId, size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        ProviderLogo(provider, Modifier.size(size * 0.70f))
    }
}
