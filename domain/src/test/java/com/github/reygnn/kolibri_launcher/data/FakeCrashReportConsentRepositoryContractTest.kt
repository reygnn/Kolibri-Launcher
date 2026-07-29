package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCrashReportConsentRepository

/**
 * Runs [CrashReportConsentRepositoryContract] against the unit-test fake
 * [FakeCrashReportConsentRepository].
 */
class FakeCrashReportConsentRepositoryContractTest : CrashReportConsentRepositoryContract() {

    override fun createRepository(): CrashReportConsentRepository = FakeCrashReportConsentRepository()
}
