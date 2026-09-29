import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `launcher.jvm.library` — pure-Kotlin JVM modules (:core, :kolibri:domain, :nyx:domain).
 * Applies the Kotlin JVM plugin and pins Java + Kotlin to one toolchain and one bytecode
 * target. Further plugins (ksp, serialization, java-test-fixtures, jmh) stay in the
 * module — they are per-module choices, not build-wide numbers.
 *
 * Classpath note: the Kotlin Gradle plugin is NOT a runtime dependency of build-logic
 * (compileOnly). Under AGP 9 built-in Kotlin a JVM module gets it from its
 * `kotlin-serialization` alias, which all three modules carry — that alias must stay,
 * or `org.jetbrains.kotlin.jvm` below is not found.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(LauncherBuild.JDK))
            }
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(LauncherBuild.JDK)
                compilerOptions {
                    jvmTarget.set(JvmTarget.fromTarget(LauncherBuild.JDK.toString()))
                }
            }
        }
    }
}
