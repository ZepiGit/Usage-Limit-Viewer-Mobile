package com.usagelimits.feature

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.usagelimits.core.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowLooper

/**
 * A [UsageViewModel] a test owns, the way `UsagePublicationRoutingTest` owns its own.
 *
 * Registered in a test-owned [ViewModelStore], so [clear] cancels its jobs and waits for them to
 * end. A test calls [clear] in its cleanup before WorkManager and the looper are torn down, also
 * when the test failed, so no job of this test reaches into the next one.
 */
internal class OwnedViewModel(container: AppContainer, context: Context) {
    private val store = ViewModelStore()
    val vm = UsageViewModel(container, context).also { store.put("usage", it) }

    fun clear() {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        store.clear()
        // Cancelled jobs still run their finally blocks, on the main looper, which is this
        // thread: pump it until they have completed, rather than suspend in join() and starve them.
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "the view model's jobs did not end" }
            ShadowLooper.idleMainLooper()
            runBlocking { kotlinx.coroutines.yield() }
        }
    }
}

/**
 * Waits until [condition] holds, running what is queued on Robolectric's paused main looper
 * before every check: Room, DataStore and view model results land there, and the Compose clock
 * does not run it. Fails when the time runs out; it never lets a test go on without [condition].
 */
internal fun ComposeTestRule.waitDrained(condition: () -> Boolean) = waitUntil(10_000) {
    ShadowLooper.idleMainLooper()
    condition()
}
