package com.github.reygnn.kolibri_launcher.crashreporting.consent

/**
 * Runs [CrashReportConsentRepositoryContract] against the unit-test fake
 * [FakeCrashReportConsentRepository].
 */
class FakeCrashReportConsentRepositoryContractTest : CrashReportConsentRepositoryContract() {

    override fun createRepository(): CrashReportConsentRepository = FakeCrashReportConsentRepository()
}
