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

