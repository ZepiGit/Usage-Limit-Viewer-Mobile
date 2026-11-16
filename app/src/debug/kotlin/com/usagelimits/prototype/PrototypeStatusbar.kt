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
                .setSortKey("prototype-$index")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(delete)
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

    companion object { private const val TAG = "PrototypeStatusbar" }
}
