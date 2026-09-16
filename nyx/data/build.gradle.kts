/*
 * :data — repository impls + DataStore + Hilt RepositoryModule/DataStoreModule
 * + the hand-rolled IconLoader (IconRasterizer, disk cache, LruBudget wiring)
 * + PackageUpdateReceiver glue. Depends on :domain, never on :app.
 *
 * The @Serializable HomeLayoutDto + mappers live HERE (CLAUDE.md rule 22), so
 * the :domain data classes stay annotation-free.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.reygnn.nyx_launcher.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    @Suppress("UnstableApiUsage")
    testFixtures { enable = true }

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

    testOptions {
        unitTests {
            // Robolectric for the IconRasterizer / disk-cache / DataStore-blob
            // tests (ICON_LOADER_SPEC §9.2). Mirror of the :app flags.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":nyx:domain"))
    // Shared home-info calendar/alarm reader (HIE Phase C); bound in RepositoryModule.
    implementation(project(":common-data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // NOTE: org.json (libs.json) intentionally NOT a runtime dep — Android's
    // platform classpath already ships org.json.*; bundling the Maven artifact
    // causes the R8/NoSuchFieldError footgun (see Kolibri-Launcher :data). It
    // stays in testImplementation for pure-JVM tests only.
    // NOTE: NO Coil/Glide — the icon cache is hand-rolled (CLAUDE.md rule 20).

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testFixturesImplementation(libs.androidx.datastore.preferences)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

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

    // Shared fixtures from :domain (MainDispatcherRule, Fakes, Contracts).
    testImplementation(testFixtures(project(":nyx:domain")))
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRuleBase
}
