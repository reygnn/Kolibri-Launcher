/*
 * ═══════════════════════════════════════════════════════════════════════════
 * :domain — domain layer (repositories, use cases, models, core utilities)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Android Library module hosting the layer that is conceptually free of UI
 * and data-source concerns. The Brocken-C sweeps removed every Android
 * import from `src/main/`, and the §11-Followup attempted to switch the
 * module type to pure-Kotlin (`kotlin("jvm")`). The switch is blocked by
 * Timber 5.x being distributed as `.aar` only — a JVM module cannot
 * consume that artifact, and TimberWrapper depends on Timber's static
 * API. Switching the module-type therefore requires a separate refactor
 * that abstracts TimberWrapper's logging backend behind a runtime-
 * injected delegate (so :domain can compile without the Timber AAR on
 * its classpath, and :app wires the Timber implementation at startup).
 * Left as a follow-up — see TODO.md §11.
 *
 * Source remains Android-free: `grep -rn "^import android\|^import androidx" src/main/`
 * yields nothing, which is the bigger architectural win regardless of
 * module-type.
 *
 * Hilt: this library declares modules (e.g. DispatcherModule). The
 * `kapt(libs.hilt.compiler)` invocation here generates the per-module
 * aggregating class that :app's @HiltAndroidApp picks up.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
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
