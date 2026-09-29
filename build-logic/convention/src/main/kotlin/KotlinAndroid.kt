import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Java level for AGP's compileOptions, derived from the one JDK number. */
internal val launcherJavaVersion: JavaVersion get() = JavaVersion.toVersion(LauncherBuild.JDK)

/**
 * Kotlin settings shared by every Android module (library, test, application): one
 * toolchain — which also sets the Java toolchain — and one bytecode target.
 */
internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(LauncherBuild.JDK)
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(LauncherBuild.JDK.toString()))
        }
    }
}

/** JVM flag every unit test JVM gets: MockK attaches its agent dynamically (JDK 21+). */
internal const val MOCKK_AGENT_JVM_ARG = "-XX:+EnableDynamicAgentLoading"
