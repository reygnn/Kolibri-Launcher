package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed persistence for the wallpaper-edit speed-dial FAB
 * position. The two floats are kept as two separate `floatPreferencesKey`
 * entries (`wallpaper_edit_fab_x_fraction`, `..._y_fraction`) rather
 * than packed into a single string — DataStore's `floatPreferencesKey`
 * already gives type-safe round-tripping and avoids a parser.
 *
 * Defaults: when neither key has been written yet (fresh install,
 * post-purge), the flow emits [FabPosition.DEFAULT]. No out-of-range
 * protection — see [FabPosition] for why clamping is the consumer's job.
 *
 * **Cold read, no hot share (DATASTORE_READ_SPEC Belang A).** [fabPositionFlow]
 * is a plain cold flow via [readFlowFailOpen]; there is no
 * `shareIn(WhileSubscribed)` replay cache. The FAB position is rendered
 * continuously on edit-mode entry, never point-read for a decision, so the
 * hot share bought nothing here and only carried the stale-replay hazard.
 * The constructor takes just the [DataStore] — no `externalScope` /
 * `sharingStrategy`, no dual constructor, no test factory.
 */
@Singleton
class FabPositionRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : FabPositionRepository, OwnsSettingsStoreKeys {

    private object PreferencesKeys {
        val FAB_X_FRACTION = floatPreferencesKey("wallpaper_edit_fab_x_fraction")
        val FAB_Y_FRACTION = floatPreferencesKey("wallpaper_edit_fab_y_fraction")
    }

    override fun ownedExactKeys(): Set<String> = setOf(
        PreferencesKeys.FAB_X_FRACTION.name,
        PreferencesKeys.FAB_Y_FRACTION.name,
    )

    override val fabPositionFlow: Flow<FabPosition> =
        dataStore.readFlowFailOpen("Error reading FAB position preferences") { preferences ->
            FabPosition(
                xFraction = preferences[PreferencesKeys.FAB_X_FRACTION]
                    ?: FabPosition.DEFAULT.xFraction,
                yFraction = preferences[PreferencesKeys.FAB_Y_FRACTION]
                    ?: FabPosition.DEFAULT.yFraction,
            )
        }

    override suspend fun saveFabPosition(position: FabPosition) {
        try {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.FAB_X_FRACTION] = position.xFraction
                preferences[PreferencesKeys.FAB_Y_FRACTION] = position.yFraction
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving FAB position")
            throw e
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("FabPositionRepositoryImpl") { preferences ->
            preferences.remove(PreferencesKeys.FAB_X_FRACTION)
            preferences.remove(PreferencesKeys.FAB_Y_FRACTION)
        }
    }
}
