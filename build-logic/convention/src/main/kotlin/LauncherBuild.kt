/**
 * The one place for build-wide numbers (SPEC_NYX_REWRITE Phase 1b, A10). Modules do not
 * set these themselves; the convention plugins apply them.
 *
 * DELIBERATE values — do not change without explicit instruction. minSdk 36 (Android 16);
 * compileSdk = targetSdk = 37 (Android 17), lifted 2026-07-18 for core-ktx 1.19.0 (see
 * gradle/libs.versions.toml). JDK 21 is also what Robolectric needs for SDK 36.
 */
object LauncherBuild {
    const val JDK = 21
    const val COMPILE_SDK = 37
    const val MIN_SDK = 36
    const val TARGET_SDK = 37
}
