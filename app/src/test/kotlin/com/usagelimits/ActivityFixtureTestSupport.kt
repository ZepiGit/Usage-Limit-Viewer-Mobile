package com.usagelimits

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

internal fun ActivityController<UsageLimitsActivity>.closeAndDrainActivityJobs() {
    val activity = get()
    val jobs = activity.viewModelStore.keys().mapNotNull { key ->
        (activity.viewModelStore[key] as? UsageViewModel)?.viewModelScope?.coroutineContext?.get(Job)
    } + listOfNotNull(activity.notificationPermissionCheck)
    close()
    awaitMainLooper { jobs.all { it.isCompleted } }
}

internal fun UsageLimitsApp.drainActivityFixtureWork(pumpEligibility: () -> Unit = ShadowLooper::idleMainLooper) {
    awaitMainLooper { startupJob?.isCompleted != false && mainOpening.job?.isCompleted != false }
    // Closing the window does not cancel its application-owned eligibility observations.
    lateinit var eligibilityBarrier: Job
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        eligibilityBarrier = observeStatusIconEligibility()
    }
    awaitMainLooper(pumpEligibility) { eligibilityBarrier.isCompleted }
    ShadowLooper.idleMainLooper()
    WorkManager.getInstance(this).cancelAllWork().result.get()
    ShadowLooper.idleMainLooper()
    resetTestWorkManager()
}

private fun awaitMainLooper(pump: () -> Unit = ShadowLooper::idleMainLooper, done: () -> Boolean) = runBlocking {
    withTimeout(10_000) {
        while (!done()) {
            pump()
            delay(10)
        }
    }
}
