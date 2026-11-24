package com.usagelimits

import android.app.Application
import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.usagelimits.core.sync.MainAppOpening
import com.usagelimits.ui.HoorayPlayback
import android.util.Log
import androidx.work.Configuration
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttp

class UsageLimitsApp : Application(), Configuration.Provider {

    /**
     * WorkManager's configuration, supplied on demand.
     *
     * The manifest excludes WorkManager's Startup initializer, so this is the only path by
     * which WorkManager is configured — deterministic and ordered after `onCreate`, rather than
     * a content provider that runs before the app exists and fails invisibly when it does not.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    lateinit var container: AppContainer
        private set

    /**
     * Application-lifetime scope for start-up work.
     *
     * SupervisorJob so a failure in one start-up task cannot cancel the others.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The start-up work, so a test can wait for it instead of sleeping and hoping.
     *
     * It matters beyond tidiness: this job opens WorkManager's database on a background
     * thread, and a test that ends while it is still running leaves it racing the next test
     * class's set-up in the same JVM. Robolectric's native runtime unpacks its fonts through a
     * process-wide zip filesystem, and two sandboxes doing that at once fail the second one
     * with `FileSystemAlreadyExistsException` — after which every SQLite call in that sandbox
     * throws `UnsatisfiedLinkError`, in tests that never touched WorkManager.
     */
    @Volatile
    var startupJob: kotlinx.coroutines.Job? = null
        private set

    private val statusIconEligibility by lazy {
        com.usagelimits.statusicon.StatusIconEligibilityObserver(
            scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
            record = { container.settingsStore.recordStatusIconEligibility(it) },
            publish = { container.publication.notificationsAllowedAgain() },
        )
    }

    /** Captures the OS state now; the application queue persists and publishes observations in order. */
    @androidx.annotation.MainThread
    internal fun observeStatusIconEligibility(): kotlinx.coroutines.Job =
        statusIconEligibility.observe(com.usagelimits.statusicon.StatusIconPublisher.canShow(this))

    internal val hoorayPlayback = HoorayPlayback()
    internal val mainOpening = MainAppOpening(
        scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate),
        claim = { container.repository.claimPendingResetConfirmations() },
        present = hoorayPlayback::open,
        enqueueSync = { SyncWorker.syncOnOpen(this) },
        stopPlayback = hoorayPlayback::stop,
    )

    override fun onCreate() {
        super.onCreate()
        OkHttp.initialize(this)
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mainOpening.processStarted()
            override fun onStop(owner: LifecycleOwner) = mainOpening.processStopped()
        })
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity !is UsageLimitsActivity) mainOpening.activityStarted(main = false)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        startupJob = appScope.launch {
            // Contained, and logged rather than swallowed. This runs in a SupervisorJob, so a
            // throw here does not crash the app — it simply means the background refresh is
            // never scheduled, silently, for the life of the install. That is the worse failure
            // of the two, and it deserves a line in the log rather than an uncaught exception
            // nobody sees.
            runCatching {
                scheduleOnStartup()
            }.onFailure { error ->
                Log.w(TAG, "background sync could not be scheduled", error)
            }
        }
    }

    // WorkManager can start this process alone; only a qualifying main opening requests fresh readings.
    internal suspend fun scheduleOnStartup() = SyncWorker.scheduleBackgroundSync(this)

    private companion object {
        const val TAG = "UsageLimitsApp"
    }
}
