package com.usagelimits.feature

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.feature.credits.AddCreditState
import com.usagelimits.feature.credits.AddCreditViewModel
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreditPublicationTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val viewModels = ViewModelStore()

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        viewModels.clear()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        Dispatchers.resetMain()
    }

    @Test fun `adding a credit balance reserves its first staleness repaint without usage accounts`() = runBlocking {
        val container = object : AppContainer(app) {
            override val credentialStore = MemoryCredentialStore()
            override val creditRegistry = CreditRegistry(scriptedHttp(
                200 to """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"1.00"}]}"""))
        }
        val vm = AddCreditViewModel(container, app)
        viewModels.put("add", vm)

        vm.submit(CreditProviderId.DEEPSEEK, "fixture", "Work", ProviderRegion.INTL, "", "5")
        val finished = vm.state.first { it is AddCreditState.Added || it is AddCreditState.Failed }

        assertTrue("Credit validation must succeed: $finished", finished is AddCreditState.Added)
        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(com.usagelimits.widget.WidgetPresentationWorker.WORK_NAME).get()
            .filter { it.state == androidx.work.WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
        assertTrue("The new balance must reserve a future repaint", pending.single().initialDelayMillis > 0)
    }
}
