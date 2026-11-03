package com.usagelimits.widget

import sh.calvin.reorderable.ReorderableItem
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.runtime.collectAsState
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.R
import com.usagelimits.ui.ProviderLogo
import com.usagelimits.ui.components.ErrorBlock
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.percentSideCaptionRes
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.text.percentFigure
import com.usagelimits.ui.theme.UsageType
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.launch
import org.json.JSONArray

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val container = (application as UsageLimitsApp).container
        val receiver = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        val kind = when {
            receiver.contains("MiniRings") -> "Mini Rings"
            receiver.contains("Minimal") -> "Account Rings"
            receiver.contains("Compact") -> "Usage Summary"
            else -> "Usage Bars"
        }
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(widgetId)
        val providerInfo = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        val targetColumns = if (android.os.Build.VERSION.SDK_INT >= 31) providerInfo?.targetCellWidth?.takeIf { it > 0 } else null
        val targetRows = if (android.os.Build.VERSION.SDK_INT >= 31) providerInfo?.targetCellHeight?.takeIf { it > 0 } else null
        val placementMetrics = WidgetMetrics.fromPlacement(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
            targetColumns ?: if (kind == "Mini Rings") 1 else 3, targetRows ?: if (kind == "Mini Rings" || kind == "Usage Summary") 1 else 2)
        setContent {
            val settings by container.settingsStore.settings.collectAsState(initial = com.usagelimits.core.settings.AppSettings())
            UsageLimitsTheme(providerIcons = settings.providerIcons) {
                var initial by remember { mutableStateOf<WidgetConfigEntity?>(null) }
                var loaded by remember { mutableStateOf(false) }
                val accounts by container.repository.observeAccountUsage().collectAsState(emptyList())
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(widgetId) {
                    try { initial = container.widgetConfigDao.get(widgetId); loaded = true }
                    catch (_: Exception) { error = getString(R.string.widget_config_load_failed) }
                }
                if (loaded) WidgetConfigScreen(accounts, initial, kind, saving, error, settings.percentDisplay,
                    onCancel = { finish() }, onSave = { scope, accountId, providerId, style, custom, percentOverride ->
                        if (!saving) {
                            saving = true
                            lifecycleScope.launch {
                                try {
                                    container.widgetConfigDao.upsert(WidgetConfigEntity(
                                        appWidgetId = widgetId, scope = scope.name, accountId = accountId,
                                        provider = providerId, updatedAt = System.currentTimeMillis(),
                                        // The old flag is written alongside the opacity so a
                                        // downgrade still reads the widget the way it was saved.
                                        transparent = style.background.isTransparent,
                                        backgroundArgb = style.background.argb,
                                        backgroundOpacity = style.background.clampedOpacity,
                                        textTone = style.textTone.name,
                                        percentDisplay = percentOverride,
                                        customAccountIdsJson = JSONArray(custom).toString(),
                                        layoutMetricsJson = (WidgetMetrics.fromJson(initial?.layoutMetricsJson ?: "{}") ?: placementMetrics).toJson()))
                                    WidgetUpdater.refreshAll(this@WidgetConfigActivity)
                                    // A saved configuration can change what the widget shows
                                    // without new data; the next staleness/reset boundary moves
                                    // with it, so recompute the one coalesced repaint.
                                    WidgetPresentationWorker.scheduleNext(this@WidgetConfigActivity)
                                    setResult(RESULT_OK, result); finish()
                                } catch (_: Exception) { saving = false; error = getString(R.string.widget_config_save_failed) }
                            }
                        }
                    })
                else Box(Modifier.fillMaxSize().background(UsageColors.Ground).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    if (error == null) Spinner() else Text(error!!, color = UsageColors.NegativeText)
                }
            }
        }
    }
}

