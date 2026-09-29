/**
 * The one place for build-wide numbers (SPEC_NYX_REWRITE Phase 1b, A10). Modules do not
 * set these themselves; the convention plugins apply them.
 */
object LauncherBuild {
    const val JDK = 21
    const val COMPILE_SDK = 37
    const val MIN_SDK = 36
    const val TARGET_SDK = 37
}
