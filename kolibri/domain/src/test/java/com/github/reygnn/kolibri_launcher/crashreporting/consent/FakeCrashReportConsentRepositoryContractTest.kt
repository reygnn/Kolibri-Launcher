package com.github.reygnn.kolibri_launcher.crashreporting.consent

import com.github.reygnn.launcher.core.crashreporting.consent.CrashReportConsentRepository
/**
 * Runs [CrashReportConsentRepositoryContract] against the unit-test fake
 * [FakeCrashReportConsentRepository].
 */
class FakeCrashReportConsentRepositoryContractTest : CrashReportConsentRepositoryContract() {

    override fun createRepository(): CrashReportConsentRepository = FakeCrashReportConsentRepository()
}
