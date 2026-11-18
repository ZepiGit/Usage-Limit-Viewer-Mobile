package com.usagelimits.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.usagelimits.feature.settings.AppIcon
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.UsageColors

// Process-scoped so rotation and returning from the background do not replay the launch.
internal var launchOverlayShown = false

/**
 * The launcher icon rising out of the app background on process start, then dissolving to
 * reveal content that is already composed underneath. Skipped when system animations are off.
 */
@Composable
fun LaunchOverlay() {
    val motion = UsageMotion.current
    var visible by remember { mutableStateOf(!launchOverlayShown && motion.enabled) }
    launchOverlayShown = true
    if (!visible) return

    val icon = AppIcon.current(LocalContext.current).foreground
    val reveal = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, motion.launchReveal())
        overlayAlpha.animateTo(0f, motion.launchDissolve())
        visible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(UsageColors.Ground)
            .testTag("launchOverlay"),
        contentAlignment = Alignment.Center,
    ) {
        // Adaptive foregrounds keep their art inside the inner 2/3, so the box is oversized.
        Image(painterResource(icon), null, Modifier.size(192.dp).graphicsLayer {
            val scale = 0.8f + 0.2f * reveal.value
            scaleX = scale
            scaleY = scale
            alpha = reveal.value.coerceIn(0f, 1f)
        })
    }
}
