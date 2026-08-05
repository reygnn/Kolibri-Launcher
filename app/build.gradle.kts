import java.io.FileInputStream
import java.util.Properties

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPORTANT FOR AI ASSISTANTS (Gemini, Claude, etc.):
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Versions now live in `gradle/libs.versions.toml` — NOT here in
 * build.gradle.kts. Pinning markers (DO NOT UPGRADE / DO NOT DOWNGRADE /
 * DO NOT CHANGE / OK to upgrade) sit next to the versions in the Catalog.
 * ⚠️ Ignoring these markers causes build failures! ⚠️
 *
 * minSdk=36 (Android 16); compileSdk=targetSdk=37 (Android 17, lifted
 * 2026-07-18 for core-ktx 1.19.0 — see gradle/libs.versions.toml).
 * These values are DELIBERATE — do NOT change without explicit instruction!
 * ═══════════════════════════════════════════════════════════════════════════
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    id("jacoco")
    alias(libs.plugins.kotlin.serialization)
}

// Loads the sensitive data from the keystore.properties file.
// This file should sit in the project root and be listed in .gitignore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

// Only load if the file exists (CI builds may not have it).
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.github.reygnn.kolibri_launcher"
    compileSdk = 37 // DO NOT CHANGE !!!

    val secretsPropertiesFile = rootProject.file("secrets.properties")
    val secretsProperties = Properties()
    if (secretsPropertiesFile.exists()) {
        secretsProperties.load(FileInputStream(secretsPropertiesFile))
    }

    defaultConfig {
        applicationId = "com.github.reygnn.kolibri_launcher"
        minSdk = 36 // DO NOT CHANGE !!!
        targetSdk = 37 // DO NOT CHANGE !!!
        versionCode = 167
        versionName = "0.99.147"

        // BuildConfig-Felder erstellen
        buildConfigField(
            "String",
            "ACRA_URL",
            "\"${secretsProperties.getProperty("acra.url", "")}\""
        )
        buildConfigField(
            "String",
            "ACRA_LOGIN",
            "\"${secretsProperties.getProperty("acra.login", "")}\""
        )
        buildConfigField(
            "String",
            "ACRA_PASSWORD",
            "\"${secretsProperties.getProperty("acra.password", "")}\""
        )

        testInstrumentationRunner = "com.github.reygnn.kolibri_launcher.HiltTestRunner"
        testInstrumentationRunnerArguments["numFlakyTestAttempts"] = "3"
        // Wipes app data (DataStore, SharedPreferences, filesDir, runtime
        // permissions) BETWEEN tests via androidx.test.orchestrator. The
        // orchestrator runs `pm clear` AFTER the instrumentation has
        // finished and BEFORE it starts the next test, so unlike a
        // @get:Rule that calls `pm clear` from inside the test process
        // (which SIGKILLs the runner itself), this is safe. Requires
        // `execution = "ANDROIDX_TEST_ORCHESTRATOR"` in testOptions.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    sourceSets {
        getByName("androidTest").resources.directories.add("src/androidTest/resources")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            // Nur setzen wenn keystore.properties existiert
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
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

    // Configures the JDK used by the Java toolchain. AGP picks this up for
    // most tasks; the `tasks.withType<JavaCompile>` block below covers the
    // hiltJavaCompileDebug task that doesn't honor it on its own.
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
            }
        }

        unitTests.all {
            it.configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }

    // Fail the build on real XML/resource breakage. The 93 existing warnings
    // (mostly PluralsCandidate, GradleDependency, LogNotTimber in paranoid
    // KolibriLauncherApp fallbacks) are intentionally left as warnings — only
    // genuine localization/resource bugs should block.
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false

        error += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            "MissingDefaultResource",
        )
    }
}

// Force the JDK 21 toolchain on every JavaCompile task. AGP/kapt-generated
// tasks like `hiltJavaCompileDebug` don't pick up the project-level toolchain
// on their own and would otherwise fall back to the system JDK with
// "invalid source release: 21".
tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

