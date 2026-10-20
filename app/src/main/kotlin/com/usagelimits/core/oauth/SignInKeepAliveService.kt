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
                .setContentIntent(openApp())
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

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The three minutes are up but a redirect wait allows five: the service ends, the login does
     * not. Once the app is cached again the listener freezes, so say how to thaw it. Skipped
     * silently where notifications are off.
     */
    private fun timedOut() {
        getSystemService(NotificationManager::class.java)?.takeIf { it.areNotificationsEnabled() }?.notify(
            HINT_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Signing in")
                .setContentText("Return to Usage Limits to finish signing in")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) = timedOut()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()

    companion object {
        private const val CHANNEL_ID = "sign_in"
        private const val NOTIFICATION_ID = 7101
        private const val HINT_ID = 7102

        /**
         * Stopping a service that startForegroundService() began before it calls startForeground()
         * crashes the app, and a login can end that fast (no browser to open). So stop() only
         * calls stopService once the service is foreground; otherwise it leaves [stopRequested]
         * for onStartCommand, which calls startForeground first and then stops itself. Each side
         * writes its flag before reading the other's, so at least one of them sees both.
         *
         * Every start() takes a new lease; only the current lease's stop() counts, so a cancelled
         * login's late clean-up cannot stop the service a newer login is relying on.
         */
        @Volatile private var foreground = false
        @Volatile private var stopRequested = false
        private var lease = 0L

        /** Best effort: a login that cannot get the service still works, it just may stall. */
        @Synchronized
        fun start(context: Context): Long {
            stopRequested = false
            try {
                ContextCompat.startForegroundService(context, Intent(context, SignInKeepAliveService::class.java))
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (an IllegalStateException) and friends.
            }
            return ++lease
        }

        /** Ends the lease [start] returned; a no-op once a newer login has taken over. */
        @Synchronized
        fun stop(context: Context, token: Long) {
            if (token != lease) return
            stopRequested = true
            context.getSystemService(NotificationManager::class.java)?.cancel(HINT_ID)
            if (foreground) context.stopService(Intent(context, SignInKeepAliveService::class.java))
        }
    }
}
