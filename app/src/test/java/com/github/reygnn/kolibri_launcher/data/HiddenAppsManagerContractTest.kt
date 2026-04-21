package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [HiddenAppsManager].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - `mockContext` wird relaxed gemockt — der Manager nimmt ihn nur als
 *     Konstruktor-Argument entgegen und berührt ihn in den hier getesteten
 *     Pfaden nicht.
 *   - Anders als bei `FavoritesManager` benötigt `HiddenAppsManager` weder
 *     `externalScope` noch `SharingStarted`-Konfiguration: sein Flow ist
 *     **cold** und bei jeder Subscription fresh aus `dataStore.data.map { … }`
 *     gebaut. Damit gibt es kein `shareIn`-Replay-Drama, das man umschiffen
 *     müsste.
 */
class HiddenAppsManagerContractTest : HiddenAppsRepositoryContract() {

    override fun createRepository(): HiddenAppsRepository {
        val fakeDataStore = FakeDataStore()
        val mockContext: Context = mockk(relaxed = true)
        return HiddenAppsManager(fakeDataStore, mockContext)
    }
}