dependencies {
    // Project modules
    implementation(project(":domain"))
    implementation(project(":data"))


    // Shared test fixtures from :domain (TimberRule, MainDispatcherRule,
    // Fake*Repository, Contract abstract classes). See `java-test-fixtures`
    // block in domain/build.gradle.kts. Brocken B.
    testImplementation(testFixtures(project(":domain")))

    // Shared test fixtures from :data (FakeDataStore). Unblocked
    // 2026-05-03 by setting `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties (an undocumented AGP flag, available since
    // 8.5). See `data/TESTFIXTURES_KOTLIN_INVESTIGATION.md` for the
    // history. The flag enables the otherwise-missing
    // `compileDebugTestFixturesKotlin` task for android-library modules.
    testImplementation(testFixtures(project(":data")))

    // UI & Material  (MUST be loaded first or use resolutionStrategy below!)
    implementation(libs.material)  // MUSS VOR androidx.appcompat:appcompat !!!

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)  // Heads up: drags in older 'MaterialYou'.
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)

    // Lifecycle & Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Data & Async
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Utilities
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)  // 1.10.0 requires Kotlin 2.3.0.
    testImplementation(libs.robolectric)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.browser)
    ksp(libs.hilt.compiler)

    implementation(libs.acra.core)
    implementation(libs.acra.http)

    debugImplementation(libs.leakcanary.android)

    // --- LOCAL UNIT TESTS (run on the host JVM) ---
    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.json)

    // Hilt for unit tests.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // MockK for unit tests.
    testImplementation(libs.mockk)

    // AndroidX Test (for Robolectric-based Activity/Fragment tests).
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)


    // --- INSTRUMENTED TESTS (run on emulator / device) ---
    androidTestUtil(libs.androidx.test.orchestrator)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.truth)

    androidTestImplementation(libs.androidx.arch.core.testing)
    debugImplementation(libs.androidx.fragment.testing)
    androidTestImplementation(libs.androidx.test.espresso.contrib)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.espresso.web)
    debugImplementation(libs.androidx.test.espresso.idling.resource)

    // Hilt for instrumented tests.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Turbine — needed for the SharedFlow subscribe-before-trigger pattern
    // in receiver tests (see TESTING_CONVENTIONS "MUTABLESHAREDFLOW IN
    // CONSTRUCTOR" — the subscriber-before-emit guarantee works on
    // instrumented hardware just as it does on the JVM, only without a
    // TestDispatcher).
    androidTestImplementation(libs.turbine)
}

configurations.all {
    resolutionStrategy {
        // DO NOT REMOVE !!!
        // Forces `material` even when appcompat drags in an older version.
        // Warning: without this force, dependency conflicts WILL appear when
        // the dependency declaration order is wrong.
        force("com.google.android.material:material:${libs.versions.material.get()}")

        force("androidx.test:runner:${libs.versions.androidxTest.get()}")
        force("androidx.test:monitor:${libs.versions.androidxTest.get()}")
        // Espresso 3.7.0's RootViewPicker / InstrumentationActivityInvoker
        // calls into desugared ActivityInvoker default methods
        // (`androidx.test.internal.platform.app.ActivityInvoker$-CC`) which
        // only exist in androidx.test:core ≥ 1.6. Without this force,
        // Gradle's consistent-resolution between debugRuntimeClasspath and
        // debugAndroidTestRuntimeClasspath downgrades core to {strictly
        // 1.5.0} (because production runtime doesn't pull a higher version
        // transitively), so any androidTest call into `inRoot(isDialog())`,
        // `scenario.onActivity { }`, or `withText(...)` matchers fails with
        // NoClassDefFoundError before the matcher runs. Symptom found
        // 2026-05-06 while bringing up CustomNamesActivityRenameTest +
        // AppDrawerFragmentSearchTest.
        force("androidx.test:core:${libs.versions.androidxTest.get()}")
    }
}

