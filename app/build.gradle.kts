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
        versionCode = 192
        versionName = "0.99.172"

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
//   - Rule 9    — bare `Timber.e(` outside the documented crash-infra files
//   - Rule 11   — broad-catch annotation + cancellation-rethrow discipline
//   - Rule 12   — `Timber.Forest.*` (use the short form)
//   - Naming    — `*Manager` classes inside `data/` (use `*RepositoryImpl`)
//   - Toast     — bare `Toast.makeText(` outside `ToastSafe.kt`
//   - Flow.catch — logging arm without a CancellationException rethrow
//   - SharedFlow — unbuffered `MutableSharedFlow(...)` that drops emissions
//   - Purge     — declared preference key not wiped by `purgeRepository()`
//   - ActivityResult — registerForActivityResult() in a lifecycle method
//   - Adapter   — Fragment RecyclerView adapter not nulled in onDestroyView
//   - ExceptionBreadth — bare catch(Exception) at a whitelisted OOM boundary
//   - StaleReplay — hot-flow `.first()` point-read outside the allowlist
//                   (AUDIT-13; enforced via the `checkStaleReplayRead` task
//                   wired below as a dependsOn, so `./gradlew checkConventions`
//                   — the CI gate — fails on it too)
//
// Run via `./gradlew checkConventions` or invoke the script directly.
tasks.register<Exec>("checkConventions") {
    group = "verification"
    description = "Runs the project-convention linter (CLAUDE.md rules)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/check-conventions.sh")
    // AUDIT-13 stale-replay gate rides along with the main convention gate so the
    // single CI step (`./gradlew checkConventions`) enforces it. It is a separate
    // task (own script/awk/allowlist) rather than folded into check-conventions.sh,
    // and stays independently runnable via `./gradlew checkStaleReplayRead`.
    dependsOn("checkStaleReplayRead")
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

// AUDIT-13 stale-replay point-read gate — a `stale_files` positive list, exactly
// like cancel_files/oom_files. Verifies ONLY the whitelisted files that
// legitimately point-read a hot-shared replay flow (favorites/order/fab): every
// such `.first()`/`.firstOrNull()` in a listed file must carry a `stale-replay
// ok` marker (±5 lines) or be converted to getXSnapshot(); an unmarked one fails.
// The class of bug that caused the swipe regression and the two favorites UI
// consumers. Discovery of a NEW read in a NON-listed file is the report-only
// scanStaleReplayRead below — the gate is blind to non-listed files by design.
// Detector: tools/check-stale-replay-read.awk; regression test:
// tools/check-stale-replay-read-test.sh. Set STALE_REPLAY_REPORT_ONLY=1 for
// discovery mode. Runs standalone via `./gradlew checkStaleReplayRead`, and
// automatically as a `dependsOn` of `checkConventions` (the CI gate above).
tasks.register<Exec>("checkStaleReplayRead") {
    group = "verification"
    description = "Verifies hot-flow point-reads in the stale_files whitelist carry a marker (AUDIT-13)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/check-stale-replay-read.sh")
}

// Discovery half of the stale-replay axis, mirroring scanCancelCandidates /
// scanOomCandidates: the stale_files positive list is blind to non-listed files,
// so a new `.first()`/`.firstOrNull()` on a favorites/order/fab flow added to a
// non-whitelisted file is invisible to the gate. This sweeps every non-listed
// main source with the SAME awk and ranks by hit count. Report-only — never
// fails the build. Run after adding such a read, especially from a non-Home
// context. Run via `./gradlew scanStaleReplayRead`.
tasks.register<Exec>("scanStaleReplayRead") {
    group = "verification"
    description = "Lists non-whitelisted files with an unmarked hot-flow point-read (report-only)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/scan-stale-replay-candidates.sh")
}

// Same discovery aid for the OTHER breadth axis: the oom_files positive list is
// blind to non-listed files, so a new `catch (e: Exception)` around a bitmap /
// inflate / JSON / ZIP boundary is invisible. Ranks hits by allocation density.
// Report-only — it never fails the build, and is NOT wired into
// `checkConventions`/CI: most Exception catches in the tree are correct.
tasks.register<Exec>("scanOomCandidates") {
    group = "verification"
    description = "Lists non-whitelisted files whose Exception catches may belong in oom_files (report-only)."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/scan-oom-candidates.sh")
}

// Code coverage configuration via JaCoCo — AGGREGATES ALL THREE MODULES.
//
// This task used to cover :app alone: the jacoco plugin was applied only here,
// and the source/class/exec wiring pointed exclusively at this module. So the
// reported number described 1208 of the suite's 2432 tests, while :data (859)
// and :domain (365) — the repository implementations and the entire use-case
// layer — were absent from it. Not under-reported: absent, which is worse,
// because an uncovered domain class looked identical to a nonexistent one.
//
// :data and :domain now apply the jacoco plugin too (see their build scripts)
// and this report unions all three. Note the task-name asymmetry: the Android
// modules produce `testDebugUnitTest.exec`, the pure-JVM :domain produces
// `test.exec` — the same variant asymmetry that had kept :domain out of the CI
// test step.
//
// Cross-project wiring uses absolute task paths and `project(...).projectDir`
// rather than task references, so it needs no `evaluationDependsOn` and cannot
// break on project-evaluation order.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest", ":data:testDebugUnitTest", ":domain:test")

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

    val dataDir = project(":data").projectDir
    val domainDir = project(":domain").projectDir

    // Class output locations. The Android modules land under AGP 9's built-in
    // Kotlin compiler path; the pure-JVM :domain uses the standard Gradle one.
    //
    // The `tmp/kotlin-classes/debug` path this used to name is the layout of
    // the EXTERNAL org.jetbrains.kotlin.android plugin, which this project
    // dropped for AGP built-in Kotlin. Nothing failed at the time: JacocoReport
    // over a nonexistent directory produces an empty report and exits 0, so the
    // coverage number silently became meaningless instead of going red. Hence
    // the guard below — a path that stops resolving must fail loudly.
    val classDirPaths = listOf(
        "${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        "$dataDir/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        "$domainDir/build/classes/kotlin/main",
    )
    val classTrees = classDirPaths.map { fileTree(it).matching { exclude(fileFilter) } }

    // Fail loudly if a class output path stops resolving (AGP layout change,
    // module rename). Without this the report just shrinks in silence, which is
    // exactly how the :app half went uncovered unnoticed.
    doFirst {
        val empty = classDirPaths.filter { fileTree(it).files.none { f -> f.extension == "class" } }
        require(empty.isEmpty()) {
            "jacocoTestReport: no .class files under ${empty.joinToString()} — " +
                "the class output layout changed; fix classDirPaths in app/build.gradle.kts " +
                "instead of shipping an empty coverage report."
        }
    }

    val sourceDirs = listOf(
        "${project.projectDir}/src/main/java",
        "$dataDir/src/main/java",
        "$domainDir/src/main/java",
    )

    // testFixtures/ is deliberately NOT a source dir: the contract abstracts
    // living there are test scaffolding, not production code under test.
    sourceDirectories.setFrom(files(sourceDirs))
    classDirectories.setFrom(files(classTrees))
    executionData.setFrom(
        files(
            "${layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec",
            "$dataDir/build/jacoco/testDebugUnitTest.exec",
            "$domainDir/build/jacoco/test.exec",
        )
    )
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
