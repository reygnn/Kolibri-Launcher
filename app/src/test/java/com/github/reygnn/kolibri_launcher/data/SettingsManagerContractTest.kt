package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [SettingsManager].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double (das echte DataStore braucht ein
 *     Android-Dateisystem — für Unit-Tests nicht nutzbar).
 *   - `mockContext` ist relaxed: [SettingsManager] benutzt den Context in den
 *     hier getesteten Pfaden nicht; er wird nur konstruktorseitig
 *     durchgereicht (Hilt-Signatur).
 *
 * Anders als bei [FavoritesManager] ist hier kein sekundärer Konstruktor /
 * `SharingStarted` nötig — [SettingsManager] nutzt kein `shareIn`, die Flows
 * bauen direkt auf `dataStore.data.map { }` auf.
 */
class SettingsManagerContractTest : SettingsRepositoryContract() {

    override fun createRepository(): SettingsRepository {
        val fakeDataStore = FakeDataStore()
        val mockContext: Context = mockk(relaxed = true)
        return SettingsManager(fakeDataStore, mockContext)
    }
}