// Project-convention linter — checks the CLAUDE.md rules whose drift was
// the biggest defect class in the post-audit-Sweep-Session 2026-05-03.
// Source script: `tools/check-conventions.sh`. Add new checks there, not here.
//
// Currently checks:
//   - Rule 9   — bare `Timber.e(` outside the documented crash-infra files
//   - Rule 12  — `Timber.Forest.*` (use the short form)
//   - Naming   — `*Manager` classes inside `data/` (use `*RepositoryImpl`)
//
// Run via `./gradlew checkConventions` or invoke the script directly.
tasks.register<Exec>("checkConventions") {
    group = "verification"
    description = "Runs the project-convention linter (CLAUDE.md rules)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/check-conventions.sh")
}

// Rule 13 — git-diff-aware German-comment linter. Flags `+` lines (added
// or modified relative to the comparison base, default `origin/main`) that
// look like comments containing German prose. Pre-existing German lines
// are intentionally not swept per Rule 13. Source: tools/check-rule13-german-comments.sh.
//
// Run via `./gradlew checkRule13` or invoke the script directly. Override
// the comparison base with the CHECK_BASE env var.
tasks.register<Exec>("checkRule13") {
    group = "verification"
    description = "Runs the Rule 13 (German comments) linter against the git diff."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/check-rule13-german-comments.sh")
}

// Discovery aid for the cancellation-rethrow whitelist. The linter (cancel_files
// positive list) is blind to non-listed files by design; this sweeps every
// non-whitelisted main source for the broad-catch shape and ranks hits by
// coroutine density. Report-only — it never fails the build. Run after a
// refactor that gives a file coroutine work (CLAUDE.md Rule 11, "Discovery").
// NOT wired into `checkConventions`/CI — a discovery tool, not a gate.
tasks.register<Exec>("scanCancelCandidates") {
    group = "verification"
    description = "Lists non-whitelisted files whose broad catches may belong in cancel_files (report-only)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/scan-cancel-candidates.sh")
}

// Code coverage configuration via JaCoCo.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/databinding/**",
        "**/di/**",
        "**/*_Factory.*",
        "**/*_MembersInjector.*",
        "**/*Module.*",
        "**/*Module$*.*",
        "**/*_Impl.*",
        "**/*_HiltComponents*.*"
    )

    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec")
    })
}


// app/build.gradle.kts

tasks.register("uploadProguardMapping") {
    group = "acra"
    description = "Upload ProGuard mapping using upload_mapping.sh script"

    doLast {
        val packageName = android.defaultConfig.applicationId
        val versionCode = android.defaultConfig.versionCode
        val mappingFile = file("build/outputs/mapping/release/mapping.txt")

        if (!mappingFile.exists()) {
            println("⚠️  Mapping file not found: ${mappingFile.absolutePath}")
            println("This is normal if minifyEnabled = false")
            return@doLast
        }

        val scriptPath = "$projectDir/acra-scripts/upload_mapping.sh"
        val scriptFile = file(scriptPath)

        if (!scriptFile.exists()) {
            println("⚠️  Script not found: $scriptPath")
            println("Please add upload_mapping.sh to your app directory")
            return@doLast
        }

        println("📤 Uploading ProGuard mapping via script...")

        // Run the script.
        val process = ProcessBuilder(
            "bash",
            scriptPath,
            mappingFile.absolutePath,
            packageName!!,
            versionCode.toString()
        )
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val exitCode = process.waitFor()

        if (exitCode == 0) {
            println("✅ Upload completed successfully!")
        } else {
            throw GradleException("Mapping upload failed with exit code $exitCode")
        }
    }
}

// Run automatically after the release build for APK and bundle.
tasks.configureEach {
    if (name in listOf("assembleRelease", "bundleRelease")) {
        finalizedBy("uploadProguardMapping")
    }
}
