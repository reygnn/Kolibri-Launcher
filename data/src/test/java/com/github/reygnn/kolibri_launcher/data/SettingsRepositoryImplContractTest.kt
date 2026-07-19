package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [SettingsRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double (das echte DataStore braucht ein
 *     Android-Dateisystem — für Unit-Tests nicht nutzbar).
 *   - `mockContext` ist relaxed: [SettingsRepositoryImpl] benutzt den Context in den
 *     hier getesteten Pfaden nicht; er wird nur konstruktorseitig
 *     durchgereicht (Hilt-Signatur).
 *
 * Anders als bei [FavoritesRepositoryImpl] ist hier kein sekundärer Konstruktor /
 * `SharingStarted` nötig — [SettingsRepositoryImpl] nutzt kein `shareIn`, die Flows
 * bauen direkt auf `dataStore.data.map { }` auf.
 */
class SettingsRepositoryImplContractTest : SettingsRepositoryContract() {

    override fun createRepository(): SettingsRepository {
        val fakeDataStore = FakeDataStore()
        return SettingsRepositoryImpl(fakeDataStore)
    }
}
