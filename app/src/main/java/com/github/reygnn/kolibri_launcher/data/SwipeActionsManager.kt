package com.github.reygnn.kolibri_launcher.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager für die Zuweisung von Swipe-Aktionen (Links/Rechts).
 *
 * Diese Klasse implementiert das `SwipeActionsRepository`-Interface und folgt dem
 * gleichen Architekturmuster wie `FavoritesOrderManager`.
 *
 * **Core Funktionalität:**
 * - Speichert und liest den ComponentName für "Swipe Left".
 * - Speichert und liest den ComponentName für "Swipe Right".
 * - Stellt beide Werte als Hot, Shared Flows bereit.
 *
 * **Architektur: Hot Shared Flow**
 * - Verwendet `shareIn()` mit `WhileSubscribed`, um eine einzige, geteilte
 * Subscription zum DataStore über alle Beobachter hinweg zu nutzen.
 * - Stellt Testbarkeit durch einen privaten Konstruktor und eine `createForTesting`-Factory-Methode sicher,
 * die eine benutzerdefinierte `SharingStarted`-Strategie erlaubt.
 *
 * **Datenfluss:**
 * 1. `SwipeActionsViewModel` ruft `setSwipeAction()` auf.
 * 2. Manager speichert den ComponentName (oder null) im DataStore.
 * 3. DataStore emittiert den neuen Wert.
 * 4. `swipeLeftAppFlow` / `swipeRightAppFlow` geben den neuen Wert an das ViewModel weiter.
 * 5. ViewModel berechnet den `SwipeActionsUiState` neu.
 *
 * @param dataStore Preferences DataStore zur Persistierung.
 * @param externalScope Application-Scope für das Hot-Flow-Sharing.
 * @param sharingStrategy Die Strategie für das Sharing (z.B. WhileSubscribed).
 */
@Singleton
open class SwipeActionsManager private constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted
) : SwipeActionsRepository {

    /**
     * Interne Definition der DataStore-Schlüssel, spezifisch für diesen Manager.
     */
    private object PreferencesKeys {
        val SWIPE_LEFT_APP_COMPONENT = stringPreferencesKey("swipe_left_app_component")
        val SWIPE_RIGHT_APP_COMPONENT = stringPreferencesKey("swipe_right_app_component")
    }

    companion object {
        /**
         * Factory-Methode zur Erstellung einer Instanz für Unit-Tests.
         */
        @VisibleForTesting
        internal fun createForTesting(
            dataStore: DataStore<Preferences>,
            externalScope: CoroutineScope?,
            sharingStrategy: SharingStarted
        ): SwipeActionsManager {
            return SwipeActionsManager(dataStore, externalScope, sharingStrategy)
        }
    }

    /**
     * Hilt-Konstruktor für die Produktion.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        @ApplicationScope externalScope: CoroutineScope?
    ) : this(
        dataStore = dataStore,
        externalScope = externalScope,
        sharingStrategy = SharingStarted.Companion.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
    )

    /**
     * Helper-Funktion, um das `shareIn`-Muster für beide Flows zu erstellen.
     */
    private fun createSwipeActionFlow(
        key: Preferences.Key<String>,
        strategy: SharingStarted
    ): Flow<String?> {
        return dataStore.data
            .catch { e ->
                if (e is IOException) {
                    TimberWrapper.silentError(e, "Error reading swipe action key: ${key.name}")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { preferences ->
                // Gibt den String-Wert oder null zurück, wenn nicht vorhanden.
                // Das `?` bei `String?` im Interface passt hier perfekt.
                preferences[key]
            }
            .let { flow ->
                // Teile den Flow nur, wenn ein externalScope vorhanden ist (d.h. nicht im Test)
                if (externalScope != null) {
                    flow.shareIn(
                        scope = externalScope,
                        started = strategy,
                        replay = 1
                    )
                } else {
                    flow
                }
            }
    }

    override val swipeLeftAppFlow: Flow<String?> = createSwipeActionFlow(
        PreferencesKeys.SWIPE_LEFT_APP_COMPONENT, sharingStrategy
    )

    override val swipeRightAppFlow: Flow<String?> = createSwipeActionFlow(
        PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT, sharingStrategy
    )

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        val key = when (slot) {
            SwipeSlot.LEFT -> PreferencesKeys.SWIPE_LEFT_APP_COMPONENT
            SwipeSlot.RIGHT -> PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT
            SwipeSlot.NONE -> {
                Timber.Forest.w("setSwipeAction called with NONE, ignoring.")
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

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(PreferencesKeys.SWIPE_LEFT_APP_COMPONENT)
                preferences.remove(PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to purge SwipeActionsManager repository")
        }
    }
}