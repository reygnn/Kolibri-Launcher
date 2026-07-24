package com.github.reygnn.kolibri_launcher.data

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.ApplicationScope
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import timber.log.Timber
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager für die Zuweisung von Swipe-Aktionen (Links/Rechts).
 *
 * Diese Klasse implementiert das `SwipeActionsRepository`-Interface und folgt dem
 * gleichen Architekturmuster wie `FavoritesOrderRepositoryImpl`.
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
open class SwipeActionsRepositoryImpl private constructor(
    dataStore: DataStore<Preferences>,
    externalScope: CoroutineScope?,
    sharingStrategy: SharingStarted,
) : SharedDataStoreFlowRepository(dataStore, externalScope, sharingStrategy),
    SwipeActionsRepository {

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
        fun createForTesting(
            dataStore: DataStore<Preferences>,
            externalScope: CoroutineScope?,
            sharingStrategy: SharingStarted
        ): SwipeActionsRepositoryImpl {
            return SwipeActionsRepositoryImpl(dataStore, externalScope, sharingStrategy)
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
     * Builds the hot, shared flow for a single swipe-slot key.
     */
    private fun swipeActionFlow(key: Preferences.Key<String>): Flow<String?> =
        sharedReadFlow("Error reading swipe action key: ${key.name}") { preferences ->
            // Returns the stored String, or null when the slot is unset — which
            // is exactly what the `String?` interface type expects.
            preferences[key]
        }

    override val swipeLeftAppFlow: Flow<String?> =
        swipeActionFlow(PreferencesKeys.SWIPE_LEFT_APP_COMPONENT)

    override val swipeRightAppFlow: Flow<String?> =
        swipeActionFlow(PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT)

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

    override suspend fun cleanupSwipeActions(installedComponentNames: List<String>) {
        try {
            val installedSet = installedComponentNames.toSet()
            dataStore.edit { preferences ->
                // Remove each slot whose target is no longer installed. Log only
                // the slot, never the componentName (PII), and never crash — on
                // failure the current state is kept.
                val left = preferences[PreferencesKeys.SWIPE_LEFT_APP_COMPONENT]
                if (left != null && left !in installedSet) {
                    preferences.remove(PreferencesKeys.SWIPE_LEFT_APP_COMPONENT)
                    Timber.w("Removed orphaned LEFT swipe action")
                }
                val right = preferences[PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT]
                if (right != null && right !in installedSet) {
                    preferences.remove(PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT)
                    Timber.w("Removed orphaned RIGHT swipe action")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to cleanup swipe actions, keeping current state")
        }
    }

    override suspend fun purgeRepository() {
        dataStore.safePurge("SwipeActionsRepositoryImpl") { preferences ->
            preferences.remove(PreferencesKeys.SWIPE_LEFT_APP_COMPONENT)
            preferences.remove(PreferencesKeys.SWIPE_RIGHT_APP_COMPONENT)
        }
    }
}
