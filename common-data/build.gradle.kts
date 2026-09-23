// :common-data — shared, product-neutral Android data sources (MONOREPO_MERGE_SPEC
// §4.1). Android-library consumed by both apps. Starts with the home-info calendar/
// alarm reader (TimeBasedEventsRepositoryImpl, HIE Phase B); further shared
// system-API repositories migrate here as they are extracted.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.github.reygnn.launcher.common.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    testOptions {
        unitTests {
            // The calendar test runs under Robolectric (real Uri/ContentUris/
            // CalendarContract); the alarm test mocks Context. Mirrors :data.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    // NOTE: org.json is provided by the Android platform (android.jar) at compile
    // AND runtime — do NOT add libs.json as an implementation dep (bundling a second
    // copy breaks R8; see kolibri :data). WallpaperRepositoryImpl uses org.json.*.

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    // Shared installed-apps state-holder contract + MainDispatcherRuleBase live in
    // :core testFixtures; the impl-side contract test (InstalledAppsStateRepository-
    // ImplContractTest) and LauncherAppsEnumeratorTest consume them.
    testImplementation(testFixtures(project(":core")))
}
