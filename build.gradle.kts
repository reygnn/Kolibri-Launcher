// Top-level build file. Plugin versions live in `gradle/libs.versions.toml`.
// Union of both source repos' root plugin declarations. AGP 9 provides built-in
// Kotlin — no `org.jetbrains.kotlin.android` alias (do not let the AS upgrade
// assistant re-add it).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
