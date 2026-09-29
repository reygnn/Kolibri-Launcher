import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
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
    configureJavaToolchain()
}

/**
 * JDK 21 for every JavaCompile task, generated ones included: Hilt's
 * `hiltJavaCompile<Variant>` does not pick up the project toolchain on its own and falls
 * back to the JDK running Gradle ("invalid source release: 21" on any other JDK). Kolibri
 * had this per module; Nyx relied on the dev machine's JDK happening to be 21.
 */
internal fun Project.configureJavaToolchain() {
    val jdk = JavaLanguageVersion.of(LauncherBuild.JDK)
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(jdk) }
    val compiler = extensions.getByType<JavaToolchainService>().compilerFor { languageVersion.set(jdk) }
    tasks.withType<JavaCompile>().configureEach { javaCompiler.set(compiler) }
}

/** JVM flag every unit test JVM gets: MockK attaches its agent dynamically (JDK 21+). */
internal const val MOCKK_AGENT_JVM_ARG = "-XX:+EnableDynamicAgentLoading"
