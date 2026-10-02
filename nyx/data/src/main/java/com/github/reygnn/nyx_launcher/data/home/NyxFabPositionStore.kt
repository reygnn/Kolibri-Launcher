package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.github.reygnn.launcher.common.data.safePurge
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.launcher.common.data.readFlowFailOpen
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the wallpaper-edit speed-dial FAB's position (two [0f,1f] fractions)
 * in Nyx's home_layout DataStore. Emits [FabPosition.DEFAULT] when unset.
 */
@Singleton
class NyxFabPositionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : Purgeable {
    val fabPositionFlow: Flow<FabPosition> =
        dataStore.readFlowFailOpen("Error reading FAB position") { prefs ->
            val x = prefs[X]
            val y = prefs[Y]
            if (x != null && y != null) FabPosition(x, y) else FabPosition.DEFAULT
        }

    suspend fun saveFabPosition(position: FabPosition) {
        dataStore.edit {
            it[X] = position.xFraction
            it[Y] = position.yFraction
        }
    }

    /**
     * Factory reset (2b-4c): removes this store's keys from the shared `home_layout` DataStore.
     * A failure is rethrown by `safePurge` (F1), so the reset reports it.
     */
    override suspend fun purgeRepository() {
        dataStore.safePurge("NyxFabPositionStore") { preferences ->
            preferences.remove(X)
            preferences.remove(Y)
        }
    }

    private companion object {
        val X = floatPreferencesKey("wallpaper_edit_fab_x_fraction")
        val Y = floatPreferencesKey("wallpaper_edit_fab_y_fraction")
    }
}
