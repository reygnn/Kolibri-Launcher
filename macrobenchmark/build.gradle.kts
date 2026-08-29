// =============================================================================
// :macrobenchmark — on-device launch-latency gate
// =============================================================================
//
// A `com.android.test` module (NOT shipped) that measures Kolibri's app-launch
// hot path on a REAL device via Macrobenchmark + Perfetto trace sections. It
// exists to LOCK the sub-frame property established by the PERF-RESULTS
// measurements: the ViewModel+Channel launch hop is sub-millisecond and must
// stay far under one frame. A regression that pushes it toward a full frame
// (e.g. a suspend call sneaking into the dispatch) trips the threshold gate.
//
// Local device only — matching this project's "androidTest = real device =
// local" posture (CLAUDE.md Rule 10). It is deliberately NOT wired into the
// device-free GitHub-Actions build job; perf numbers on a hosted emulator are
// noise. CI only compile-checks the module so it cannot rot (see android.yml).
//
// Run:  ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
//       ./gradlew :macrobenchmark:verifyLaunchBenchmark   (threshold gate)
// (needs a connected, unlocked device with at least one home favorite; the
// target app is auto-built as the app's `benchmark` build type).

plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher.macrobenchmark"
    compileSdk = 37 // matches :app — DO NOT CHANGE independently

    defaultConfig {
        minSdk = 36 // :app's minSdk; the target device is always >= this
        targetSdk = 37
        // Plain JUnit runner — the benchmark self-instruments the separate
        // target-app process; it needs neither Hilt nor the app's HiltTestRunner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildTypes {
        // The app has no dedicated `benchmark` build type; matchingFallbacks
        // routes THIS test variant to the app's `release` (non-debuggable +
        // profileable, family-key signed locally) = the true ship build. The
        // test APK itself is debug-signed/debuggable, which macrobenchmark
        // allows (self-instrumenting: target + test are separate APKs). The
        // name `benchmark` is what makes the run task `connectedBenchmark…`.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    // Macrobenchmark requires the target app to run in its own process; the test
    // process drives it. self-instrumenting makes this a two-APK setup.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the `benchmark` variant can run; disable debug/release so Gradle
        // does not build test variants with no matching target.
        it.enable = it.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}

