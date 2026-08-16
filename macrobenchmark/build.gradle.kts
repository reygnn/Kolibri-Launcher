// =============================================================================
// :macrobenchmark — on-device launch-latency gate
// =============================================================================
//
// A `com.android.test` module (NOT shipped) that measures Kolibri's app-launch
// hot path on a REAL device via Macrobenchmark + Perfetto trace sections. It
// exists to LOCK the sub-frame property established by the APP-START-PERFORMANCE
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
    // per-iteration TAP->DISPATCH gap. Measured p99 on a Pixel 9a is ~0.85 ms
    // (APP-START-PERFORMANCE.md); 4.0 ms is generous, non-flaky headroom that
    // is still under half a 120 Hz frame (8.33 ms). A structural regression
    // (a frame added to the hop) lands well past this. Device-calibrated —
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
