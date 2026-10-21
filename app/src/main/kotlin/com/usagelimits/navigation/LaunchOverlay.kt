package com.usagelimits.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageColors

private const val ICON_IN_MS = 320
private const val OVERLAY_OUT_MS = 220

// Process-scoped so rotation and returning from the background do not replay the launch.
internal var launchOverlayShown = false

/**
 * The launcher icon rising out of the app background on process start, then dissolving to
 * reveal content that is already composed underneath. Skipped when system animations are off.
 */
@Composable
fun LaunchOverlay() {
    val motionEnabled = LocalMotionEnabled.current
    var visible by remember { mutableStateOf(!launchOverlayShown && motionEnabled) }
    launchOverlayShown = true
    if (!visible) return

    val icon = AppIcon.current(LocalContext.current).foreground
    val reveal = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(ICON_IN_MS, easing = EaseOutBack))
        overlayAlpha.animateTo(0f, tween(OVERLAY_OUT_MS, easing = LinearEasing))
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
