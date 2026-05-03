package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.SharingStarted

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [FavoritesOrderRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - `mockContext` wird nur als Konstruktor-Argument durchgereicht.
 *   - `externalScope = null` umgeht den `shareIn`-Layer aus den im
 *     [FavoritesRepositoryImplContractTest] dokumentierten Gründen.
 *   - Konstruktion via `createForTesting`, weil der primäre Konstruktor
 *     `private` ist.
 */
class FavoritesOrderRepositoryImplContractTest : FavoritesOrderRepositoryContract() {

    override fun createRepository(): FavoritesOrderRepository {
        val fakeDataStore = FakeDataStore()
        val context: Context = mockk(relaxed = true)

        return FavoritesOrderRepositoryImpl.createForTesting(
            dataStore = fakeDataStore,
            context = context,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )
    }
}
