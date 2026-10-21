package com.usagelimits.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.R
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

enum class AppIcon(val alias: String, val label: String, @DrawableRes val foreground: Int) {
    CURRENT(".MainActivity", "Default", R.drawable.ic_launcher_foreground),
    GAUGE_COIN(".IconGaugeCoin", "Gauge Coin", R.mipmap.ic_launcher_gauge_coin_foreground),
    AI_GAUGE(".IconAiGauge", "Gauge", R.mipmap.ic_launcher_ai_gauge_foreground),
    AI_HOURGLASS(".IconAiHourglass", "Hourglass", R.mipmap.ic_launcher_ai_hourglass_foreground),
    AI_REFRESH_RING(".IconAiRefreshRing", "Refresh Ring", R.mipmap.ic_launcher_ai_refresh_ring_foreground),
    AI_BATTERY(".IconAiBattery", "Battery", R.mipmap.ic_launcher_ai_battery_foreground);

    companion object {
        var pending: AppIcon? = null
            private set

        fun select(icon: AppIcon) {
            pending = icon
        }

        // Launcher alias changes can close the activity, so wait until it leaves the foreground.
        fun applyPending(context: Context) {
            val icon = pending ?: return
            if (icon != current(context)) apply(context, icon)
            pending = null
        }

        private fun component(context: Context, icon: AppIcon) =
            ComponentName(context, UsageLimitsActivity::class.java.name.substringBeforeLast('.') + icon.alias)

        fun current(context: Context): AppIcon = entries.firstOrNull {
            context.packageManager.getComponentEnabledSetting(component(context, it)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: CURRENT

        fun apply(context: Context, icon: AppIcon) {
            val manager = context.packageManager
            manager.setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filterNot { it == icon }.forEach {
                manager.setComponentEnabledSetting(component(context, it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}

@Composable
fun AppIconTile(icon: AppIcon, size: Dp = 56.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color(0xFF191715))) {
        Image(painterResource(icon.foreground), null, Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.5f
            scaleY = 1.5f
        })
    }
}

@Composable
fun AppIconSettings() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIcon.pending ?: AppIcon.current(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("App icon")
        UsageCard {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { icon ->
                            Column(Modifier.weight(1f).selectable(selected == icon, role = Role.RadioButton, onClick = {
                                AppIcon.select(icon)
                                selected = icon
                            }), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(if (selected == icon) Modifier.border(1.5.dp, UsageColors.Ink, RoundedCornerShape(14.dp)) else Modifier) {
                                    AppIconTile(icon)
                                }
                                Text(icon.label, style = MaterialTheme.typography.bodySmall, color = UsageColors.Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}
