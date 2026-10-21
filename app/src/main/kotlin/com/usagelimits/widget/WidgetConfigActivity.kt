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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WidgetScope
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
            UsageLimitsTheme(providerIcons = container.settingsStore.settings.collectAsState(initial = com.usagelimits.core.settings.AppSettings()).value.providerIcons) {
                var initial by remember { mutableStateOf<WidgetConfigEntity?>(null) }
                var loaded by remember { mutableStateOf(false) }
                val accounts by container.repository.observeAccountUsage().collectAsState(emptyList())
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(widgetId) {
                    try { initial = container.widgetConfigDao.get(widgetId); loaded = true }
                    catch (_: Exception) { error = "Could not load this widget. Please try again." }
                }
                if (loaded) WidgetConfigScreen(accounts, initial, kind, saving, error,
                    onCancel = { finish() }, onSave = { scope, accountId, providerId, style, custom ->
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
                                        customAccountIdsJson = JSONArray(custom).toString(),
                                        layoutMetricsJson = (WidgetMetrics.fromJson(initial?.layoutMetricsJson ?: "{}") ?: placementMetrics).toJson()))
                                    WidgetUpdater.refreshAll(this@WidgetConfigActivity)
                                    // A saved configuration can change what the widget shows
                                    // without new data; the next staleness/reset boundary moves
                                    // with it, so recompute the one coalesced repaint.
                                    WidgetPresentationWorker.scheduleNext(this@WidgetConfigActivity)
                                    setResult(RESULT_OK, result); finish()
                                } catch (_: Exception) { saving = false; error = "Could not save the widget. Try again." }
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
private fun WidgetConfigScreen(accounts: List<AccountUsage>, initial: WidgetConfigEntity?, kind: String,
    saving: Boolean, error: String?, onCancel: () -> Unit,
    onSave: (WidgetScope, String?, String?, WidgetStyle, List<String>) -> Unit) {
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
            TextAction("Cancel", onCancel, color = UsageColors.Muted, enabled = !saving)
            InkButton(if (saving) "Saving…" else "Save widget", { onSave(scope, accountId, providerId, style, currentOrder.filter { it in selected }) },
                Modifier.weight(1f), enabled = valid && !saving)
        }
    }) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ScreenTitle(kind) }
            item { ContentPreview(preview, kind, style) }
            item {
                BackgroundSettings(
                    style = style,
                    onColor = { backgroundArgb = it },
                    onOpacity = { opacity = it },
                    onTextTone = { textToneName = it.name },
                )
            }
            item { SectionHeader("Widget content") }
            // Restored as a real choice: it was removed from this screen once and the saved
            // value kept working, so a widget could silently watch "most critical" with no way
            // to see or change that from here.
            item {
                ChoiceCard {
                    ScopeChoice("Most Critical", "Automatically shows whatever needs attention most", scope == WidgetScope.MOST_CRITICAL) { scopeName = WidgetScope.MOST_CRITICAL.name }
                    RuleDivider(Modifier.padding(horizontal = 16.dp))
                    ScopeChoice("Closest Resets", "Automatically shows whatever account resets next", scope == WidgetScope.CLOSEST_RESETS) { scopeName = WidgetScope.CLOSEST_RESETS.name }
                    RuleDivider(Modifier.padding(horizontal = 16.dp))
                    ScopeChoice("All accounts", "Same order as Overview", scope == WidgetScope.ALL_ACCOUNTS) { scopeName = WidgetScope.ALL_ACCOUNTS.name }
                }
            }
            item { SectionHeader("One provider") }
            item {
                ChoiceCard {
                    // Every provider, connected or not: a provider with no account gives the widget's empty state.
                    ProviderId.entries.forEachIndexed { index, provider ->
                        if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                        ScopeChoice(provider.displayName, "All ${provider.displayName} accounts", scope == WidgetScope.PROVIDER && providerId == provider.id, provider) {
                            providerId = provider.id; scopeName = WidgetScope.PROVIDER.name
                        }
                    }
                }
            }
            if (kind != "Usage Summary") {
                item { ChoiceCard { ScopeChoice("Custom", "Choose accounts and drag to reorder", scope == WidgetScope.CUSTOM) { scopeName = WidgetScope.CUSTOM.name } } }
                if (scope == WidgetScope.CUSTOM) items(currentOrder, key = { "custom-$it" }) { id ->
                    val account = accounts.first { it.account.localId == id }.account
                    ReorderableItem(reorderState, key = "custom-$id") { _ ->
                        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id },
                                modifier = Modifier.semantics { contentDescription = "Show ${account.label}" })
                            ProviderLogo(account.provider, Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(account.label, maxLines = 1, color = UsageColors.Ink)
                                Text(account.provider.displayName, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                            }
                            TextButton(onClick = { move(id, -1) }, enabled = currentOrder.first() != id,
                                modifier = Modifier.size(40.dp).semantics { contentDescription = "Move up ${account.label}" }) { Text("↑") }
                            TextButton(onClick = { move(id, 1) }, enabled = currentOrder.last() != id,
                                modifier = Modifier.size(40.dp).semantics { contentDescription = "Move down ${account.label}" }) { Text("↓") }
                            Box(Modifier.size(44.dp).draggableHandle().semantics { contentDescription = "Move ${account.label}" },
                                contentAlignment = Alignment.Center) {
                                Icon(com.usagelimits.ui.AppIcons.DragHandle, null, tint = UsageColors.Muted)
                            }
                        }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                item { SectionHeader("One account") }
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
        Text("Background", style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        Text("Colour and opacity of this widget's panel", style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetStyle.CHOICES.forEach { choice ->
                val chosen = choice.argb == style.background.argb
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(choice.argb))
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) UsageColors.Ink else UsageColors.Control, CircleShape)
                        .selectable(chosen, role = Role.RadioButton) { onColor(choice.argb) }
                        .semantics { contentDescription = "${choice.label} background" },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val percent = style.background.clampedOpacity
        Text(
            if (percent == 0) "Opacity · transparent" else "Opacity · $percent %",
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink,
        )
        Slider(
            value = percent.toFloat(),
            onValueChange = { onOpacity(it.roundToInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Background opacity" },
            colors = SliderDefaults.colors(thumbColor = UsageColors.Ink, activeTrackColor = UsageColors.Ink, inactiveTrackColor = UsageColors.Rule),
        )
        Spacer(Modifier.height(8.dp))
        Text("Text", style = MaterialTheme.typography.bodyLarge, color = UsageColors.Ink)
        // States AUTO's actual assumption, which is now continuous at every opacity: the ink
        // contrasts the chosen COLOUR, never the wallpaper — a widget cannot see the wallpaper,
        // so a see-through panel needs the explicit override when the colour's guess is wrong.
        Text(
            "Auto contrasts the colour above at any opacity. If the wallpaper shows through, pick Light or Dark yourself.",
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted,
        )
        Spacer(Modifier.height(6.dp))
        SegmentedRow {
            listOf(WidgetTextTone.AUTO to "Auto", WidgetTextTone.LIGHT to "Light", WidgetTextTone.DARK to "Dark").forEach { (tone, label) ->
                Segment(label, style.textTone == tone) { onTextTone(tone) }
            }
        }
    }
}

/**
 * The widget as it will look, drawn with the same style rules the widget itself uses, over a
 * flat mat so a translucent panel reads as translucent.
 */
@Composable
private fun ContentPreview(snapshot: WidgetSnapshot, kind: String, style: WidgetStyle) {
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(androidx.compose.ui.graphics.Color(0xFF3A3936)).padding(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(style.background.color).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Preview · ${snapshot.accountCount} selected", style = MaterialTheme.typography.labelMedium, color = style.textSecondary)
            if (snapshot.accounts.isEmpty()) Text(snapshot.emptyMessage ?: "Select accounts to see your widget", color = style.textSecondary)
            else if (kind.contains("Rings")) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                snapshot.accounts.take(4).forEach { account ->
                    val row = ringLimit(account)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Image(
                            UsageRing.draw(160, row?.remainingPercent, ringAccent(account, style).toArgb(), style.trackArgb).asImageBitmap(),
                            null, Modifier.fillMaxSize(),
                        )
                        ProviderId.fromId(account.providerId)?.let { ProviderLogo(it, Modifier.size(22.dp)) }
                    }
                }
            } else snapshot.accounts.take(if (kind == "Usage Summary") 1 else 2).forEach { account ->
                Text(account.title, style = MaterialTheme.typography.titleMedium, color = style.textPrimary)
                account.rows.take(2).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = style.textSecondary)
                        PreviewBar(((row.remainingPercent ?: 0.0) / 100).toFloat(), style.bar(row, account.dataValidity), style.track, Modifier.weight(1f))
                        Text(com.usagelimits.core.model.percentLabel(row.remainingPercent), style = UsageType.Figure, color = style.barText(row, account.dataValidity))
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
