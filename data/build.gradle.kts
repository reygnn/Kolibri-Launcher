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
    implementation(libs.json)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}

kapt {
    correctErrorTypes = true
}
