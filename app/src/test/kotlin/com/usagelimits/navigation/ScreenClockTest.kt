package com.usagelimits.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.ZoneId

/**
 * The screens' clock on the test scheduler's virtual time: the wall clock it reads is the
 * scheduler's own time plus a fixed origin, so every wake-up is decided by the code under test and
 * nothing waits on real time. Each test cancels the loop it starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenClockTest {
    /** A bound in real time: a test that stops making progress fails here instead of blocking the run. */
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val origin = 1_800_000_000_000L
    private val utc = ZoneId.of("Etc/UTC")

    private fun TestScope.clock(zone: () -> ZoneId = { utc }, offset: () -> Long = { 0L }) =
        ScreenClock(now = { origin + currentTime + offset() }, zone = zone)

    @Test fun `a boundary before the next tick wakes the clock at the boundary`() = runTest {
        val clock = clock()
        val loop = launch { clock.run { _, _ -> origin + 7_000 } }
        runCurrent()
        advanceTimeBy(6_999); runCurrent()
        assertEquals("not before the boundary", origin, clock.nowMs)
        advanceTimeBy(1); runCurrent()
        assertEquals("at the boundary, not at the 30 s tick", origin + 7_000, clock.nowMs)
        loop.cancel()
    }

    @Test fun `without a boundary the clock moves on the tick`() = runTest {
        val clock = clock()
        val loop = launch { clock.run { _, _ -> null } }
        runCurrent()
        advanceTimeBy(TICK_INTERVAL_MS - 1); runCurrent()
        assertEquals(origin, clock.nowMs)
        advanceTimeBy(1); runCurrent()
        assertEquals(origin + TICK_INTERVAL_MS, clock.nowMs)
        loop.cancel()
    }

    @Test fun `a foreground return or a clock change reads the clock and zone at once`() = runTest {
        var jumped = 0L
        var zone = utc
        val clock = clock(zone = { zone }, offset = { jumped })
        val loop = launch { clock.run { _, _ -> null } }
        runCurrent()
        // The person set the clock an hour ahead and moved to Santiago; no tick has come yet.
        jumped = 3_600_000
        zone = ZoneId.of("America/Santiago")
        clock.reread()
        runCurrent()
        assertEquals(origin + 3_600_000, clock.nowMs)
        assertEquals(ZoneId.of("America/Santiago"), clock.zoneId)
        // The wait starts over from the re-read: the next tick is a full interval later.
        advanceTimeBy(TICK_INTERVAL_MS - 1); runCurrent()
        assertEquals(origin + 3_600_000, clock.nowMs)
        advanceTimeBy(1); runCurrent()
        assertEquals(origin + 3_600_000 + TICK_INTERVAL_MS, clock.nowMs)
        loop.cancel()
    }

    @Test fun `a stale threshold moved forward while the clock waits wakes it at the new boundary`() = runTest {
        val clock = clock()
        // The boundary is when the reading turns stale: fetched at origin, stale after the interval.
        var staleAfter = 60 * 60_000L
        val loop = launch { clock.run { _, _ -> origin + staleAfter } }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        // A shorter sync interval: the reading turns stale 5 s from now, before the next tick.
        staleAfter = 6_000
        clock.reread()
        runCurrent()
        advanceTimeBy(4_999); runCurrent()
        assertEquals("not before the new boundary", origin + 1_000, clock.nowMs)
        advanceTimeBy(1); runCurrent()
        assertEquals("at the new boundary, not at the tick planned before the change", origin + 6_000, clock.nowMs)
        loop.cancel()
    }

    @Test fun `a wake that resumes just after a boundary shows the screen past it`() = runTest {
        // A reread publishes B - 1; the loop only resumes after B (a busy main thread). The screen
        // must stand past B at once, not wait for the next tick because B already dropped out.
        var lag = 0L
        val boundary = origin + 5_000
        val clock = ScreenClock(now = { origin + currentTime + lag }, zone = { utc })
        val loop = launch { clock.run { at, _ -> boundary.takeIf { it > at } } }
        runCurrent()
        advanceTimeBy(4_999)
        clock.reread()
        assertEquals("the reread shows B - 1", boundary - 1, clock.nowMs)
        lag = 2
        runCurrent()
        assertEquals("the resumed round shows the instant it planned against, past B", boundary + 1, clock.nowMs)
        loop.cancel()
    }

    @Test fun `the wait is measured from when the boundary was computed`() = runTest {
        // Reading the boundary takes 2 s of the clock (a slow projection on a busy main thread).
        // The wake-up still lands at the boundary, not 2 s after it.
        var computing = 0L
        val clock = ScreenClock(now = { origin + currentTime + computing }, zone = { utc })
        val loop = launch { clock.run { _, _ -> (origin + 7_000).also { computing = 2_000 } } }
        runCurrent()
        advanceTimeBy(4_999); runCurrent()
        assertEquals("not before the boundary", origin, clock.nowMs)
        advanceTimeBy(1); runCurrent()
        assertEquals("at the boundary on the clock it was computed against", origin + 7_000, clock.nowMs)
        loop.cancel()
    }

    @Test fun `the clock runs only while started, and reads the time again on every start`() = runTest {
        val clock = clock()
        // A lifecycle this test moves by hand; the registry's main-thread check does not apply here.
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        // repeatOnLifecycle watches the lifecycle on the main dispatcher: here, the test's own.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val loop = launch { clock.runWhileStarted(owner.lifecycle) { _, _ -> null } }
        try {
            runCurrent()
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START); runCurrent()
            advanceTimeBy(TICK_INTERVAL_MS); runCurrent()
            assertEquals("ticks while started", origin + TICK_INTERVAL_MS, clock.nowMs)

            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP); runCurrent()
            advanceTimeBy(5 * TICK_INTERVAL_MS); runCurrent()
            assertEquals("no tick after STOP", origin + TICK_INTERVAL_MS, clock.nowMs)

            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START); runCurrent()
            assertEquals("START reads the time at once", origin + 6 * TICK_INTERVAL_MS, clock.nowMs)
        } finally {
            // The loop runs on the main dispatcher: it has to end while that is still the test's
            // own, also after a failed assertion. Reset first, and its cancellation would wait for
            // a main dispatcher that is gone, which hung this test under a mutant.
            loop.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `the boundary is asked again after every wake-up`() = runTest {
        val clock = clock()
        val boundaries = ArrayDeque(listOf(origin + 1_000, origin + 1_500))
        val loop = launch { clock.run { _, _ -> boundaries.firstOrNull { it > origin + currentTime } } }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(origin + 1_000, clock.nowMs)
        advanceTimeBy(500); runCurrent()
        assertEquals("the second boundary, found after the first wake-up", origin + 1_500, clock.nowMs)
        loop.cancel()
    }
}
