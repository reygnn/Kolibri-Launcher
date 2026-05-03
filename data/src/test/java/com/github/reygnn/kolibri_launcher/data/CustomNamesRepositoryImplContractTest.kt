package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import io.mockk.mockk
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Contract-Test-Ausführung gegen die echte Produktionsklasse [CustomNamesRepositoryImpl].
 *
 * Setup-Details:
 *   - [FakeDataStore] als DataStore-Double.
 *   - Echter `MutableSharedFlow<Unit>` als appsUpdateTrigger. Konfiguration
 *     unterscheidet sich BEWUSST von Produktion (Begründung unten).
 *   - `mockContext` relaxed gemockt — der Manager hält ihn nur als
 *     Konstruktor-Argument und berührt ihn in den hier getesteten Pfaden nicht.
 *
 * WARUM `BufferOverflow.DROP_OLDEST` und nicht die Produktions-Konfiguration:
 *   In Produktion ist der Trigger `MutableSharedFlow(replay = 0,
 *   extraBufferCapacity = 1)` mit Default `BufferOverflow.SUSPEND`. Das passt,
 *   weil dort `InstalledAppsRepositoryImpl` als Subscriber permanent zuhört und
 *   `emit`s aus dem Buffer rauszieht. In den Contract-Tests gibt es KEINEN
 *   Subscriber — der zweite `emit()` würde den 1-Slot-Buffer überfüllen und
 *   die Coroutine permanent suspendieren. Mit `DROP_OLDEST` schluckt der Flow
 *   beliebig viele Triggers ohne zu blockieren. Das Trigger-Verhalten
 *   selbst wird im Contract nicht beobachtet (siehe Klassen-KDoc des
 *   Contracts), also ist dieser Workaround sicher.
 */
class CustomNamesRepositoryImplContractTest : CustomNamesRepositoryContract() {

    override fun createRepository(): CustomNamesRepository {
        val fakeDataStore = FakeDataStore()
        val appsUpdateTrigger = MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val context: Context = mockk(relaxed = true)
        return CustomNamesRepositoryImpl(fakeDataStore, appsUpdateTrigger, context)
    }
}

