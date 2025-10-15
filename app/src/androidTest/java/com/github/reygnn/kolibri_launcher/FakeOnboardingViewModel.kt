// In FakeOnboardingViewModel.kt

package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A fake implementation of OnboardingViewModelInterface for instrumented tests.
 * This class provides full control over the state and events to the test.
 */
class FakeOnboardingViewModel @Inject constructor() :
    BaseViewModel<OnboardingEvent>(Dispatchers.Unconfined),
    OnboardingViewModelInterface {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    override val uiState: StateFlow<OnboardingUiState> = _uiState

    var onDoneClickedCallCount = 0
        private set

    // --- Öffentliche Methoden für den Test zur Manipulation ---

    /** Von Tests verwendet, um den UI-Zustand zu setzen. */
    fun setState(newState: OnboardingUiState) {
        _uiState.value = newState
    }

    /** Von Tests verwendet, um ein Event zu senden. */
    fun sendEventForTest(newEvent: OnboardingEvent) {
        launchSafe {
            sendEvent(newEvent)
        }
    }

    // --- Implementierung der Interface-Methoden ---

    override fun setLaunchMode(mode: LaunchMode) {
        // In diesem Fake ignorieren wir das, da der Zustand manuell gesetzt wird.
    }

    override fun loadInitialData() {
        // In diesem Fake ignorieren wir das, da die Daten manuell gesetzt werden.
    }

    override fun onSearchQueryChanged(query: String) {
        // In einem komplexeren Test könnten wir hier den query speichern.
    }

    override fun onAppToggled(app: AppInfo) {
        // In einem komplexeren Test könnten wir hier den Zustand ändern.
    }

    override fun onDoneClicked() {
        // Merke nur, dass die Methode aufgerufen wurde.
        onDoneClickedCallCount++
    }
}