// -----------------------------------------------------------------------------
// Threshold gate — parse the Macrobenchmark JSON and fail if p99 of the
// TAP->DISPATCH gap regresses past the frame-budget headroom.
// -----------------------------------------------------------------------------
tasks.register("verifyLaunchBenchmark") {
    group = "verification"
    description = "Fail if the launch-dispatch gap p99 exceeds the threshold " +
        "(run :macrobenchmark:connectedBenchmarkAndroidTest first)."

    // Worst-case threshold in MILLISECONDS, gated on the `maximum` of the
    // per-iteration TAP->DISPATCH gap. Measured max on the A17 5G is ~1.3 ms
    // (PERF-RESULTS.md; the Pixel 9a calibration p99 was ~0.85 ms); 4.0 ms is
    // generous, non-flaky headroom, still under half a 120 Hz frame (8.33 ms).
    // A structural regression (a frame added to the hop) lands well past this.
    // Device-calibrated —
    // re-tune if the reference device changes.
    val thresholdMs = 4.0
    val metricName = "launchDispatchGapMs" // emitted by LaunchDispatchGapMetric

    doLast {
        val outDir = layout.buildDirectory.get().asFile
        val jsons = outDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-benchmarkData.json") }
            .toList()
        if (jsons.isEmpty()) {
            throw GradleException(
                "No *-benchmarkData.json under ${outDir.path}. Run " +
                    ":macrobenchmark:connectedBenchmarkAndroidTest first.",
            )
        }
        // Dependency-free scan (no JSON lib on the test-module classpath): the
        // schema is flat and stable. Trace metrics are emitted with a suffix
        // (e.g. `launchDispatchGapMsMs` / `...Nanos`), so match the prefix and
        // read the block's `maximum` = worst-case gap across all iterations.
        var worst = -1.0
        var found = false
        jsons.forEach { f ->
            val text = f.readText()
            val block = Regex("\"${Regex.escape(metricName)}[^\"]*\"\\s*:\\s*\\{[^}]*}")
                .find(text)?.value
            if (block != null) {
                val max = Regex("\"maximum\"\\s*:\\s*([0-9.eE+-]+)").find(block)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                if (max != null) { found = true; if (max > worst) worst = max }
            }
        }
        if (!found) {
            throw GradleException(
                "Metric '$metricName' not found in benchmark JSON. Did the " +
                    "benchmark run and emit LaunchDispatchGapMetric?",
            )
        }
        logger.lifecycle(
            "Launch-dispatch gap, worst iteration: %.3f ms (threshold %.1f ms)".format(worst, thresholdMs),
        )
        if (worst > thresholdMs) {
            throw GradleException(
                "Launch-dispatch gap REGRESSED: worst %.3f ms > %.1f ms threshold. ".format(worst, thresholdMs) +
                    "The VM+Channel hop is no longer sub-frame — investigate before merging.",
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Startup gate — parse the Macrobenchmark JSON and fail if the baseline-profile
// cold-start TTID MEDIAN regresses past the headroom threshold. Sibling of
// verifyLaunchBenchmark; same dependency-free scan, same "measured value + a
// generous, non-flaky buffer that catches a STRUCTURAL regress, not noise" logic.
// -----------------------------------------------------------------------------
tasks.register("verifyStartupBenchmark") {
    group = "verification"
    description = "Fail if StartupBenchmark.startupBaselineProfile timeToInitialDisplayMs " +
        "median exceeds the threshold (run the StartupBenchmark first)."

    // Threshold in MILLISECONDS, gated on the MEDIAN of the ship-equivalent
    // (CompilationMode.Partial = baseline profile installed) COLD-start TTID.
    //
    // Measured on an A17 5G (SM-A176B, 3×20 iterations pooled): Partial median
    // 547 ms, p95 569 ms, CoV 3.1%; the profile-LESS run (CompilationMode.None)
    // sits at median 613 ms / p5 587 ms. The 5–95% bands are DISJOINT — the
    // profiled p95 (569 ms) still beats the unprofiled p5 (587 ms); only lone
    // outlier iterations touch. 580 ms is ~6% over the healthy median AND above its
    // p95, so per-run noise never trips it; yet it sits 33 ms below the profile-less
    // median (613 ms), so it FAILS exactly when the profile silently stops applying
    // (empty/absent baseline profile → Partial degrades toward None → TTID climbs to
    // ~613 ms) or a heavy init lands on the startup path. Median (not max): startup
    // is a whole-render measurement, so a single slow cold start is noise — a shifted
    // median is the structural signal. The old, faster reference device (retired
    // R5GL71YWEPH) had ZERO min/max overlap and the threshold cleared the Partial
    // MAX; this slower unit's arm tails touch on outliers, so the buffer is now over
    // p95, not max. Device-calibrated — re-tune if the reference device changes.
    //
    // OPERATIONAL: cold-start TTID is UNMEASURABLE while Kolibri is the default home
    // (its process never dies — "must not be running prior to cold start"), so the
    // data this gate reads is produced with ANOTHER launcher set as default. Local
    // device only; not wired into the device-free CI.
    val thresholdMs = 580.0
    val benchmarkName = "startupBaselineProfile"
    val metricName = "timeToInitialDisplayMs"

    // Baseline-profile sanity floor. The release baseline profile is a gitignored
    // local artifact with no automatic generation (CLAUDE.md § Versioning), so a
    // fresh checkout / cleaned build/ bakes only the library-default ART rules
    // (~2.7k lines) instead of the ~15k captured profile — which silently
    // inflates the very TTID this gate reads. 6k cleanly separates the two
    // (2.7k defaults « 6k « ~15k captured) with room for the app to shrink; used
    // ONLY to turn a degraded-profile failure into an actionable "regenerate"
    // message instead of a misleading "REGRESSED". Captured at configuration time
    // to avoid cross-project access in doLast.
    val minHealthyProfileLines = 6_000
    val appReleaseArtProfileDir =
        project(":app").layout.buildDirectory.dir("intermediates/merged_art_profile/release")

    doLast {
        val outDir = layout.buildDirectory.get().asFile
        val jsons = outDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-benchmarkData.json") }
            .toList()
        if (jsons.isEmpty()) {
            throw GradleException(
                "No *-benchmarkData.json under ${outDir.path}. Run " +
                    ":macrobenchmark:connectedBenchmarkAndroidTest (StartupBenchmark) first.",
            )
        }
        // Dependency-free scan (no JSON lib on the test-module classpath). Two benchmarks
        // emit `timeToInitialDisplayMs`, so anchor on the ship-equivalent one by name first,
        // THEN read that block's `median` (a flat field, before the `runs` array).
        var worstMedian = -1.0
        var found = false
        jsons.forEach { f ->
            val text = f.readText()
            val nameIdx = text.indexOf("\"$benchmarkName\"")
            if (nameIdx >= 0) {
                val block = text.substring(nameIdx)
                val median = Regex(
                    "\"${Regex.escape(metricName)}\"\\s*:\\s*\\{[^}]*?\"median\"\\s*:\\s*([0-9.eE+-]+)",
                ).find(block)?.groupValues?.get(1)?.toDoubleOrNull()
                if (median != null) { found = true; if (median > worstMedian) worstMedian = median }
            }
        }
        if (!found) {
            throw GradleException(
                "Metric '$metricName' for '$benchmarkName' not found in benchmark JSON. Did the " +
                    "StartupBenchmark run (with a non-Kolibri default home) and emit it?",
            )
        }
        logger.lifecycle(
            "Cold-start TTID (baseline profile), median: %.1f ms (threshold %.1f ms)".format(worstMedian, thresholdMs),
        )
        if (worstMedian > thresholdMs) {
            // Before crying regression, check whether the baked release baseline
            // profile is actually there. A degraded/absent profile (the gitignored
            // local artifact was never generated) inflates this metric for a benign
            // reason — surface that as an actionable regenerate, not a false alarm.
            val profileFile = appReleaseArtProfileDir.get().asFile
                .walkTopDown().firstOrNull { it.name == "baseline-prof.txt" }
            val profileLines = profileFile?.readLines()?.size ?: 0
            if (profileLines < minHealthyProfileLines) {
                throw GradleException(
                    "Cold-start TTID %.1f ms > %.1f ms, BUT the baked release baseline profile looks ".format(worstMedian, thresholdMs) +
                        "degraded/absent ($profileLines lines; healthy ~15k, ~2.7k = library defaults only). " +
                        "This is almost certainly NOT a real regression: the profile is a gitignored local " +
                        "artifact that nothing auto-generates. Run `./gradlew :app:generateBaselineProfile` " +
                        "(connected device), then re-run StartupBenchmark. If it STILL reads ~2.7k after " +
                        "regenerating, the profile generation itself is broken. See PERF-BENCHMARK-SETUP.md.",
                )
            }
            throw GradleException(
                "Cold-start TTID REGRESSED: baseline-profile median %.1f ms > %.1f ms threshold ".format(worstMedian, thresholdMs) +
                    "(profile healthy, $profileLines lines). A heavy init likely landed on the startup " +
                    "path — investigate before merging.",
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Fully-drawn gate — sibling of verifyStartupBenchmark, on the SAME
// startupBaselineProfile block but reading `timeToFullDisplayMs` (TTFD =
// process fork → reportFullyDrawn, fired one frame after the favorites first
// paint). It uniquely guards the favorites-READY path: a regression in the
// PackageManager-enumeration / favorites-render tail inflates TTFD but is
// INVISIBLE to the TTID gate above (TTID is the first frame, drawn before the
// favorites paint). Same dependency-free JSON scan and same degraded-profile
// escape hatch.
// -----------------------------------------------------------------------------
tasks.register("verifyStartupFullyDrawnBenchmark") {
    group = "verification"
    description = "Fail if StartupBenchmark.startupBaselineProfile timeToFullDisplayMs " +
        "median exceeds the threshold (run the StartupBenchmark first)."

    // Threshold in MILLISECONDS, gated on the MEDIAN of the ship-equivalent
    // (CompilationMode.Partial) COLD-start TTFD.
    //
    // CALIBRATION — a deliberate sibling of the 580 ms TTID gate, NOT a naive fit to
    // the idle session that measures TTFD (thermalThrottleSleepSeconds = 0, cool
    // device: its Partial TTID sits ~130 ms below §3's warm 547 baseline the TTID gate
    // is calibrated to, so a threshold fit to the cool TTFD would false-fail in the
    // warmer §3 state where the TTID gate passes). The two gates must stay mutually
    // consistent, so TTFD is anchored to the SAME warm baseline via the thermally-
    // STABLE part of the metric: the TTFD − TTID gap (PackageManager favorites-render
    // tail), effectively equal in both arms.
    //
    // This threshold was RE-BASELINED after the favorites provisional-resolution was
    // parallelized (perf(favorites), PERF-RESULTS §3b): that cut the cool gap from
    // ~341 ms to ~193 ms (cool Partial TTFD 751 → 606). New derivation: warm Partial
    // TTFD ≈ §3 warm Partial TTID (547) + gap (193) ≈ 741 ms; +~6 % headroom (the
    // same position the 580 gate takes over its 547 median) → 790 ms, still below the
    // estimated warm None TTFD (612.9 + 193 ≈ 806), so it FIRES on profile-silence
    // exactly like its TTID sibling. The cool session's measured Partial TTFD (606,
    // p95 666) sits well under 790, so it never false-fails a cool run either. (The
    // pre-parallelization value was 940, off the 341 ms gap.) Device-calibrated —
    // like the TTID gate, re-tune if the reference device changes or the favorites
    // path is re-optimized; a single-session TTID+TTFD re-baseline would let both
    // gates be re-derived from one thermal state.
    //
    // OPERATIONAL: same as verifyStartupBenchmark — cold start is UNMEASURABLE while
    // Kolibri is the default home; the data is produced with another launcher default.
    // Local device only; not wired into the device-free CI. Requires the favorites to
    // be seeded (StartupBenchmark does this) — reportFullyDrawn only fires on a
    // non-empty favorites paint, so an unseeded run emits no TTFD at all.
    val thresholdMs = 790.0
    val benchmarkName = "startupBaselineProfile"
    val metricName = "timeToFullDisplayMs"

    val minHealthyProfileLines = 6_000
    val appReleaseArtProfileDir =
        project(":app").layout.buildDirectory.dir("intermediates/merged_art_profile/release")

    doLast {
        val outDir = layout.buildDirectory.get().asFile
        val jsons = outDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-benchmarkData.json") }
            .toList()
        if (jsons.isEmpty()) {
            throw GradleException(
                "No *-benchmarkData.json under ${outDir.path}. Run " +
                    ":macrobenchmark:connectedBenchmarkAndroidTest (StartupBenchmark) first.",
            )
        }
        var worstMedian = -1.0
        var found = false
        jsons.forEach { f ->
            val text = f.readText()
            val nameIdx = text.indexOf("\"$benchmarkName\"")
            if (nameIdx >= 0) {
                val block = text.substring(nameIdx)
                val median = Regex(
                    "\"${Regex.escape(metricName)}\"\\s*:\\s*\\{[^}]*?\"median\"\\s*:\\s*([0-9.eE+-]+)",
                ).find(block)?.groupValues?.get(1)?.toDoubleOrNull()
                if (median != null) { found = true; if (median > worstMedian) worstMedian = median }
            }
        }
        if (!found) {
            throw GradleException(
                "Metric '$metricName' for '$benchmarkName' not found in benchmark JSON. TTFD is only " +
                    "emitted when reportFullyDrawn() fires (non-empty favorites paint) — did StartupBenchmark " +
                    "run with seeded favorites (and a non-Kolibri default home)?",
            )
        }
        logger.lifecycle(
            "Cold-start TTFD (baseline profile), median: %.1f ms (threshold %.1f ms)".format(worstMedian, thresholdMs),
        )
        if (worstMedian > thresholdMs) {
            val profileFile = appReleaseArtProfileDir.get().asFile
                .walkTopDown().firstOrNull { it.name == "baseline-prof.txt" }
            val profileLines = profileFile?.readLines()?.size ?: 0
            if (profileLines < minHealthyProfileLines) {
                throw GradleException(
                    "Cold-start TTFD %.1f ms > %.1f ms, BUT the baked release baseline profile looks ".format(worstMedian, thresholdMs) +
                        "degraded/absent ($profileLines lines; healthy ~15k, ~2.7k = library defaults only). " +
                        "Almost certainly NOT a real regression: run `./gradlew :app:generateBaselineProfile` " +
                        "(connected device), then re-run StartupBenchmark. See PERF-BENCHMARK-SETUP.md.",
                )
            }
            throw GradleException(
                "Cold-start TTFD REGRESSED: baseline-profile median %.1f ms > %.1f ms threshold ".format(worstMedian, thresholdMs) +
                    "(profile healthy, $profileLines lines). Something inflated the favorites-ready path " +
                    "(enumeration / favorites render) — note TTID may still pass; investigate before merging.",
            )
        }
    }
}
