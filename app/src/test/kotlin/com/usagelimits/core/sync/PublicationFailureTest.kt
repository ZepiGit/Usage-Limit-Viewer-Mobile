package com.usagelimits.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PublicationFailureTest {
    @Test fun `a failed publication step never skips the remaining effects or fails a committed change`() = runBlocking {
        for (cacheMoved in listOf(false, true)) {
            val expected = if (cacheMoved) listOf("repaint", "schedule", "alerts") else listOf("repaint", "schedule")
            for (failedStep in expected) {
                val attempted = mutableListOf<String>()
                suspend fun effect(step: String) {
                    attempted += step
                    if (step == failedStep) throw IOException("$step unavailable")
                }
                val publication: Publication = OutOfAppPublication(
                    repaint = { effect("repaint") },
                    scheduleBoundary = { effect("schedule") },
                    publishAlerts = { effect("alerts") },
                )

                if (cacheMoved) publication.cacheMoved() else publication.presentationChanged()

                assertEquals("Failure in $failedStep", expected, attempted)
            }
        }
    }

    @org.robolectric.annotation.Config(application = android.app.Application::class)
    @Test fun `an unavailable WorkManager does not stop repainting or alerts`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        resetTestWorkManager()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            androidx.work.WorkManager.getInstance(context)
        }
        val completed = mutableListOf<String>()
        val publication: Publication = OutOfAppPublication(
            repaint = { completed += "repaint" },
            scheduleBoundary = {
                com.usagelimits.widget.WidgetPresentationWorker.scheduleNext(context,
                    System.currentTimeMillis() + 60_000, System.currentTimeMillis())
            },
            publishAlerts = { completed += "alerts" },
        )

        publication.presentationChanged()
        publication.cacheMoved()

        assertEquals(listOf("repaint", "repaint", "alerts"), completed)
        assertEquals(2, org.robolectric.shadows.ShadowLog.getLogsForTag("Publication").count {
            it.msg == "presentation boundary publication failed" && it.throwable is IllegalStateException
        })
    }

    @Test fun `cancellation stops publication and still reaches its caller`() = runBlocking {
        for (cacheMoved in listOf(false, true)) {
            val steps = if (cacheMoved) listOf("repaint", "schedule", "alerts") else listOf("repaint", "schedule")
            for ((index, cancelledStep) in steps.withIndex()) {
                val cancelled = CancellationException("worker stopped")
                val attempted = mutableListOf<String>()
                suspend fun effect(step: String) {
                    attempted += step
                    if (step == cancelledStep) throw cancelled
                }
                val publication: Publication = OutOfAppPublication(
                    repaint = { effect("repaint") },
                    scheduleBoundary = { effect("schedule") },
                    publishAlerts = { effect("alerts") },
                )
                try {
                    if (cacheMoved) publication.cacheMoved() else publication.presentationChanged()
                    fail("Cancellation must propagate")
                } catch (error: CancellationException) {
                    assertSame(cancelled, error)
                }
                assertEquals(steps.take(index + 1), attempted)
            }
        }
    }
}
