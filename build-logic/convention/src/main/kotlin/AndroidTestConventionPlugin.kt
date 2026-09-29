import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `launcher.android.test` — com.android.test harness modules that drive an app from a
 * separate APK (:kolibri:macrobenchmark, :kolibri:baselineprofile).
 *
 * Owns the build-wide numbers (same SDKs as the app under test, Java/Kotlin 21) and the
 * plain JUnit instrumentation runner: a harness self-instruments the target app's
 * process and needs neither Hilt nor the app's test runner. The module keeps what is
 * its own: namespace, targetProjectPath, build types, experimental properties.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                compileSdk = LauncherBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = LauncherBuild.MIN_SDK
                    targetSdk = LauncherBuild.TARGET_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = launcherJavaVersion
                    targetCompatibility = launcherJavaVersion
                }
            }
            configureKotlinAndroid()
        }
    }
}
