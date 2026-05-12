package com.github.reygnn.kolibri_launcher.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.io.IOException
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
 * post-purge), the flow emits [FabPosition.DEFAULT]. No
 * out-of-range protection — see [FabPosition] for why clamping is the
 * consumer's job.
 *
 * Sharing follows the [SwipeActionsRepositoryImpl] template: `shareIn`
 * with `WhileSubscribed(FLOW_SHARING_TIMEOUT_MS)` in production, raw
 * flow when [externalScope] is `null` (test path).
 */
@Singleton
open class FabPositionRepositoryImpl private constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted,
) : FabPositionRepository {

    private object PreferencesKeys {
        val FAB_X_FRACTION = floatPreferencesKey("wallpaper_edit_fab_x_fraction")
        val FAB_Y_FRACTION = floatPreferencesKey("wallpaper_edit_fab_y_fraction")
    }

    companion object {
        @VisibleForTesting
        fun createForTesting(
            dataStore: DataStore<Preferences>,
            externalScope: CoroutineScope?,
            sharingStrategy: SharingStarted,
        ): FabPositionRepositoryImpl =
            FabPositionRepositoryImpl(dataStore, externalScope, sharingStrategy)
    }

    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationScope externalScope: CoroutineScope?,
    ) : this(
        dataStore = dataStore,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
    )

    override val fabPositionFlow: Flow<FabPosition> = run {
        val source = dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading FAB position preferences")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                FabPosition(
                    xFraction = preferences[PreferencesKeys.FAB_X_FRACTION]
                        ?: FabPosition.DEFAULT.xFraction,
                    yFraction = preferences[PreferencesKeys.FAB_Y_FRACTION]
                        ?: FabPosition.DEFAULT.yFraction,
                )
            }
        if (externalScope != null) {
            source.shareIn(
                scope = externalScope,
                started = sharingStrategy,
                replay = 1,
            )
        } else {
            source
        }
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
