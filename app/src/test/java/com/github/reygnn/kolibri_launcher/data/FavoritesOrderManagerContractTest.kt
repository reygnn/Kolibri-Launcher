package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.SharingStarted

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [FavoritesOrderManager].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - `mockContext` wird nur als Konstruktor-Argument durchgereicht.
 *   - `externalScope = null` umgeht den `shareIn`-Layer aus den im
 *     [FavoritesManagerContractTest] dokumentierten Gründen.
 *   - Konstruktion via `createForTesting`, weil der primäre Konstruktor
 *     `private` ist.
 */
class FavoritesOrderManagerContractTest : FavoritesOrderRepositoryContract() {

    override fun createRepository(): FavoritesOrderRepository {
        val fakeDataStore = FakeDataStore()
        val mockContext: Context = mockk(relaxed = true)

        return FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )
    }
}
