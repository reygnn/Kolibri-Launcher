/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVisibilityManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : AppVisibilityRepository {

    private object PreferencesKeys {
        val HIDDEN_COMPONENTS = stringSetPreferencesKey("hidden_components_set")
    }

    override val hiddenAppsFlow: Flow<Set<String>>
        get() = dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading hidden components preferences.")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] ?: emptySet()
            }

    override suspend fun isComponentHidden(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            hiddenAppsFlow.first().contains(componentName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error checking if component is hidden: $componentName")
            false
        }
    }

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            val currentHidden = hiddenAppsFlow.first()

            // Bereits versteckt - frühzeitiger Erfolg
            if (currentHidden.contains(componentName)) {
                return true
            }

            dataStore.edit { preferences ->
                val newHidden = currentHidden + componentName
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = newHidden
            }

            Timber.i("Component hidden: $componentName")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error hiding component: $componentName")
            false
        }
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName.isNullOrBlank()) return false

        return try {
            val currentHidden = hiddenAppsFlow.first()

            // Bereits sichtbar - frühzeitiger Erfolg
            if (!currentHidden.contains(componentName)) {
                return true
            }

            dataStore.edit { preferences ->
                val newHidden = currentHidden - componentName
                preferences[PreferencesKeys.HIDDEN_COMPONENTS] = newHidden
            }

            Timber.i("Component shown: $componentName")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing component: $componentName")
            false
        }
    }

    override fun purgeRepository() { }
}