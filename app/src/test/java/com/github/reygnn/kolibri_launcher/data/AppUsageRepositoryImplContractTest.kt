package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [AppUsageRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - `mockContext` relaxed gemockt — der Manager hält ihn nur als
 *     Konstruktor-Argument und berührt ihn in den hier getesteten Pfaden nicht.
 *   - Kein `externalScope` / `SharingStarted` nötig: [AppUsageRepositoryImpl] benutzt
 *     kein `shareIn`, sondern liest pro Call frisch aus `dataStore.data.first()`.
 *
 * Siehe [AppUsageRepositoryContract] zur Begründung, warum dieser Contract die
 * Sortier-Reihenfolge nicht prüft (kurz: der Manager benutzt
 * `System.currentTimeMillis()` direkt, der Fake macht überhaupt keine
 * Sortierung).
 */
class AppUsageRepositoryImplContractTest : AppUsageRepositoryContract() {

    override fun createRepository(): AppUsageRepository {
        val fakeDataStore = FakeDataStore()
        val context: Context = mockk(relaxed = true)
        return AppUsageRepositoryImpl(fakeDataStore, context)
    }
}
