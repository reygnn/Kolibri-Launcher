package com.github.reygnn.kolibri_launcher.crashreporting.consent

import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore

/**
 * Runs [CrashReportConsentRepositoryContract] against the real production
 * class [CrashReportConsentRepositoryImpl], backed by an in-memory
 * [FakeDataStore]. The impl has a plain `@Inject` constructor (no `shareIn`
 * layer), so it is constructed directly.
 */
class CrashReportConsentRepositoryImplContractTest : CrashReportConsentRepositoryContract() {

    override fun createRepository(): CrashReportConsentRepository =
        CrashReportConsentRepositoryImpl(dataStore = FakeDataStore())
}
