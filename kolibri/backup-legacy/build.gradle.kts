// :kolibri:backup-legacy — reads Kolibri's pre-E5a backup archives (backup.json + wallpapers/)
// for a transition period (SPEC_NYX_REWRITE E5a, "legacy module with sunset"). It never
// writes. It up-converts an old archive into the current `kolibri.backup` section plus staged
// blobs, so an old backup takes the same import path — and gets the same semantics (E1, E2,
// B11, B13, B14) — as a new one.
//
// Sunset: 3 months after the first release of 2a; the date is in SUNSET next to this file and
// checkConventions warns from that day. Removing the module = drop it from settings.gradle.kts,
// from :kolibri:app's dependencies and from tools/conventions/kolibri.conf. The engine then
// reports old archives as "older version, no longer supported" on its own.
plugins {
    id("launcher.android.library") // SDK, Java/Kotlin 21, unit-test setup (build-logic)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher.backup.legacy"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kolibri:domain"))
    implementation(project(":kolibri:data"))
    implementation(project(":feature-backup"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit) // assertFailsWith (A12 exception)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json) // real org.json for BackupSerializer's strict recovery
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRule
}
