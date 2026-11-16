package com.usagelimits.prototype

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.NotificationPublisher
import kotlinx.coroutines.runBlocking

/** Throwaway debug-only status bar experiment; intentionally not an app feature. */
class PrototypeStatusbarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val light = intent.getBooleanExtra("light", false)
        window.statusBarColor = if (light) Color.rgb(245, 245, 245) else Color.rgb(38, 41, 46)
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        setContentView(TextView(this).apply {
            setBackgroundColor(if (light) Color.rgb(245, 245, 245) else Color.rgb(38, 41, 46))
            setTextColor(if (light) Color.BLACK else Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            text = "PROTOTYPE — not production\n\n" + intent.getStringExtra("label").orEmpty() +
                "\n\nBitmap alpha masks · 3 pinned accounts\nClaude 05 · Codex 42 · xAI 87"
        })
    }
}

class PrototypeStatusbarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val command = intent.getStringExtra("command") ?: "post"
        if (command == "deleted") {
            Log.i(TAG, "DELETE_INTENT id=${intent.getIntExtra("id", -1)}")
            return
        }
        if (command == "clear") {
            manager.cancelAll()
            return
        }
        if (command == "alert") {
            // Exercise the actual app publisher, not a lookalike alert notification.
            runBlocking {
                val container = (context.applicationContext as UsageLimitsApp).container
                val account = container.repository.upsertFromLogin(
                    ProviderId.CODEX, "prototype-only-${System.currentTimeMillis()}", null, "Prototype alert fixture", null, emptyMap(),
                )
                val now = System.currentTimeMillis()
                val snapshot = UsageSnapshot(account.localId, now, SnapshotStatus.OK, listOf(
                    UsageWindow("prototype-quota", "Prototype quota", WindowCategory.FIVE_HOUR, 95.0, 18000L, now + 3600000, false),
                ))
                NotificationPublisher(context, container.settingsStore, container.notificationDao,
                    transactions = container.transactions).publishFor(listOf(AccountUsage(account, snapshot)))
            }
            Log.i(TAG, "NORMAL_ALERT publisher completed")
            return
        }
        val scenario = intent.getStringExtra("scenario") ?: "b"
        val variant = intent.getStringExtra("variant") ?: "A"
        if (scenario.startsWith("candidate-")) {
            postGroupingCandidate(context, manager, scenario)
            return
        }
        val channelId = if (scenario == "d") "prototype_default_silent" else "prototype_low"
        manager.createNotificationChannel(NotificationChannel(channelId, "PROTOTYPE ${if (scenario == "d") "DEFAULT silent" else "LOW"}",
            if (scenario == "d") NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val providers = listOf("claude", "codex", "xai")
        val values = intent.getStringExtra("states")?.split(",") ?: listOf("5", "42", "87")
        val before = manager.activeNotifications.filter { it.id in 7100..7102 }
        val indices = if (command == "update") listOf(1) else listOf(0, 1, 2)
        for (index in indices) {
            val id = 7100 + index
            val state = if (command == "update") "99" else values[index]
            val bitmap = context.assets.open("prototype/$variant-${providers[index]}-$state.png").use { BitmapFactory.decodeStream(it) }
            val delete = PendingIntent.getBroadcast(context, id,
                Intent(context, PrototypeStatusbarReceiver::class.java).putExtra("command", "deleted").putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(context, channelId)
                .setSmallIcon(Icon.createWithBitmap(bitmap))
                .setContentTitle("PROTOTYPE ${providers[index]} ${if (state == "5") "05" else state}")
                .setContentText("Variant $variant · case $scenario · ${if (command == "update") "UPDATED same ID" else "initial"}")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(false)
                .setWhen(before.firstOrNull { it.id == id }?.notification?.`when` ?: System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(delete)
            if (scenario != "a") builder.setSortKey("prototype-$index")
            when (scenario) {
                "a" -> Unit
                "c" -> builder.setGroup("prototype-all")
                else -> builder.setGroup("prototype-${providers[index]}")
            }
            manager.notify(id, builder.build())
        }
        if (scenario == "c" && command != "update") manager.notify(7199,
            Notification.Builder(context, channelId)
                .setSmallIcon(com.usagelimits.R.drawable.ic_notification)
                .setContentTitle("PROTOTYPE summary")
                .setContentText("Three accounts in one explicit group")
                .setGroup("prototype-all")
                .setGroupSummary(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build())
        val after = manager.activeNotifications.filter { it.id in 7100..7102 }
        Log.i(TAG, "$command variant=$variant scenario=$scenario before=${before.map { "${it.key}@${it.postTime}" }} after=${after.map { "${it.key}@${it.postTime}" }}")
    }

    private fun postGroupingCandidate(context: Context, manager: NotificationManager, scenario: String) {
        val providers = listOf("claude", "codex", "xai")
        val values = listOf("5", "42", "87")
        fun smallIcon(name: String): Icon = context.assets.open("prototype/$name.png").use {
            Icon.createWithBitmap(BitmapFactory.decodeStream(it))
        }
        fun channel(index: Int): String {
            val split = scenario == "candidate-sections"
            val id = if (scenario == "candidate-channels") "prototype-account-$index" else if (split) "prototype-section-$index" else "prototype-candidate-low"
            val importance = if (split) listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN)[index] else NotificationManager.IMPORTANCE_LOW
            manager.createNotificationChannel(NotificationChannel(id, "PROTOTYPE $id", importance).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
            return id
        }
        if (scenario.startsWith("candidate-composite-")) {
            channel(0)
            val composite = if (scenario.endsWith("bars")) "F-bars-5-42-87" else "F-rings-5-42-87"
            manager.notify(7199, Notification.Builder(context, "prototype-candidate-low")
                .setSmallIcon(smallIcon(composite))
                .setContentTitle("PROTOTYPE three pinned accounts")
                .setContentText("Composite summary: 05 · 42 · 87")
                .setGroup("prototype-valid-composite").setGroupSummary(true)
                .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true).build())
        }
        for (index in 0..2) {
            val channelId = channel(index)
            val group = when (scenario) {
                "candidate-composite-bars", "candidate-composite-rings" -> "prototype-valid-composite"
                "candidate-valid-groups", "candidate-summary-one", "candidate-summary-three" -> "prototype-valid-${providers[index]}"
                else -> "prototype-single-${providers[index]}"
            }
            val icon = smallIcon("C-${providers[index]}-${values[index]}")
            val notification = Notification.Builder(context, channelId)
                .setSmallIcon(icon)
                .setContentTitle("PROTOTYPE ${providers[index]} ${values[index]}")
                .setContentText(scenario)
                .setGroup(group)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(if (scenario == "candidate-colorized") Color.rgb(177, 93, 69) else Color.TRANSPARENT)
                .setColorized(scenario == "candidate-colorized")
                .build()
            manager.notify(7100 + index, notification)
            if (scenario in listOf("candidate-valid-groups", "candidate-summary-one", "candidate-summary-three")) {
                // Only honest if each extra row conveys useful account quota detail.
                val extraChildren = when (scenario) { "candidate-summary-one" -> 0; "candidate-summary-three" -> 2; else -> 1 }
                repeat(extraChildren) { detail ->
                    manager.notify(7200 + index * 10 + detail, Notification.Builder(context, channelId)
                        .setSmallIcon(icon).setContentTitle("PROTOTYPE ${providers[index]} ${if (detail == 0) "reset" else "weekly quota"}")
                        .setContentText("Quota detail row ${detail + 2} for grouping experiment")
                        .setGroup(group).setOngoing(true).setOnlyAlertOnce(true).build())
                }
                manager.notify(7300 + index, Notification.Builder(context, channelId)
                    .setSmallIcon(icon).setContentTitle("PROTOTYPE ${providers[index]} summary")
                    .setGroup(group).setGroupSummary(true).setOngoing(true).setOnlyAlertOnce(true).build())
            }
        }
        Log.i(TAG, "GROUPING_CANDIDATE $scenario active=${manager.activeNotifications.map { it.key }}")
    }

    companion object { private const val TAG = "PrototypeStatusbar" }
}
