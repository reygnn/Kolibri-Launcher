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
 * 2026-07-18 for core-ktx 1.19.0 — see gradle/libs.versions.toml). They live ONCE
 * in build-logic (LauncherBuild) for every module of both apps, not here.
 * These values are DELIBERATE — do NOT change without explicit instruction!
 * ═══════════════════════════════════════════════════════════════════════════
 */

plugins {
    id("launcher.android.application") // SDKs, Java/Kotlin 21, unit tests, BuildConfig (build-logic)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    // Consumes the :baselineprofile producer and bakes its generated profile
    // into the release build. Adds internal nonMinifiedRelease/benchmarkRelease
    // generation variants (used only by :app:generateBaselineProfile).
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher"

    defaultConfig {
        applicationId = "com.github.reygnn.kolibri_launcher"
        versionCode = 241
        versionName = "1.0.0-rc3"

        testInstrumentationRunner = "com.github.reygnn.kolibri_launcher.HiltTestRunner"
        testInstrumentationRunnerArguments["numFlakyTestAttempts"] = "1"
    }

    sourceSets {
        getByName("androidTest").resources.directories.add("src/androidTest/resources")
    }

    // A personal-build release toggle: true if its own `-P<name>` (bare or
    // `=true`) OR the `-PdailyDriver` master flag is passed. Absence = off, so a
    // plain `bundleRelease` / `assembleRelease` can only ever produce a
    // public-safe build, never leak a personal-only surface.
    fun personalProperty(name: String): Boolean =
        providers.gradleProperty(name).map { it.isBlank() || it.toBoolean() }.getOrElse(false)

    // `-PdailyDriver` is the personal-build master flag: it turns on every
    // personal-only release toggle at once (currently SHOW_DEV_COMMANDS +
    // SHOW_CACHE_TOASTS). The individual flags below still work standalone —
    // each is the OR of its own property and dailyDriver.
    val dailyDriverRelease = personalProperty("dailyDriver")

    // Public-safe default: the three ACRA test-trigger dev commands
    // (throw / silent-error / warn) are compiled out of a plain
    // `bundleRelease` / `assembleRelease` so the GitHub-shipped AAB never
    // exposes them. A personal build re-enables them with `-PdevCommands`
    // (bare, or `=true`) or `-PdailyDriver`. Read via BuildConfig.SHOW_DEV_COMMANDS
    // in SettingsFragment. pipeline_status is NOT gated (read-only probe).
    val devCommandsInRelease = dailyDriverRelease || personalProperty("devCommands")

    // Public-safe default: the three wallpaper cache-diagnostic toasts
    // (single-layer hit / single-layer fill / composite fill in WallpaperDelegate)
    // are compiled out of a plain release so the GitHub AAB never toasts on every
    // cache operation. A personal build re-enables them with `-PcacheToasts`
    // (bare, or `=true`) or `-PdailyDriver`. Read via BuildConfig.SHOW_CACHE_TOASTS.
    val cacheToastsInRelease = dailyDriverRelease || personalProperty("cacheToasts")

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            // Dev commands + cache-diagnostic toasts always present in a debug build.
            buildConfigField("boolean", "SHOW_DEV_COMMANDS", "true")
            buildConfigField("boolean", "SHOW_CACHE_TOASTS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            buildConfigField("boolean", "SHOW_DEV_COMMANDS", devCommandsInRelease.toString())
            buildConfigField("boolean", "SHOW_CACHE_TOASTS", cacheToastsInRelease.toString())
            ndk {
                debugSymbolLevel = "FULL"
            }
            // No Gradle signingConfig: per the family convention Gradle emits an
            // UNSIGNED release AAB/APK and install-aab.sh signs it with the shared
            // family key at install time. Don't reintroduce a signingConfig here.
        }
        // NOTE: the :macrobenchmark module measures the `release` build type
        // directly (its test variant matches via matchingFallbacks = ["release"]).
        // No dedicated benchmark build type is needed here: release is already
        // non-debuggable + profileable (<profileable> in the manifest). The
        // on-device benchmark variant supplies its own debug signingConfig
        // (see macrobenchmark/build.gradle.kts), so the unsigned app release
        // still installs for the benchmark run.
    }

    androidResources {
        // Keep only the locales the app itself ships (values/ + values-de/).
        // Without this filter, androidx/Material contribute their string
        // translations in ~85 locales to resources.arsc, which is stored
        // uncompressed (mandatory for targetSdk >= 30) and made up ~30 % of
        // the release APK. See tmp/APK_SIZE_FINDINGS.md (2026-09-02).
        localeFilters += listOf("en", "de")
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":common-ui"))
    implementation(project(":feature-crashreporting"))
    implementation(project(":feature-backup")) // shared backup engine (SPEC_NYX_REWRITE 2a)
    // Reads pre-E5a backups until its sunset (kolibri/backup-legacy/SUNSET); remove with the module.
    implementation(project(":kolibri:backup-legacy"))
    // Project modules
    implementation(project(":kolibri:domain"))
    implementation(project(":kolibri:data"))
    // Shared wallpaper persistence (WallpaperFileManager/RepositoryImpl) is injected
    // directly into LauncherViewModel/WallpaperDelegate, so :app needs :common-data
    // on its own classpath (kolibri:data depends on it via non-transitive implementation).
    implementation(project(":common-data"))

    // Shared test fixtures from :domain (TimberRule, MainDispatcherRule,
    // Fake*Repository, Contract abstract classes). See `java-test-fixtures`
    // block in domain/build.gradle.kts. Brocken B.
    testImplementation(testFixtures(project(":kolibri:domain")))
    // Shared :core test fixtures (the one MainDispatcherRule, recordEmissions).
    // androidTest deliberately omitted: its only MainDispatcherRule mention is a
    // comment in INSTRUMENTED_TESTING_NOTES.kt (the rule deadlocks a real Main).
    testImplementation(testFixtures(project(":core")))

    // Shared test fixtures from :data (FakeDataStore). Unblocked
    // 2026-05-03 by setting `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties (an undocumented AGP flag, available since
    // 8.5). See `data/TESTFIXTURES_KOTLIN_INVESTIGATION.md` for the
    // history. The flag enables the otherwise-missing
    // `compileDebugTestFixturesKotlin` task for android-library modules.
    testImplementation(testFixtures(project(":kolibri:data")))

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

    // Baseline Profile: installs the baked profile at first run (dexopt).
    // REQUIRED for the shipped profile to have any runtime effect.
    implementation(libs.androidx.profileinstaller)
    // Wires :baselineprofile as the producer of :app's release baseline profile.
    baselineProfile(project(":kolibri:baselineprofile"))

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

    // Shared TAPL-lite test-support fassade (BasePage/awaitUntil/page objects).
    // src/main of the library, pulled in ONLY on the androidTest classpath.
    androidTestImplementation(project(":common-testing-android"))

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
// Source script: `../tools/check-conventions.sh --app kolibri` (one orchestrator for both
// apps, SPEC_NYX_REWRITE A3); kolibri's lists/decisions are data in
// `../tools/conventions/kolibri.conf`. Add new checks there, not here.
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
    workingDir = projectDir.parentFile // = kolibri/ (scripts live in kolibri/tools; monorepo rootDir is the repo root)
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
    workingDir = projectDir.parentFile // = kolibri/ (scripts live in kolibri/tools; monorepo rootDir is the repo root)
    commandLine = listOf("bash", "../tools/check-rule13-german-comments.sh")
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
    workingDir = projectDir.parentFile // = kolibri/ (scripts live in kolibri/tools; monorepo rootDir is the repo root)
    commandLine = listOf("bash", "../tools/check-stale-replay-read.sh", "--app", "kolibri")
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
    workingDir = projectDir.parentFile // = kolibri/ (scripts live in kolibri/tools; monorepo rootDir is the repo root)
    commandLine = listOf("bash", "../tools/scan-stale-replay-candidates.sh", "--app", "kolibri")
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