@Composable
internal fun WidgetConfigScreen(accounts: List<AccountUsage>, initial: WidgetConfigEntity?, kind: String,
    saving: Boolean, error: String?,
    // The app's mode; the preview resolves this widget's own choice over it, and required so it cannot drift to LEFT.
    percentDisplay: PercentDisplay,
    onCancel: () -> Unit,
    onSave: (WidgetScope, String?, String?, WidgetStyle, List<String>, String?) -> Unit) {
    var scopeName by rememberSaveable { mutableStateOf(WidgetScope.fromName(initial?.scope).name) }
    var accountId by rememberSaveable { mutableStateOf(initial?.accountId) }
    var providerId by rememberSaveable { mutableStateOf(initial?.provider) }
    val savedStyle = remember(initial) {
        WidgetStyle.fromStored(initial?.backgroundArgb, initial?.backgroundOpacity, initial?.transparent ?: false, initial?.textTone)
    }
    var backgroundArgb by rememberSaveable { mutableStateOf(savedStyle.background.argb) }
    var opacity by rememberSaveable { mutableStateOf(savedStyle.background.clampedOpacity) }
    var textToneName by rememberSaveable { mutableStateOf(savedStyle.textTone.name) }
    val style = WidgetStyle(WidgetBackground(backgroundArgb, opacity), WidgetTextTone.fromName(textToneName))
    var modeOverrideName by rememberSaveable { mutableStateOf(initial?.percentDisplay?.let { PercentDisplay.fromNameOrNull(it)?.name }) }
    val savedIds = remember(initial) { runCatching {
        val a = JSONArray(initial?.customAccountIdsJson ?: "[]"); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(savedIds) }
    var order by rememberSaveable { mutableStateOf(savedIds) }
    val currentOrder = order.filter { id -> accounts.any { it.account.localId == id } } + accounts.map { it.account.localId }.filter { it !in order }
    val liveOrder by rememberUpdatedState(currentOrder)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderState = sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key.toString()
        val toKey = to.key.toString()
        if (fromKey.startsWith("custom-") && toKey.startsWith("custom-")) {
            val values = liveOrder.toMutableList()
            val a = values.indexOf(fromKey.removePrefix("custom-"))
            val b = values.indexOf(toKey.removePrefix("custom-"))
            if (a >= 0 && b >= 0 && a != b) { values.add(b, values.removeAt(a)); order = values }
        }
    }
    val scope = WidgetScope.fromName(scopeName)
    val preview = WidgetDataBuilder.build(accounts, System.currentTimeMillis(), scope, accountId, providerId,
        customAccountIds = currentOrder.filter { it in selected })
    val valid = when (scope) {
        WidgetScope.ACCOUNT -> accounts.any { it.account.localId == accountId }
        WidgetScope.PROVIDER -> ProviderId.fromId(providerId) != null
        WidgetScope.CUSTOM -> preview.accounts.isNotEmpty()
        else -> true
    }
    fun move(id: String, direction: Int) {
        val ids = liveOrder.toMutableList(); val from = ids.indexOf(id)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, ids.lastIndex)
        if (from != to) { ids.add(to, ids.removeAt(from)); order = ids }
    }
    Scaffold(containerColor = UsageColors.Ground, bottomBar = {
        Row(Modifier.fillMaxWidth().background(UsageColors.Surface).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(R.string.action_cancel), onCancel, color = UsageColors.Muted, enabled = !saving)
            InkButton(stringResource(if (saving) R.string.widget_config_saving else R.string.widget_config_save), { onSave(scope, accountId, providerId, style, currentOrder.filter { it in selected }, modeOverrideName) },
                Modifier.weight(1f), enabled = valid && !saving)
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ScreenTitle(stringResource(widgetKindTitleRes(kind))) }
            item { ContentPreview(preview, kind, style, percentDisplay.resolved(PercentDisplay.fromNameOrNull(modeOverrideName))) }
            item {
                BackgroundSettings(
                    style = style,
                    onColor = { backgroundArgb = it },
                    onOpacity = { opacity = it },
                    onTextTone = { textToneName = it.name },
                )
            }
            item { PercentDisplaySettings(modeOverrideName) { modeOverrideName = it } }
            item { SectionHeader(stringResource(R.string.widget_config_section_content)) }
            // Restored as a real choice: it was removed from this screen once and the saved
            // value kept working, so a widget could silently watch "most critical" with no way
            // to see or change that from here.
            item {
                ChoiceCard {
                    ScopeChoice(stringResource(R.string.widget_config_most_critical_title), stringResource(R.string.widget_config_most_critical_subtitle), scope == WidgetScope.MOST_CRITICAL) { scopeName = WidgetScope.MOST_CRITICAL.name }
                    RuleDivider(Modifier.padding(horizontal = 16.dp))
                    ScopeChoice(stringResource(R.string.widget_config_closest_resets_title), stringResource(R.string.widget_config_closest_resets_subtitle), scope == WidgetScope.CLOSEST_RESETS) { scopeName = WidgetScope.CLOSEST_RESETS.name }
                    RuleDivider(Modifier.padding(horizontal = 16.dp))
                    ScopeChoice(stringResource(R.string.widget_config_all_accounts_title), stringResource(R.string.widget_config_all_accounts_subtitle), scope == WidgetScope.ALL_ACCOUNTS) { scopeName = WidgetScope.ALL_ACCOUNTS.name }
                }
            }
            item { SectionHeader(stringResource(R.string.widget_config_section_provider)) }
            item {
                ChoiceCard {
                    // Every provider, connected or not: a provider with no account gives the widget's empty state.
                    ProviderId.entries.forEachIndexed { index, provider ->
                        if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                        ScopeChoice(provider.displayName, stringResource(R.string.widget_config_provider_accounts, provider.displayName), scope == WidgetScope.PROVIDER && providerId == provider.id, provider) {
                            providerId = provider.id; scopeName = WidgetScope.PROVIDER.name
                        }
                    }
                }
            }
            if (kind != "Usage Summary") {
                item { ChoiceCard { ScopeChoice(stringResource(R.string.widget_config_custom_title), stringResource(R.string.widget_config_custom_subtitle), scope == WidgetScope.CUSTOM) { scopeName = WidgetScope.CUSTOM.name } } }
                if (scope == WidgetScope.CUSTOM) items(currentOrder, key = { "custom-$it" }) { id ->
                    val account = accounts.first { it.account.localId == id }.account
                    ReorderableItem(reorderState, key = "custom-$id") { _ ->
                        val showDescription = stringResource(R.string.widget_config_show_account, account.label)
                        val moveUpDescription = stringResource(R.string.widget_config_move_up, account.label)
                        val moveDownDescription = stringResource(R.string.widget_config_move_down, account.label)
                        val moveDescription = stringResource(R.string.widget_config_move, account.label)
                        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id },
                                modifier = Modifier.semantics { contentDescription = showDescription })
                            ProviderLogo(account.provider, Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(account.label, maxLines = 1, color = UsageColors.Ink)
                                Text(account.provider.displayName, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                            }
                            TextButton(onClick = { move(id, -1) }, enabled = currentOrder.first() != id,
                                modifier = Modifier.size(40.dp).semantics { contentDescription = moveUpDescription }) { Text("↑") }
                            TextButton(onClick = { move(id, 1) }, enabled = currentOrder.last() != id,
                                modifier = Modifier.size(40.dp).semantics { contentDescription = moveDownDescription }) { Text("↓") }
                            Box(Modifier.size(44.dp).draggableHandle().semantics { contentDescription = moveDescription },
                                contentAlignment = Alignment.Center) {
                                Icon(com.usagelimits.ui.AppIcons.DragHandle, null, tint = UsageColors.Muted)
                            }
                        }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.widget_config_section_account)) }
                item {
                    ChoiceCard {
                        accounts.forEachIndexed { index, usage ->
                            if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                            ScopeChoice(usage.account.label, usage.account.provider.displayName,
                                scope == WidgetScope.ACCOUNT && accountId == usage.account.localId, usage.account.provider) {
                                accountId = usage.account.localId; scopeName = WidgetScope.ACCOUNT.name
                            }
                        }
                    }
                }
            }
            error?.let { item { ErrorBlock(it) } }
        }
    }
}

