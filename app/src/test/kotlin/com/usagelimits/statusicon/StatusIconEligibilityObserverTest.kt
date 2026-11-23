package com.usagelimits.statusicon

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusIconEligibilityObserverTest {
    @Test fun `captured observations stay ordered through a held publication and restore once per edge`() = runTest {
        var known: Boolean? = null
        var publications = 0
        val recorded = mutableListOf<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observer = StatusIconEligibilityObserver(this, record = { allowed ->
            recorded += allowed
            val edge = known == false && allowed
            known = allowed
            edge
        }, publish = {
            publications++
            if (publications == 1) {
                entered.complete(Unit)
                release.await()
            }
        })
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            jobs += observer.observe(true)
            runCurrent()
            assertEquals("unknown-to-allowed is not a restoration edge", 0, publications)
            jobs += observer.observe(false)
            jobs += observer.observe(true)
            runCurrent()
            assertTrue("the first restoration is held in flight", entered.isCompleted && !jobs.last().isCompleted)
            jobs += observer.observe(true)
            jobs += observer.observe(false)
            jobs += observer.observe(true)
            runCurrent()
            assertEquals("later observations cannot overtake the held publication", listOf(true, false, true), recorded)
            release.complete(Unit)
            jobs.last().join()
            assertEquals(listOf(true, false, true, true, false, true), recorded)
            assertEquals("ordinary resumes do not restore; each actual edge does", 2, publications)
        } finally {
            release.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun `the settings store retains and transactionally claims eligibility without changing display settings`() = runTest {
        val context: Application = ApplicationProvider.getApplicationContext()
        val store = SettingsStore(context)
        store.recordStatusIconEligibility(true)
        val settings = store.settings.first()
        assertFalse(store.recordStatusIconEligibility(false))
        val restarted = SettingsStore(context)
        assertEquals(false, restarted.statusIconEligibility.first())
        val claim = async { store.recordStatusIconEligibility(true) }
        val competingClaim = async { restarted.recordStatusIconEligibility(true) }
        try {
            assertEquals("the persisted edge is claimed once across store instances", 1,
                listOf(claim.await(), competingClaim.await()).count { it })
        } finally {
            claim.cancelAndJoin()
            competingClaim.cancelAndJoin()
        }
        restarted.recordStatusIconEligibility(false)
        var edges = 0
        val observer = StatusIconEligibilityObserver(this, restarted::recordStatusIconEligibility) { edges++ }
        val first = observer.observe(true)
        val duplicate = observer.observe(true)
        try {
            duplicate.join()
            assertEquals("one persisted edge despite two entry callbacks", 1, edges)
            assertEquals(true, SettingsStore(context).statusIconEligibility.first())
            assertEquals("eligibility metadata must not change AppSettings", settings, store.settings.first())
        } finally {
            first.cancelAndJoin()
            duplicate.cancelAndJoin()
        }
    }
}
