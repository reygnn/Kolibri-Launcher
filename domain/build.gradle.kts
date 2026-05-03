/*
 * ═══════════════════════════════════════════════════════════════════════════
 * :domain — domain layer (repositories, use cases, models, core utilities)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Android Library module hosting the layer that is conceptually free of UI
 * and data-source concerns. The original §9.2 split kept it as an Android
 * Library because three sites carried Android imports
 * (`core/TextColorCalculator`, `core/AppConstants`'s DataStore keys,
 * `domain/model/AppInfo`'s `@Parcelize`); the Brocken-C sweeps removed all
 * three, so `src/main/` is now Android-free in source. Switching the module
 * type to pure Kotlin is a separate build-config refactor — tracked in
 * TODO.md.
 *
 * Hilt: this library declares modules (e.g. DispatcherModule). The
 * `kapt(libs.hilt.compiler)` invocation here generates the per-module
 * aggregating class that :app's @HiltAndroidApp picks up.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    id("kotlin-parcelize")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher.domain"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}

kapt {
    correctErrorTypes = true
}
