package com.usagelimits.feature.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.usagelimits.R
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.ProviderId
import com.usagelimits.ui.ClaudeCodeFigure
import com.usagelimits.ui.FigureSlot
import com.usagelimits.ui.ProviderArtwork
import com.usagelimits.ui.ProviderIconCatalog
import com.usagelimits.ui.ProviderIconChoice
import com.usagelimits.ui.claudeCodeFill
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/** The choice's label in the current language; reading the configuration makes a language change recompose it. */
@Composable
private fun ProviderIconChoice.labelText(): String {
    LocalConfiguration.current
    return label(LocalContext.current.resources)
}

/**
 * The provider icon rows. While Claude shows a Claude Code icon its row draws the figure in
 * [claudeMood] (the first Claude account in Overview order) and the animation switch sits under it.
 */
@Composable
fun ProviderIconSettings(
    selected: Map<String, String>,
    claudeMood: ClaudeCodeMood = ClaudeCodeMood.IDLE,
    animateClaudeCode: Boolean = true,
    onAnimateClaudeCodeChange: (Boolean) -> Unit = {},
    onChoose: (ProviderId, String) -> Unit,
) {
    var editing by remember { mutableStateOf<ProviderId?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.settings_section_provider_icons))
        UsageCard {
            ProviderId.entries.forEachIndexed { index, provider ->
                if (index > 0) RuleDivider()
                val icon = ProviderIconCatalog.selected(provider, selected[provider.id])
                val figureFill = icon.claudeCodeFill()
                Row(Modifier.fillMaxWidth().clickable { editing = provider }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (figureFill != null) {
                        ClaudeCodeFigure(claudeMood, figureFill, FigureSlot.SETTINGS_ROW, Modifier.size(30.dp))
                    } else {
                        ProviderArtwork(icon, null, Modifier.size(30.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
                        Text(icon.labelText(), style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                    }
                    Text(stringResource(R.string.action_choose), color = UsageColors.Muted, style = MaterialTheme.typography.labelLarge)
                }
                // Only the two Claude Code icons move, so only they get the switch; the figure in the
                // row above is its live preview.
                if (figureFill != null) {
                    Box(
                        Modifier.padding(start = 42.dp, bottom = 10.dp).fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium).background(UsageColors.Ground)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        ToggleRow(
                            title = stringResource(R.string.settings_animate_claude_code_title),
                            subtitle = stringResource(R.string.settings_animate_claude_code_subtitle),
                            checked = animateClaudeCode,
                            onChange = onAnimateClaudeCodeChange,
                        )
                    }
                }
            }
        }
    }
    editing?.let { provider ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.settings_provider_icon_title, provider.displayName)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderIconCatalog.choices(provider).forEach { choice ->
                        val checked = choice.id == ProviderIconCatalog.selected(provider, selected[provider.id]).id
                        Row(Modifier.fillMaxWidth().selectable(checked, role = Role.RadioButton,
                            onClick = { onChoose(provider, choice.id); editing = null }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ProviderArtwork(choice, null, Modifier.size(36.dp))
                            Text(choice.labelText(), Modifier.weight(1f).padding(horizontal = 12.dp))
                            RadioButton(checked, onClick = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_done)) } })
    }
}