/**
 * The resources of the current configuration. Reading `LocalConfiguration` is what makes the
 * screen recompose when the language changes: the resources object alone does not notify.
 */
@Composable
private fun localResources(): android.content.res.Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/**
 * The title of a widget's configuration screen. [kind] is the English identifier the activity
 * derives from the receiver, a key and not display text.
 */
@androidx.annotation.StringRes
internal fun widgetKindTitleRes(kind: String): Int = when (kind) {
    "Mini Rings" -> R.string.widget_label_mini_rings
    "Account Rings" -> R.string.widget_label_account_rings
    "Usage Summary" -> R.string.widget_label_usage_summary
    else -> R.string.widget_label_usage_bars
}

/** One card per group of radio rows, split by hairlines. */
@Composable
private fun ChoiceCard(content: @Composable ColumnScope.() -> Unit) = UsageCard(contentPadding = PaddingValues(0.dp), content = content)

@Composable
private fun ScopeChoice(title: String, subtitle: String, selected: Boolean, provider: ProviderId? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (provider != null) { ProviderLogo(provider, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        }
        RadioButton(selected, onClick = null)
    }
}

/**
 * The panel controls: colour, opacity and ink.
 * A slider allows partial transparency; explicit ink overrides handle wallpapers
 * whose contrast differs from the selected panel colour.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BackgroundSettings(
    style: WidgetStyle,
    onColor: (Int) -> Unit,
    onOpacity: (Int) -> Unit,
    onTextTone: (WidgetTextTone) -> Unit,
) {
    UsageCard {
        Text(stringResource(R.string.widget_config_background_title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        Text(stringResource(R.string.widget_config_background_subtitle), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetStyle.CHOICES.forEach { choice ->
                val chosen = choice.argb == style.background.argb
                val choiceDescription = stringResource(R.string.widget_config_background_choice, stringResource(choice.labelRes))
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(choice.argb))
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) UsageColors.Ink else UsageColors.Control, CircleShape)
                        .selectable(chosen, role = Role.RadioButton) { onColor(choice.argb) }
                        .semantics { contentDescription = choiceDescription },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val percent = style.background.clampedOpacity
        Text(
            if (percent == 0) stringResource(R.string.widget_config_opacity_transparent)
            else stringResource(R.string.widget_config_opacity_percent, percent),
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink,
        )
        val opacityDescription = stringResource(R.string.widget_config_opacity_description)
        Slider(
            value = percent.toFloat(),
            onValueChange = { onOpacity(it.roundToInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = opacityDescription },
            colors = SliderDefaults.colors(thumbColor = UsageColors.Ink, activeTrackColor = UsageColors.Ink, inactiveTrackColor = UsageColors.Rule),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.widget_config_text_title), style = MaterialTheme.typography.bodyLarge, color = UsageColors.Ink)
        // States AUTO's actual assumption, which is now continuous at every opacity: the ink
        // contrasts the chosen COLOUR, never the wallpaper — a widget cannot see the wallpaper,
        // so a see-through panel needs the explicit override when the colour's guess is wrong.
        Text(
            stringResource(R.string.widget_config_text_note),
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted,
        )
        Spacer(Modifier.height(6.dp))
        SegmentedRow {
            listOf(
                WidgetTextTone.AUTO to stringResource(R.string.widget_config_tone_auto),
                WidgetTextTone.LIGHT to stringResource(R.string.widget_config_tone_light),
                WidgetTextTone.DARK to stringResource(R.string.widget_config_tone_dark),
            ).forEach { (tone, label) ->
                Segment(label, style.textTone == tone) { onTextTone(tone) }
            }
        }
    }
}

/**
 * Whether this widget counts what is left or what is used, or simply follows the app.
 *
 * "App setting" is its own segment rather than the absence of a choice, so a widget that never
 * picked one keeps tracking the app's mode instead of being pinned to today's value.
 */
