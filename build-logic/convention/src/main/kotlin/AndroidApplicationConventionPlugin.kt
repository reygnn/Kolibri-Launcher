import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.util.Properties

/**
 * `launcher.android.application` — the two app modules (:kolibri:app, :nyx:app).
 *
 * Owns everything the two apps must share (decided 29.09.: Kolibri's setup for both):
 *  - build-wide numbers (SDKs, Java/Kotlin 21) and BuildConfig generation;
 *  - unit tests: Robolectric resources, default values, MockK agent flag;
 *  - instrumented tests: the androidx test orchestrator, with `clearPackageData` — it
 *    runs `pm clear` BETWEEN tests from outside the instrumentation, which a rule inside
 *    the test process cannot do safely; the argument has no effect without the orchestrator;
 *  - strict lint with a per-module baseline (`lint-baseline.xml` next to the build file;
 *    regenerate with `./gradlew :<app>:app:updateLintBaseline` after a deliberate cleanup);
 *  - the report-only discovery tasks (the scripts sweep the whole monorepo).
 *  - the ACRA endpoint as BuildConfig fields (ACRA_URL / _LOGIN / _PASSWORD) from the
 *    monorepo-root `secrets.properties` (gitignored; empty strings when absent);
 *  - coverage: `jacocoTestReport` over the app's app/data/domain modules (Coverage.kt);
 *  - R8 inputs: optimize defaults + the module's proguard-rules.pro + rules generated
 *    from the namespace (ReleaseRules.kt, SPEC_NYX_REWRITE D1 end form).
 * The module keeps what is its own: namespace, applicationId, version, test runner and
 * its arguments, view binding, build types, dependencies.
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
                defaultConfig {
                    testInstrumentationRunnerArguments["clearPackageData"] = "true"
                    // ACRA endpoint, fed into :feature-crashreporting via AcraConfig at runtime.
                    val acra = acraSecrets()
                    for ((field, key) in ACRA_FIELDS) {
                        buildConfigField("String", field, "\"${acra.getProperty(key, "")}\"")
                    }
                }
                testOptions {
                    execution = "ANDROIDX_TEST_ORCHESTRATOR"
                }
                lint {
                    abortOnError = true
                    checkReleaseBuilds = true
                    warningsAsErrors = false
                    // Grandfathers pre-existing warnings so CI reports only NEW findings.
                    baseline = file("lint-baseline.xml")
                    error += setOf(
                        "MissingTranslation",
                        "ExtraTranslation",
                        "MissingDefaultResource",
                        // New dead resources fail the build (locks in the AUDIT cleanup);
                        // a module's false positives go into its own lint.xml.
                        "UnusedResources",
                    )
                }
            }
            dependencies.add("androidTestUtil", libs.findLibrary("androidx-test-orchestrator").get())
            configureKotlinAndroid()
            configureAppCoverage()
            configureReleaseRules()
            registerDiscoveryTasks()
        }
    }
}

/**
 * Report-only discovery sweeps (never fail the build). The scripts cover the whole
 * monorepo, so the tasks behave the same in both apps; defined once here.
 */
private fun Project.registerDiscoveryTasks() {
    val scans = listOf(
        Triple("scanCancelCandidates", "scan-cancel-candidates.sh",
            "Lists non-whitelisted files whose broad catches may belong in CANCEL_FILES (report-only)."),
        Triple("scanOomCandidates", "scan-oom-candidates.sh",
            "Lists non-whitelisted files whose Exception catches may belong in OOM_FILES (report-only)."),
        Triple("scanInitOrderLaunch", "scan-init-order-launch.sh",
            "Lists files that launch a coroutine in an init block followed by a property initializer (report-only)."),
    )
    val script = { name: String -> rootProject.file("tools/$name").absolutePath }
    for ((task, file, text) in scans) {
        tasks.register<Exec>(task) {
            group = "verification"
            description = text
            workingDir = rootProject.projectDir
            commandLine("bash", script(file))
        }
    }
}

/** BuildConfig field → key in the root `secrets.properties`. */
private val ACRA_FIELDS = listOf(
    "ACRA_URL" to "acra.url",
    "ACRA_LOGIN" to "acra.login",
    "ACRA_PASSWORD" to "acra.password",
)

/** The shared root `secrets.properties` (gitignored); empty when the file is absent. */
private fun Project.acraSecrets(): Properties = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
