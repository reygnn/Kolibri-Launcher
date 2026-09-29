import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File

/** Generated / wiring code that says nothing about test coverage. */
private val COVERAGE_EXCLUDES = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*.*",
    "android/**/*.*", "**/databinding/**", "**/di/**", "**/*_Factory.*",
    "**/*_MembersInjector.*", "**/*Module.*", "**/*Module$*.*", "**/*_Impl.*",
    "**/*_HiltComponents*.*",
)

/**
 * JaCoCo for an app module: `./gradlew :<app>:app:jacocoTestReport` unions the app's
 * three modules (app, data, domain) — a report over :app alone once described half the
 * suite while the use-case and repository layers were simply absent. The sibling modules
 * apply `id("jacoco")` themselves so their unit tests write the exec files read here.
 *
 * Task paths are derived from the app's own project path (`:<app>:data:…`), never
 * written out: the hard-coded `:data:testDebugUnitTest` / `:domain:test` of the
 * pre-monorepo report stopped resolving at the merge.
 */
internal fun Project.configureAppCoverage() {
    pluginManager.apply("jacoco")
    extensions.configure<ApplicationExtension> {
        testOptions.unitTests.all { test ->
            test.extensions.configure(JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
    val app = requireNotNull(parent) { "launcher.android.application expects an app module at :<app>:app" }
    val dataDir = rootProject.project("${app.path}:data").projectDir
    val domainDir = rootProject.project("${app.path}:domain").projectDir
    val buildDir = layout.buildDirectory.get().asFile

    tasks.register<JacocoReport>("jacocoTestReport") {
        group = "verification"
        description = "Unit-test coverage over ${app.path}:app, :data and :domain."
        dependsOn("testDebugUnitTest", "${app.path}:data:testDebugUnitTest", "${app.path}:domain:test")
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        // Android modules compile with AGP 9's built-in Kotlin; the pure-JVM :domain uses
        // the standard Gradle layout.
        val classDirPaths = listOf(
            "$buildDir/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
            "$dataDir/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
            "$domainDir/build/classes/kotlin/main",
        )
        classDirectories.setFrom(classDirPaths.map { dir -> fileTree(dir) { exclude(COVERAGE_EXCLUDES) } })
        // testFixtures/ is deliberately not a source dir: contracts there are scaffolding.
        sourceDirectories.setFrom(files("$projectDir/src/main/java", "$dataDir/src/main/java", "$domainDir/src/main/java"))
        executionData.setFrom(
            files(
                "$buildDir/jacoco/testDebugUnitTest.exec",
                "$dataDir/build/jacoco/testDebugUnitTest.exec",
                "$domainDir/build/jacoco/test.exec",
            ),
        )
        // A class path that stops resolving (AGP layout change, module rename) must fail
        // loudly: JacocoReport over an empty directory silently reports nothing.
        doFirst {
            val empty = classDirPaths.filter { dir -> File(dir).walkTopDown().none { it.extension == "class" } }
            require(empty.isEmpty()) {
                "jacocoTestReport: no .class files under ${empty.joinToString()} — the class output " +
                    "layout changed; fix classDirPaths in build-logic/Coverage.kt instead of " +
                    "shipping an empty coverage report."
            }
        }
    }
}
