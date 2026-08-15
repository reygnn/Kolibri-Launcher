/*
 * ═══════════════════════════════════════════════════════════════════════════
 * :domain — domain layer (repositories, use cases, models, core utilities)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Pure-Kotlin JVM module. The §9.2 split landed this layer as an Android
 * Library at first; the §11 sweeps removed every Android import from
 * `src/main/`, and the §12 follow-up unblocked the type-switch by
 * abstracting Timber behind `core/KolibriLog` (so `:domain` no longer
 * needs Timber 5.x's `.aar` artefact on its compile classpath). All
 * logging from domain code now goes through `KolibriLog`, whose
 * lambda-backed handlers are wired by `:app/KolibriLauncherApp.onCreate`
 * to the real Timber API.
 *
 * Hilt: this layer declares @Module classes (e.g. DispatcherModule). The
 * `hilt-android` Gradle plugin is NOT applied here because that plugin
 * adds Android-specific entry-point processing (`@AndroidEntryPoint`,
 * Application subclassing) which a pure-JVM module cannot host. What we
 * still need — code generation for `@Module` / `@Provides` / `@Binds` —
 * comes from the `ksp(libs.hilt.compiler)` invocation. The aggregating
 * glue runs in `:app`, where the `hilt-android` plugin IS applied.
 */
plugins {
    kotlin("jvm")
    alias(libs.plugins.ksp)
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
    // JMH microbenchmarks for pure-Kotlin hot paths (applyCustomNames, …).
    // Adds an isolated `src/jmh/` source set + a `:domain:jmh` task; it does
    // NOT touch `test` / jacoco / checkConventions. Run with `./gradlew
    // :domain:jmh`. JVM-only by design — this is the reproducible-benchmark
    // counterpart to the JVM-first test philosophy (no androidTest, no device).
    alias(libs.plugins.jmh)
    // Produces build/jacoco/test.exec — note the task is `test`, not
    // `testDebugUnitTest`: this is a pure-JVM module with no build variants,
    // the same asymmetry that kept it out of the CI test step. :app's
    // aggregating `jacocoTestReport` consumes the exec file.
    id("jacoco")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

// JMH run configuration. Pins the harness version to the catalog and emits a
// machine-readable JSON alongside the console table, so a committed baseline
// can be diffed by hand (there is no hard CI gate — JMH numbers are
// host-dependent; the JSON is the reproducible artefact, not an auto-fail).
jmh {
    // .asProvider() disambiguates the `jmh` version group (it also holds
    // `jmh-gradle-plugin`) down to the leaf `jmh` version.
    jmhVersion.set(libs.versions.jmh.asProvider().get())
    resultFormat.set("JSON")
    // Modest defaults — enough to stabilise a µs-scale pure function without a
    // multi-minute run. Override per-benchmark via annotations if needed.
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)

    // === Ad-hoc subset filter ===================================================
    // The full suite runs 30+ min end-to-end because `luminancePass` alone is
    // ~0.4 ms/call over two sizes with warmup+forks; a one-off check of a single
    // pure function should not pay for that. `includes`/`excludes` are REGEXes
    // matched against each benchmark's fully-qualified name, comma-separated for
    // several patterns. There is NO `-P` filter built into the me.champeau.jmh
    // plugin (the earlier `-Pjmh.includes` attempt was a no-op that only left a
    // stale JMH lock behind) — these two properties are that missing knob:
    //
    //   ./gradlew :domain:jmh -PjmhInclude=ApplyCustomNames   # one class
    //   ./gradlew :domain:jmh -PjmhInclude=filterByName,score # several
    //   ./gradlew :domain:jmh -PjmhExclude=Luminance          # everything fast
    //
    // Unset → the full suite, which stays the canonical baseline run.
    val includeFilter = (project.findProperty("jmhInclude") as String?)
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
    val excludeFilter = (project.findProperty("jmhExclude") as String?)
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
    includeFilter?.let { includes.set(it) }
    excludeFilter?.let { excludes.set(it) }

    // === Ad-hoc profiler knob ===================================================
    // Same shape as the include/exclude filters: JMH ships profilers (`gc` for
    // per-op allocation rate, `stack` for hot frames) that turn a throughput run
    // into an allocation/CPU study. Off by default (they slow the run and are
    // host-dependent); opt in ad hoc, comma-separated for several:
    //
    //   ./gradlew :domain:jmh -PjmhInclude=TimeWeightedSort -PjmhProfilers=gc
    //
    (project.findProperty("jmhProfilers") as String?)
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        ?.let { profilers.set(it) }

    // A filtered run is PARTIAL, so it must not clobber the committed-baseline
    // JSON (`results.json`) with a subset — that footgun is the whole reason a
    // filtered run is worth isolating. Redirect it to a sibling file instead;
    // the unfiltered baseline run keeps writing the canonical path.
    val filtered = includeFilter != null || excludeFilter != null
    val resultsName = if (filtered) "results-filtered.json" else "results.json"
    resultsFile.set(layout.buildDirectory.file("reports/jmh/$resultsName"))
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // hilt-core is the JVM-only artefact (JAR). hilt-android (AAR) cannot
    // be consumed by a kotlin("jvm") module; the @Module / @Provides /
    // @Binds annotations and the runtime support live in hilt-core, which
    // is enough for what :domain declares (DispatcherModule etc.). The
    // aggregating glue runs in :app via the hilt-android plugin.
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    // Test dependencies — pure-JVM tests in :domain/src/test/. Brocken-B
    // (Test-Isolation per Modul) starts here; see TODO §13 for status.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)

    // Shared test fixtures (TimberRule, MainDispatcherRule, …) consumed
    // by both :domain/src/test/ and :app/src/test/ via the
    // `java-test-fixtures` plugin. The fixtures source set lives in
    // :domain/src/testFixtures/ and gets its own `implementation`-style
    // configuration below; consumers reach it via
    // `testImplementation(testFixtures(project(":domain")))`.
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.turbine)
    testFixturesImplementation(libs.truth)
    testFixturesImplementation(libs.mockk)
    testFixturesImplementation(libs.kotlin.test.junit)
    // hilt-core provides javax.inject — needed because some fake repositories
    // are @Singleton @Inject constructor() (used as Hilt-test-replacements
    // in :app's @TestInstallIn modules).
    testFixturesImplementation(libs.hilt.core)
}

