package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.SharingStarted

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [FavoritesRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double (das echte DataStore braucht
 *     Android-Dateisystem — für Unit-Tests nicht nutzbar).
 *   - `mockContext` ist relaxed, weil [FavoritesRepositoryImpl] den Context nur als
 *     Konstruktor-Handover nimmt und ihn in den hier getesteten Pfaden nicht
 *     wirklich berührt.
 *
 * WARUM `externalScope = null`:
 *   Mit einem externen Scope legt `FavoritesRepositoryImpl` einen `shareIn(…,
 *   replay = 1)` über `dataStore.data`. Unter `UnconfinedTestDispatcher`
 *   führt das im selben `runTest`-Block zu stale Reads: der
 *   Upstream-Collector bekommt den DataStore-Update erst mit dem nächsten
 *   Scheduler-Tick in den Replay-Buffer geschrieben — wir lesen in der
 *   Zwischenzeit den alten gecachten Wert. `externalScope = null` schaltet
 *   den `shareIn`-Layer ab; jeder `.first()`-Aufruf auf
 *   `favoriteComponentsFlow` macht dann eine frische Subscription auf
 *   `dataStore.data` (MutableStateFlow) und sieht den aktuellen Wert.
 *
 *   Die `shareIn`-Semantik (Hot-Sharing, `WhileSubscribed`-Timeout) ist
 *   Produktions-Infrastruktur und wird separat im `FavoritesRepositoryImplTest`
 *   geprüft — sie gehört nicht zum Interface-Contract.
 *
 *   Der zweite (`@VisibleForTesting`-) Konstruktor wird trotzdem benutzt,
 *   weil er die einzige Möglichkeit ist, `externalScope = null` plus eine
 *   explizite `SharingStarted` zu übergeben. Die angegebene
 *   `SharingStarted.Lazily` ist funktional irrelevant, solange der Scope
 *   null ist (kein `shareIn` wird angewendet).
 */
class FavoritesRepositoryImplContractTest : FavoritesRepositoryContract() {

    override fun createRepository(): FavoritesRepository {
        val fakeDataStore = FakeDataStore()
        val context: Context = mockk(relaxed = true)

        return FavoritesRepositoryImpl(
            dataStore = fakeDataStore,
            context = context,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )
    }
}
