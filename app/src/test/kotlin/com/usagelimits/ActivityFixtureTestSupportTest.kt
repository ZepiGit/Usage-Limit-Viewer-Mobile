package com.usagelimits

import android.Manifest
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.settings.SettingsStore
import com.usagelimits.core.sync.resetTestWorkManager
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFixtureTestSupportTest {
    @get:Rule val settingsFiles = TemporaryFolder()

    @Test fun `a settings writer can finish after fixture cleanup and main queue reset`() {
        val app: UsageLimitsApp = ApplicationProvider.getApplicationContext()
        runBlocking { app.startupJob?.join() }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val original = app.container
        val scheduler = TestCoroutineScheduler()
        val storeJob = SupervisorJob()
        val scope = CoroutineScope(storeJob + StandardTestDispatcher(scheduler))
        val real = PreferenceDataStoreFactory.create(scope = scope) {
            settingsFiles.root.resolve("settings.preferences_pb")
        }
        val writerQueue = ArrayDeque<Runnable>()
        val heldWriter = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                writerQueue.addLast(block)
            }
        }
        val gated = object : DataStore<Preferences> by real {
            private var firstWrite = true

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (!firstWrite) return real.updateData(transform)
                firstWrite = false
                return withContext(heldWriter) { real.updateData(transform) }
            }
        }
        val container = object : AppContainer(app) {
            override val settingsStore = SettingsStore(gated)
        }
        val store = container.settingsStore
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun pumpEligibility() {
            while (writerQueue.isNotEmpty()) writerQueue.removeFirst().run()
            scheduler.runCurrent()
            ShadowLooper.idleMainLooper()
        }

        var fixtureCleaned = false
        ReflectionHelpers.setField(app, "container", container)
        try {
            val initial = scope.async { store.settings.first() }
            scheduler.runCurrent()
            assertEquals(30, initial.getCompleted().syncIntervalMinutes)
            instrumentation.runOnMainSync { app.observeStatusIconEligibility() }
            writerQueue.removeFirst().run()
            // The actor now owns the update and has dispatched its transform to the held caller.
            scheduler.runCurrent()
            check(writerQueue.isNotEmpty()) { "DataStore did not dispatch the eligibility transform" }

            app.drainActivityFixtureWork(::pumpEligibility)
            fixtureCleaned = true
            shadowOf(Looper.getMainLooper().queue).reset()
            val consumer = scope.async {
                store.setSyncInterval(45)
                store.settings.first().syncIntervalMinutes
            }
            scheduler.runCurrent()
            assertTrue("the next SettingsStore write must finish after fixture cleanup", consumer.isCompleted)
            assertEquals(45, consumer.getCompleted())
        } finally {
            try {
                lateinit var finalObservation: Job
                instrumentation.runOnMainSync { finalObservation = app.observeStatusIconEligibility() }
                runBlocking {
                    withTimeout(10_000) {
                        while (!finalObservation.isCompleted) {
                            pumpEligibility()
                            yield()
                        }
                    }
                }
            } finally {
                ReflectionHelpers.setField(app, "container", original)
                storeJob.cancel()
                scheduler.runCurrent()
                if (!fixtureCleaned) {
                    WorkManager.getInstance(app).cancelAllWork().result.get()
                    ShadowLooper.idleMainLooper()
                    resetTestWorkManager()
                }
                check(storeJob.isCompleted) { "temporary DataStore work did not end" }
            }
        }
    }
}
