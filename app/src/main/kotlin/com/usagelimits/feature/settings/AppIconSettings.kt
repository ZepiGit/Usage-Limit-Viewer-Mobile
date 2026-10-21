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
import com.usagelimits.MainActivity
import com.usagelimits.R
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

enum class AppIcon(val alias: String, val label: String, @DrawableRes val foreground: Int) {
    CURRENT(".IconCurrent", "Default", R.drawable.ic_launcher_foreground),
    GAUGE_COIN(".IconGaugeCoin", "Gauge Coin", R.mipmap.ic_launcher_gauge_coin_foreground),
    AI_GAUGE(".IconAiGauge", "Gauge", R.mipmap.ic_launcher_ai_gauge_foreground),
    AI_HOURGLASS(".IconAiHourglass", "Hourglass", R.mipmap.ic_launcher_ai_hourglass_foreground),
    AI_REFRESH_RING(".IconAiRefreshRing", "Refresh Ring", R.mipmap.ic_launcher_ai_refresh_ring_foreground),
    AI_BATTERY(".IconAiBattery", "Battery", R.mipmap.ic_launcher_ai_battery_foreground);

    companion object {
        fun current(context: Context): AppIcon = entries.firstOrNull {
            context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + it.alias)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: CURRENT

        fun apply(context: Context, icon: AppIcon) {
            val manager = context.packageManager
            manager.setComponentEnabledSetting(ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + icon.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filterNot { it == icon }.forEach {
                manager.setComponentEnabledSetting(ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + it.alias),
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
    var selected by remember { mutableStateOf(AppIcon.current(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("App icon")
        UsageCard {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { icon ->
                            Column(Modifier.weight(1f).selectable(selected == icon, role = Role.RadioButton, onClick = {
                                AppIcon.apply(context, icon)
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
