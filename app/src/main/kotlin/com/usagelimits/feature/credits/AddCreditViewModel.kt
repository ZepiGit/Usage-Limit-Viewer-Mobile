package com.usagelimits.feature.credits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddCreditState {
    data object Idle : AddCreditState
    /** The key is being tried against the provider; nothing is saved yet. */
    data object Validating : AddCreditState
    data class Failed(val message: String) : AddCreditState
    data class Added(val label: String) : AddCreditState
}

/** One add attempt: prove the key, then save it. A rejected key leaves nothing behind. */
class AddCreditViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<AddCreditState>(AddCreditState.Idle)
    val state: StateFlow<AddCreditState> = _state.asStateFlow()

    fun submit(
        provider: CreditProviderId,
        key: String,
        label: String,
        region: ProviderRegion,
        teamId: String,
        lowThreshold: String,
    ) {
        if (_state.value == AddCreditState.Validating) return
        val threshold = parseThreshold(lowThreshold)
        if (threshold == ThresholdInput.Invalid) {
            _state.value = AddCreditState.Failed("The low-balance figure must be a number above zero.")
            return
        }
        _state.value = AddCreditState.Validating
        viewModelScope.launch {
            _state.value = try {
                val account = container.creditAccounts.add(
                    provider = provider, key = key, label = label, region = region, teamId = teamId,
                    lowThreshold = (threshold as? ThresholdInput.Value)?.amount,
                )
                // The first read is already stored; the widgets only need telling.
                WidgetUpdater.refreshAll(appContext)
                AddCreditState.Added(account.label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CreditError) {
                AddCreditState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                // Never e.message: it could carry anything. This is the only other way to fail.
                AddCreditState.Failed("The key could not be saved on this device.")
            }
        }
    }

    fun reset() {
        _state.value = AddCreditState.Idle
    }

    class Factory(
        private val container: AppContainer,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddCreditViewModel(container, appContext) as T
    }
}
