package com.usagelimits.statusicon

import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Orders captured main-window observations through the stored edge and its publication. */
internal class StatusIconEligibilityObserver(
    private val scope: CoroutineScope,
    private val record: suspend (Boolean) -> Boolean,
    private val publish: suspend () -> Unit,
) {
    private var pending: Job? = null

    @MainThread
    fun observe(allowed: Boolean): Job {
        val previous = pending
        return scope.launch {
            previous?.join()
            try {
                if (record(allowed)) publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("StatusIconEligibility", "notification eligibility observation failed", error)
            }
        }.also { pending = it }
    }
}
