import java.io.FileInputStream
import java.util.Properties

/*
 * :app — Android application. Hosts @HiltAndroidApp, the launcher Activity,
 * the home-grid / drawer / folder / dock UI (RecyclerView adapters + the
 * stale-binding guard, ICL-INV-9), Navigation, and the aggregating Hilt glue.
 *
 * ACRA / keystore / secrets wiring is carried over from Kolibri-Launcher and
 * omitted here for brevity (rule 8 still applies — ACRA is opt-in).
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    // baselineprofile plugin added later, as in the big Kolibri.
}

android {
    namespace = "com.github.reygnn.nyx_launcher"
    compileSdk = 37

    // ACRA endpoint from the shared root secrets.properties (gitignored), same as
    // Kolibri. Fed into :feature-crashreporting via AcraConfig at runtime.
    val secretsPropertiesFile = rootProject.file("secrets.properties")
    val secretsProperties = Properties()
    if (secretsPropertiesFile.exists()) {
        secretsProperties.load(FileInputStream(secretsPropertiesFile))
    }

    defaultConfig {
        applicationId = "com.github.reygnn.nyx_launcher"
        minSdk = 36
        targetSdk = 37
        versionCode = 9
        versionName = "0.1.7-dev"

        buildConfigField("String", "ACRA_URL", "\"${secretsProperties.getProperty("acra.url", "")}\"")
        buildConfigField("String", "ACRA_LOGIN", "\"${secretsProperties.getProperty("acra.login", "")}\"")
        buildConfigField("String", "ACRA_PASSWORD", "\"${secretsProperties.getProperty("acra.password", "")}\"")

        testInstrumentationRunner = "com.github.reygnn.nyx_launcher.HiltTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildFeatures { buildConfig = true }

    // Dev-command visibility: always on in debug; in release only for a personal
    // build (`-PdevCommands` or the `-PdailyDriver` master flag). Public release = off.
    fun personalProperty(name: String): Boolean =
        (project.findProperty(name) as String?)?.let { it.isEmpty() || it.toBoolean() } ?: false
    val devCommandsInRelease = personalProperty("dailyDriver") || personalProperty("devCommands")

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("boolean", "SHOW_DEV_COMMANDS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "SHOW_DEV_COMMANDS", devCommandsInRelease.toString())
        }
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// `material` MUST resolve before `appcompat` (appcompat drags an older
// MaterialYou). Same force() as Kolibri-Launcher.
configurations.configureEach {
    resolutionStrategy { force(libs.material) }
}

dependencies {
    implementation(project(":nyx:domain"))
    implementation(project(":nyx:data"))
    implementation(project(":common-ui"))
    implementation(project(":common-data"))
    implementation(project(":feature-crashreporting"))

    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.timber)
    // ACRA (opt-in, rule 8): implementation(libs.acra.core) / implementation(libs.acra.http)
    debugImplementation(libs.leakcanary.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(testFixtures(project(":nyx:domain")))
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRuleBase

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.contrib)
    androidTestImplementation(libs.androidx.fragment.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.truth)
    // Shared TAPL-lite test-support facade (BasePage / awaitUntil / drag / probe).
    androidTestImplementation(project(":common-testing-android"))
}

// --- ACRA ProGuard mapping upload (mirrors Kolibri) ---
// After a release build, POST build/outputs/mapping/release/mapping.txt to the ACRA
// server (Acrarium) so obfuscated crash stacks deobfuscate. Best-effort: a no-op when
// minify is off (no mapping) or the script is missing, and NON-FATAL on any upload
// failure — missing secrets (fresh clone / CI), offline, or ACRA down must never fail
// the release build, since the deliverable AAB is already produced by the time this
// finalizer runs. Credentials come from the shared root secrets.properties via
// acra-scripts/load_secrets.sh; re-run `./gradlew uploadProguardMapping` once available.
tasks.register("uploadProguardMapping") {
    group = "acra"
    description = "Upload the release ProGuard mapping to the ACRA server"
    doLast {
        val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        if (!mappingFile.exists()) {
            println("uploadProguardMapping: no mapping (${mappingFile.path}) — skipped (minify off?)")
            return@doLast
        }
        val script = file("acra-scripts/upload_mapping.sh")
        if (!script.exists()) {
            println("uploadProguardMapping: ${script.path} missing — skipped")
            return@doLast
        }
        val pkg = android.defaultConfig.applicationId
        val versionCode = android.defaultConfig.versionCode
        println("uploadProguardMapping: uploading mapping for $pkg ($versionCode)…")
        val exit = ProcessBuilder("bash", script.absolutePath, mappingFile.absolutePath, pkg, versionCode.toString())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        // Non-fatal by contract: the AAB is already built. A missing secrets.properties,
        // an offline machine or an ACRA outage must not flip the release build to FAILED.
        if (exit != 0) {
            logger.warn(
                "uploadProguardMapping: upload failed (exit $exit) — release output is unaffected; " +
                    "re-run ./gradlew uploadProguardMapping once ACRA/secrets are reachable.",
            )
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") finalizedBy("uploadProguardMapping")
}

// --- Convention linters (reuse Kolibri's battle-tested detectors) ---
// Nyx does not re-implement the checks: the detector logic lives once in
// kolibri/tools/*.awk (+ the two generalized *.sh). This orchestrator runs those
// detectors over Nyx's own sources, with Nyx's own scan roots / positive lists,
// and only the checks that apply to Nyx. The per-check triage (what runs, what is
// deliberately skipped and why) is documented at the top of the script.
tasks.register<Exec>("checkConventions") {
    group = "verification"
    description = "Runs the project-convention linter (reuses Kolibri's detectors)."
    workingDir = projectDir.parentFile // = nyx/ (scripts live in nyx/tools)
    commandLine = listOf("bash", "tools/check-conventions.sh")
}

// Rule 13 — git-diff-aware German-comment linter. Reuses Kolibri's script + awk
// with RULE13_SCAN_ROOT pointed at nyx/, so it flags only newly added German
// comments in Nyx's own diff. Override the comparison base with CHECK_BASE.
tasks.register<Exec>("checkRule13") {
    group = "verification"
    description = "Runs the Rule 13 (German comments) linter against Nyx's git diff."
    workingDir = projectDir.parentFile // = nyx/
    environment("RULE13_SCAN_ROOT", projectDir.parentFile.absolutePath)
    commandLine = listOf("bash", "../kolibri/tools/check-rule13-german-comments.sh")
}
