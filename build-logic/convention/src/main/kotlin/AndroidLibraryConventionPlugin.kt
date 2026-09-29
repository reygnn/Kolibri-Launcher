import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `launcher.android.library` — every Android library module (:common-ui, :common-data,
 * :common-testing-android, :feature-crashreporting, :kolibri:data, :nyx:data).
 *
 * Owns the build-wide numbers and the unit-test setup; the module keeps only what is
 * genuinely its own (namespace, testFixtures, an instrumentation runner, dependencies).
 * Unit tests: Robolectric needs the merged resources, and JVM tests that touch android.*
 * stubs get default values instead of "not mocked"; every test JVM gets the MockK agent
 * flag (decided 29.09.: Kolibri's setup for all modules).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                compileSdk = LauncherBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = LauncherBuild.MIN_SDK
                }
                compileOptions {
                    sourceCompatibility = launcherJavaVersion
                    targetCompatibility = launcherJavaVersion
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                    unitTests.isReturnDefaultValues = true
                    unitTests.all { it.jvmArgs(MOCKK_AGENT_JVM_ARG) }
                }
            }
            configureKotlinAndroid()
        }
    }
}
