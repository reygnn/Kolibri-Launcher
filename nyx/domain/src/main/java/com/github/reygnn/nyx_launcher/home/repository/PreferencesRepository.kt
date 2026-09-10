package com.github.reygnn.nyx_launcher.home.repository

import kotlinx.coroutines.flow.Flow

/** User preferences (DataStore-backed). v1: the monochrome-icons toggle. */
interface PreferencesRepository {
    fun monochromeIcons(): Flow<Boolean>
    suspend fun setMonochromeIcons(enabled: Boolean)
}
