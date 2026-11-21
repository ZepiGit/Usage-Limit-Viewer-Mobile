package com.usagelimits.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.FigureFont
import com.usagelimits.core.settings.TextFont
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.face
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val textLabels = mapOf(
    TextFont.ROBOTO to R.string.settings_font_roboto,
    TextFont.ROBOTO_FLEX to R.string.settings_font_roboto_flex,
    TextFont.IBM_PLEX_SANS to R.string.settings_font_ibm_plex_sans,
    TextFont.FIGTREE to R.string.settings_font_figtree,
)

private val figureLabels = mapOf(
    FigureFont.ROBOTO_MONO to R.string.settings_font_roboto_mono,
    FigureFont.JETBRAINS_MONO to R.string.settings_font_jetbrains_mono,
    FigureFont.IBM_PLEX_MONO to R.string.settings_font_ibm_plex_mono,
)

private val fontNotices = listOf(
    textLabels.getValue(TextFont.ROBOTO_FLEX) to R.raw.font_license_roboto_flex,
    textLabels.getValue(TextFont.IBM_PLEX_SANS) to R.raw.font_license_ibm_plex_sans,
    textLabels.getValue(TextFont.FIGTREE) to R.raw.font_license_figtree,
    figureLabels.getValue(FigureFont.ROBOTO_MONO) to R.raw.font_license_roboto_mono,
    figureLabels.getValue(FigureFont.JETBRAINS_MONO) to R.raw.font_license_jetbrains_mono,
    figureLabels.getValue(FigureFont.IBM_PLEX_MONO) to R.raw.font_license_ibm_plex_mono,
)

@Composable
internal fun AppearanceFonts(
    settings: AppSettings,
    onTextFontChange: (TextFont) -> Unit,
    onFigureFontChange: (FigureFont) -> Unit,
    licenceReadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FontPickerHeading(R.string.settings_text_font, R.string.settings_text_font_subtitle)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0..1) {
                        val font = TextFont.entries[row * 2 + column]
                        val selected = font == settings.textFont
                        FontChoice(selected, { onTextFontChange(font) }, Modifier.weight(1f)) {
                            Text(stringResource(textLabels.getValue(font)),
                                style = font.face.style(14, 20, emphasised = selected),
                                textAlign = TextAlign.Center, color = UsageColors.Ink)
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FontPickerHeading(R.string.settings_figure_font, R.string.settings_figure_font_subtitle)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (font in FigureFont.entries) {
                FontChoice(font == settings.figureFont, { onFigureFontChange(font) }, Modifier.weight(1f)) {
                    Text(percentFigureText("61"), Modifier.clearAndSetSemantics {},
                        style = font.face.FigureLarge, color = UsageColors.Ink)
                    Text(stringResource(figureLabels.getValue(font)), style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 11.sp, lineHeight = 14.sp), textAlign = TextAlign.Center, color = UsageColors.Muted)
                }
            }
        }
    }

    var showLicences by rememberSaveable { mutableStateOf(false) }
    TextAction(stringResource(R.string.settings_font_licences), { showLicences = true }, Modifier.heightIn(min = 48.dp))
    if (showLicences) FontLicences(licenceReadDispatcher) { showLicences = false }
}

@Composable
private fun FontPickerHeading(title: Int, subtitle: Int) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
    }
}

@Composable
private fun FontChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Column(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(shape)
            .background(if (selected) UsageColors.Raised else UsageColors.Surface)
            .border(if (focused) 3.dp else 1.dp, if (focused || selected) UsageColors.Ink else UsageColors.Rule, shape)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, interactionSource = null, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun FontLicences(readDispatcher: CoroutineDispatcher, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val notices by produceState<List<String>?>(null, resources, readDispatcher) {
        value = withContext(readDispatcher) {
            fontNotices.map { (_, raw) -> resources.openRawResource(raw).bufferedReader().use { it.readText() } }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UsageColors.Raised,
        title = { Text(stringResource(R.string.settings_font_licences), style = MaterialTheme.typography.titleMedium) },
        text = {
            val documents = notices
            if (documents == null) Spinner() else LazyColumn(
                Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(fontNotices, key = { _, notice -> notice.first }) { index, (label, _) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
                        Text(documents[index], style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge) }
        },
    )
}