@Composable
private fun PercentDisplaySettings(overrideName: String?, onSelect: (String?) -> Unit) {
    UsageCard {
        Text(stringResource(R.string.widget_config_percent_title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        Text(stringResource(R.string.widget_config_percent_subtitle), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        Spacer(Modifier.height(6.dp))
        SegmentedRow {
            listOf<Pair<String?, String>>(
                null to stringResource(R.string.widget_config_percent_app),
                PercentDisplay.LEFT.name to stringResource(percentSideCaptionRes(PercentDisplay.LEFT)),
                PercentDisplay.USED.name to stringResource(percentSideCaptionRes(PercentDisplay.USED)),
            )
                .forEach { (name, label) -> Segment(label, overrideName == name) { onSelect(name) } }
        }
    }
}

/**
 * The widget as it will look, drawn with the same style rules the widget itself uses, over a
 * flat mat so a translucent panel reads as translucent.
 */
@Composable
internal fun ContentPreview(snapshot: WidgetSnapshot, kind: String, style: WidgetStyle, mode: PercentDisplay) {
    val resources = localResources()
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(androidx.compose.ui.graphics.Color(0xFF3A3936)).padding(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(style.background.color).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.widget_config_preview_title, snapshot.accountCount), style = MaterialTheme.typography.labelMedium, color = style.textSecondary)
            if (snapshot.accounts.isEmpty()) Text(snapshot.emptyText(resources) ?: stringResource(R.string.widget_config_preview_empty), color = style.textSecondary)
            else if (kind.contains("Rings")) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // A ring alone does not say which side it counts; the placed widgets carry the same caption.
                Text(stringResource(percentSideCaptionRes(mode)), style = MaterialTheme.typography.labelSmall, color = style.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    snapshot.accounts.take(4).forEach { account ->
                        val p = ringLimit(account).presentation(null, mode)
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Image(
                                UsageRing.draw(
                                    160,
                                    p.fill?.times(100.0),
                                    ringAccent(account, style).toArgb(), style.trackArgb,
                                ).asImageBitmap(),
                                ringDescription(resources, account, mode), Modifier.fillMaxSize(),
                            )
                            ProviderId.fromId(account.providerId)?.let { ProviderLogo(it, Modifier.size(22.dp)) }
                        }
                    }
                }
            } else snapshot.accounts.take(if (kind == "Usage Summary") 1 else 2).forEach { account ->
                Text(account.title, style = MaterialTheme.typography.titleMedium, color = style.textPrimary)
                account.rows.take(2).forEach { row ->
                    val p = row.presentation(account.dataValidity, mode)
                    val percent = percentFigure(p.token, resources)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.labelText(resources), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = style.textSecondary)
                        PreviewBar(
                            (p.fill ?: 0.0).toFloat(),
                            style.fill(p.fillState), style.track, Modifier.weight(1f),
                        )
                        // The preview names the side as the placed widget does; a dash stays bare.
                        Text(
                            if (!p.hasFigure) percent else "$percent ${stringResource(percentSideWordRes(mode))}",
                            style = UsageType.Figure, color = style.barText(row, account.dataValidity),
                        )
                    }
                }
            }
        }
    }
}

/** A 4dp bar on the widget's own track; no fill when the widget would draw none. */
@Composable
private fun PreviewBar(fraction: Float, fill: androidx.compose.ui.graphics.Color?, track: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        if (fill != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
    }
}
