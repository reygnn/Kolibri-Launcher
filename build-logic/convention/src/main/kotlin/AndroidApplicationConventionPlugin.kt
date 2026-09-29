import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `launcher.android.application` — the two app modules (:kolibri:app, :nyx:app).
 *
 * Step 1b-4a: the build-wide numbers (SDKs, Java/Kotlin 21), the unit-test setup shared
 * with every library (Robolectric resources, default values, MockK agent flag) and
 * BuildConfig generation (both apps read BuildConfig). The module keeps what is its own:
 * namespace, applicationId, version, test runner, view binding, dependencies.
 * Later steps add the rest of the shared app setup (1b-4b: lint, test orchestrator,
 * coverage, discovery tasks; 1b-4c: release/R8 rules — SPEC_NYX_REWRITE D1 end form).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                compileSdk = LauncherBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = LauncherBuild.MIN_SDK
                    targetSdk = LauncherBuild.TARGET_SDK
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
                buildFeatures {
                    buildConfig = true
                }
            }
            configureKotlinAndroid()
        }
    }
}
