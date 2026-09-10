package com.github.reygnn.nyx_launcher.home.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PreferencesRepository] test double. */
class FakePreferencesRepository(monochrome: Boolean = false) : PreferencesRepository {
    private val state = MutableStateFlow(monochrome)
    override fun monochromeIcons(): Flow<Boolean> = state
    override suspend fun setMonochromeIcons(enabled: Boolean) { state.value = enabled }
}
