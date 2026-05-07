import java.io.FileInputStream
import java.util.Properties

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * WICHTIG FÜR KI-ASSISTENTEN (Gemini, Claude, etc.):
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Versionen leben jetzt in `gradle/libs.versions.toml` — NICHT mehr hier
 * im build.gradle.kts. Pinning-Marker (DO NOT UPGRADE / DO NOT DOWNGRADE
 * / DO NOT CHANGE / OK to upgrade) stehen direkt neben den Versionen im
 * Catalog. ⚠️ Das Ignorieren dieser Marker führt zu Build-Fehlern! ⚠️
 *
 * minSdk=36 und compileSdk=36 sind ABSICHTLICH so gesetzt (Android 16)!
 * Diese Werte NICHT ändern ohne explizite Anweisung!
 * ═══════════════════════════════════════════════════════════════════════════
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android)
    id("kotlin-parcelize")
    id("jacoco")
    alias(libs.plugins.kotlin.serialization)
}

// Lädt die sensiblen Daten aus der keystore.properties-Datei
// Diese Datei sollte im Stammverzeichnis des Projekts liegen und in .gitignore eingetragen sein
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

// Nur laden wenn Datei existiert (für CI)
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.github.reygnn.kolibri_launcher"
    compileSdk = 36 // DO NOT CHANGE !!!

    val secretsPropertiesFile = rootProject.file("secrets.properties")
    val secretsProperties = Properties()
    if (secretsPropertiesFile.exists()) {
        secretsProperties.load(FileInputStream(secretsPropertiesFile))
    }

    defaultConfig {
        applicationId = "com.github.reygnn.kolibri_launcher"
        minSdk = 36 // DO NOT CHANGE !!!
        targetSdk = 36 // DO NOT CHANGE !!!
        versionCode = 84
        versionName = "0.99.64"

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
        getByName("androidTest").resources.srcDirs("src/androidTest/resources")
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
                getDefaultProguardFile("proguard-android.txt"),
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
    implementation(libs.androidx.appcompat)  // Achtung: bringt älteres 'MaterialYou' mit
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
    implementation(libs.kotlinx.serialization.json)  // 1.10.0 benötigt Kotlin 2.3.0
    testImplementation(libs.robolectric)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.browser)
    kapt(libs.hilt.compiler)

    implementation(libs.acra.core)
    implementation(libs.acra.http)

    debugImplementation(libs.leakcanary.android)

    // --- LOKALE UNIT-TESTS (laufen auf dem PC/JVM) ---
    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.json)

    // Hilt für Unit-Tests
    testImplementation(libs.hilt.android.testing)
    kaptTest(libs.hilt.compiler)

    // MockK für Unit-Tests
    testImplementation(libs.mockk)

    // AndroidX Test (für Robolectric-basierte Activity/Fragment-Tests)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)


    // --- INSTRUMENTIERTE TESTS (laufen auf Emulator/Gerät) ---
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

    // Hilt für instrumentierte Tests
    androidTestImplementation(libs.hilt.android.testing)
    kaptAndroidTest(libs.hilt.compiler)

    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Turbine für SharedFlow-Subscriber-Race-Pattern in Receiver-Tests
    // (siehe TESTING_CONVENTIONS „MUTABLESHAREDFLOW IN CONSTRUCTOR" — die
    // Subscriber-vor-Trigger-Garantie funktioniert auf instrumentierter
    // Hardware genauso wie unter JVM, nur ohne TestDispatcher).
    androidTestImplementation(libs.turbine)
}

kapt {
    correctErrorTypes = true
}

configurations.all {
    resolutionStrategy {
        // DO NOT REMOVE !!!
        // Erzwingt material auch wenn appcompat eine ältere Version mitbringt.
        // Warnung: ohne diesen force WIRD es bei falscher Reihenfolge der Dependencies zu Dependency-Konflikten kommen!
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

// Code Coverage Configuration mit JaCoCo
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

        // Script ausführen
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

// Automatisch nach Release Build für APK und Bundle
tasks.configureEach {
    if (name in listOf("assembleRelease", "bundleRelease")) {
        finalizedBy("uploadProguardMapping")
    }
}
