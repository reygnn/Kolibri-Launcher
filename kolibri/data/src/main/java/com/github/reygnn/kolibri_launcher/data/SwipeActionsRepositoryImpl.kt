package com.github.reygnn.kolibri_launcher.data
import com.github.reygnn.launcher.common.data.safePurge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [SwipeActionsRepository].
 *
 * Stores the assigned ComponentName for the LEFT and RIGHT swipe slots in a
 * Preferences DataStore, one key per slot.
 *
 * **All reads are authoritative and fresh** (`dataStore.data.first()`): the
 * launch path ([getSwipeActionComponent]) and the reconcile path read the
 * current stored value directly, so a slot reassigned in the Settings activity
 * takes effect on the very next swipe. There is no hot/shared flow — an earlier
 * `WhileSubscribed(replay = 1)` share was removed because nothing consumed it,
 * and its replay cache could hand back a stale assignment on the first swipe
 * after a change while Home held no subscriber.
 *
 * @param dataStore Preferences DataStore used for persistence.
 */
@Singleton
class SwipeActionsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SwipeActionsRepository, OwnsSettingsStoreKeys {

    /**
     * Interne Definition der DataStore-Schlüssel, spezifisch für diesen Manager.
     */
    private object PreferencesKeys {
        val SWIPE_LEFT_APP_COMPONENT = stringPreferencesKey("swipe_left_app_component")
        val SWIPE_RIGHT_APP_COMPONENT = stringPreferencesKey("swipe_right_app_component")
    }

    override fun ownedExactKeys(): Set<String> = setOf(
        PreferencesKeys.SWIPE_LEFT_APP_COMPONENT.name,
        PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT.name,
    )

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        val key = when (slot) {
            SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT -> PreferencesKeys.SWIPE_LEFT_APP_COMPONENT
            SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT -> PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT
            SwipeSlot.NONE -> {
                Timber.w("setSwipeAction called with NONE, ignoring.")
                return // Keine Aktion für "NONE"
            }
        }

        try {
            dataStore.edit { preferences ->
                if (componentName == null) {
                    // Wenn null, entferne den Schlüssel, um den Slot zu leeren
                    preferences.remove(key)
                } else {
                    // Setze den ComponentName für den jeweiligen Slot
                    preferences[key] = componentName
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Das ViewModel fängt dies über launchSafe ab, aber wir loggen es hier.
            TimberWrapper.silentError(e, "Error saving swipe action for $slot")
            // Wir werfen den Fehler weiter, damit der Aufrufer (VM) darauf reagieren kann
            throw e
        }
    }

    override suspend fun getSwipeActionComponent(slot: SwipeSlot): String? {
        val key = when (slot) {
            SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT -> PreferencesKeys.SWIPE_LEFT_APP_COMPONENT
            SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT -> PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT
            SwipeSlot.NONE -> return null
        }
        return try {
            // Authoritative FRESH read straight from the store. The launch
            // decision needs the current assignment: a slot changed in the
            // Settings activity must take effect on the very next swipe, so the
            // read never goes through a cache.
            dataStore.data.first()[key]
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Expected failure mode (I/O), handled non-destructively: a transient
            // read error yields no launch (NoAction), never a wrong app. Plain
            // Timber.w (not silentError) — an IOException here is a real I/O
            // failure, not a programmer error, so it must not throw in DEBUG.
            Timber.w(e, "Error reading swipe action for $slot; treating as unassigned")
            null
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("SwipeActionsRepositoryImpl") { preferences ->
            preferences.remove(PreferencesKeys.SWIPE_LEFT_APP_COMPONENT)
            preferences.remove(PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT)
        }
    }
}
