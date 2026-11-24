package com.usagelimits.core.sync

import kotlinx.coroutines.launch

/** Joins process ON_START and the interval's first activity start, in either callback order. */
internal class MainAppOpening(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val claim: suspend () -> Set<String>,
    private val present: (Set<String>) -> Unit,
    private val enqueueSync: () -> Unit,
    private val stopPlayback: () -> Unit,
) {
    var job: kotlinx.coroutines.Job? = null
        private set
    private var generation = 0L

    private fun open() {
        val current = ++generation
        val previous = job
        job = scope.launch {
            previous?.join()
            val accounts = claim()
            if (current == generation) present(accounts)
            enqueueSync()
        }
    }

    fun interrupt() { generation++; stopPlayback() }

    private var foreground = false
    private var firstMain: Boolean? = null
    private var awaitingActivity = false

    fun processStarted() {
        if (foreground) return
        foreground = true
        awaitingActivity = firstMain == null
        if (firstMain == true) open()
        firstMain = null
    }

    fun activityStarted(main: Boolean) {
        if (!foreground) {
            if (firstMain == null) firstMain = main
        } else if (awaitingActivity) {
            awaitingActivity = false
            if (main) open()
        }
    }

    fun processStopped() {
        foreground = false
        firstMain = null
        awaitingActivity = false
        interrupt()
    }
}
