package com.usagelimits.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The screens' clock on the test scheduler's virtual time: the wall clock it reads is the
 * scheduler's own time plus a fixed origin, so every wake-up is decided by the code under test and
 * nothing waits on real time. Each test cancels the loop it starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenClockTest {
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
