package com.usagelimits.core.oauth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.usagelimits.MainActivity
import com.usagelimits.R

/**
 * Keeps the process out of the cached state while a browser sign-in waits for its redirect.
 *
 * The redirect lands on [LoopbackServer], a socket inside this process. With the browser in
 * front the app is cached, and Android 14+'s cached-apps freezer freezes it about ten seconds
 * later; an incoming loopback connection does not thaw it, so the kernel accepts the browser's
 * request and nothing answers: the page loads forever until the user returns to the app. A
 * foreground service keeps the process uncached, so the listener keeps answering.
 *
 * A short service is capped at three minutes; [onTimeout] stops it, and the login itself
 * keeps waiting (returning to the app thaws it).
 */
class SignInKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sign-in", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Signing in")
                .setContentText("Finishing sign-in… Return to Usage Limits if the browser stalls.")
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // Not allowed to run in the foreground right now: the login carries on without us.
            stopSelf()
            return START_NOT_STICKY
        }
        foreground = true
        // The login ended before we got here (stop() held back its stopService); stopping now
        // is safe, because startForeground has been called.
        if (stopRequested) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) = stopSelf()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL_ID = "sign_in"
        private const val NOTIFICATION_ID = 7101

        /**
         * Stopping a service that startForegroundService() began before it calls startForeground()
         * crashes the app, and a login can end that fast (no browser to open). So stop() only
         * calls stopService once the service is foreground; otherwise it leaves [stopRequested]
         * for onStartCommand, which calls startForeground first and then stops itself. Each side
         * writes its flag before reading the other's, so at least one of them sees both.
         */
        @Volatile private var foreground = false
        @Volatile private var stopRequested = false

        /** Best effort: a login that cannot get the service still works, it just may stall. */
        fun start(context: Context) {
            stopRequested = false
            try {
                ContextCompat.startForegroundService(context, Intent(context, SignInKeepAliveService::class.java))
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (an IllegalStateException) and friends.
            }
        }

        fun stop(context: Context) {
            stopRequested = true
            if (foreground) context.stopService(Intent(context, SignInKeepAliveService::class.java))
        }
    }
}
