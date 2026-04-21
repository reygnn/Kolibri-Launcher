package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.ReactiveFakeInstalledAppsRepository

/**
 * Contract-Test-Ausführung gegen den spezialisierten Fake
 * [ReactiveFakeInstalledAppsRepository].
 *
 * Siehe [InstalledAppsRepositoryContract] für die tatsächlichen Tests.
 *
 * SETUP:
 *   Dieser Fake braucht einen [com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository]
 *   im Konstruktor. Wir injizieren einen frischen [FakeCustomNamesRepository]
 *   ohne Custom Names — damit ist sein Verhalten in den Contract-Tests
 *   deterministisch (returns originalName).
 *
 *   Die hartkodierte 3-Apps-Liste und das Custom-Names-Mapping des Reactive-
 *   Fakes sind NICHT Teil dieses Contracts (das ist Verhalten, das das
 *   Interface gar nicht zusichert). Sie werden im Aufrufer
 *   `CustomNamesViewModelTest` getestet.
 */
class ReactiveFakeInstalledAppsRepositoryContractTest : InstalledAppsRepositoryContract() {

    override fun createRepository(): InstalledAppsRepository {
        val customNamesRepository = FakeCustomNamesRepository()
        return ReactiveFakeInstalledAppsRepository(customNamesRepository)
    }
}
