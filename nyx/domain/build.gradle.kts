/*
 * :domain — pure-Kotlin JVM layer (models, repository interfaces, use cases,
 * the pure transitions HomeLayoutTransition / HomeLayoutReconciler).
 *
 * NO Android on the compile classpath (CLAUDE.md rule 14 / IHM-INV-1). No
 * Bitmap/Drawable/LauncherApps ever reaches here — icons are referenced, not
 * rendered. Logging goes through a small KolibriLog seam, not Timber directly,
 * so this module needs no .aar on its classpath.
 *
 * Hilt: declares @Module/@Provides/@Binds (e.g. DispatcherModule). The
 * hilt-android PLUGIN is deliberately NOT applied — it adds Android entry-point
 * processing a JVM module can't host. Codegen comes from ksp(hilt.compiler);
 * hilt-core is the JVM-only (JAR) artifact. Aggregation runs in :app.
 */
plugins {
    kotlin("jvm")                              // version from AGP 9 built-in Kotlin
    alias(libs.plugins.ksp)
    `java-test-fixtures`                       // Contract abstract classes + Fakes
    alias(libs.plugins.kotlin.serialization)   // @Serializable DTOs live in :data,
    // but value objects that need it may compile here; keep the plugin available.
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JVM-only Hilt artifact (JAR). hilt-android (AAR) cannot be consumed by a
    // kotlin("jvm") module; hilt-core carries the @Module/@Provides/@Binds
    // annotations + javax.inject that :domain declares.
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    // Pure-JVM unit tests (the truth-table tests for the transitions).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)

    // Shared fixtures (MainDispatcherRule, Fake*Repository, *Contract classes)
    // consumed by :domain and :data test source sets.
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.turbine)
    testFixturesImplementation(libs.truth)
    testFixturesImplementation(libs.mockk)
    testFixturesImplementation(libs.kotlin.test.junit)
    testFixturesImplementation(libs.hilt.core)   // javax.inject for @Inject fakes
}
