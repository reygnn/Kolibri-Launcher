/*
 * ═══════════════════════════════════════════════════════════════════════════
 * :data — repository implementations + DataStore + Hilt repository module
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Android Library module hosting the data sources: every `XyzRepositoryImpl`,
 * the `RepositoryModule` Hilt bindings, the `DataStoreModule`, plus the
 * non-Hilt `PackageUpdateReceiver` glue that bridges into the app via
 * `AppUpdateSignal`.
 *
 * Depends on `:domain` for repository contracts and models. No dependency on
 * `:app` — the data→ui cycle was eliminated in branches
 * `refactor/cycle-elimination-data-to-ui` (commits 5e0d9b3, 9f9b3d2,
 * 695dd52) before this split landed.
 *
 * Hilt: declares modules (RepositoryModule, DataStoreModule, AppUpdateModule).
 * The `kapt(libs.hilt.compiler)` invocation here generates the per-module
 * aggregating class that :app's @HiltAndroidApp picks up at link time.
 *
 * BuildConfig: enabled here so `BuildConfig.DEBUG` resolves in
 * FavoritesRepositoryImpl. The app's `versionName` is NOT duplicated as a
 * `buildConfigField` — it's injected from :app via Hilt
 * (`@Named("appVersionName")`) so there's a single source of truth.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }

    buildFeatures {
        buildConfig = true
    }

    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources on the test classpath; the
            // BackupRepositoryImpl* / WallpaperRepositoryImpl* / DataMigrationManager
            // tests run with @RunWith(RobolectricTestRunner::class). Mirrors
            // the same flags in :app/build.gradle.kts.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // NOTE: org.json:json (libs.json) is intentionally NOT a runtime
    // implementation dependency. Android's platform classpath already
    // ships org.json.* (JSONObject, JSONArray, JSONStringer, …) — adding
    // the Maven artifact bundles a duplicate `org.json.JSONStringer` into
    // the APK that R8 minifies (renaming internal fields to `a`, `b`, …).
    // At runtime ART loads the platform JSONStringer from the boot
    // classpath, so any bytecode that R8 compiled against the bundled
    // copy (notably ACRA's `StringFormat$JSON.toFormattedString`) hits
    // NoSuchFieldError on `JSONStringer.a`. Symptom: `AcraTree: Failed
    // to report exception to ACRA / NoSuchFieldError`. Diagnosed
    // 2026-05-04 after the §9.2 module split silently brought this dep
    // into runtime when the Repository-Impls moved here. The Maven
    // artifact stays in `testImplementation` below where pure-JVM tests
    // need it (no Android platform classes available there).

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // testFixtures: shared test doubles consumed by :app's and :data's
    // own test source sets. Currently exposes FakeDataStore. The
    // androidx.datastore + coroutines deps mirror the production usage
    // because FakeDataStore implements DataStore<Preferences> directly.
    //
    // The Kotlin compile task for testFixtures requires the
    // `android.experimental.enableTestFixturesKotlinSupport=true` flag
    // in gradle.properties — see data/TESTFIXTURES_KOTLIN_INVESTIGATION.md.
    testFixturesImplementation(libs.androidx.datastore.preferences)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    // Test dependencies — Repository-Impl tests in :data/src/test/.
    // Brocken B (Test-Isolation per Modul) — see TODO §13. The set
    // mirrors :domain/build.gradle.kts's testImplementation block plus
    // Robolectric, since some Repository-Impls touch Android URI/Uri-
    // ContentResolver/InputStream APIs (BackupRepositoryImpl,
    // WallpaperRepositoryImpl, UsageExportRepositoryImpl IO paths,
    // DataMigrationManager).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)

    // Shared test fixtures from :domain (TimberRule, MainDispatcherRule,
    // Fake*Repository, Contract abstract classes).
    testImplementation(testFixtures(project(":domain")))
}

kapt {
    correctErrorTypes = true
}
