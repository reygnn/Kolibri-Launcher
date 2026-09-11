package com.github.reygnn.launcher.feature.crashreporting.consent

import com.github.reygnn.kolibri_launcher.crashreporting.consent.CrashReportConsentRepositoryContract
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.launcher.core.crashreporting.consent.CrashReportConsentRepository

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
