// :common-ui — shared, product-neutral Android UI utilities (MONOREPO_MERGE_SPEC
// §4.1, Phase 2). Android-library consumed by both apps. Starts as the minimal
// set the :feature-crashreporting extraction needs (ToastSafe, LaunchTrace); the
// rest of ui.base / ui.util migrates here as further features are shared. The
// shared dispatchTouchEvent gesture stack (GestureDispatchCore + analyzer +
// GestureFrameLayout) lives in the `gesture` package, consumed by both apps.
plugins {
    id("launcher.android.library") // SDK, Java/Kotlin 21, unit-test setup (build-logic)
}

android {
    namespace = "com.github.reygnn.launcher.common.ui"

    // WallpaperParityScenes (SPEC_NYX_REWRITE 3b/35): one source of the parity scenes for the
    // Robolectric test here and the instrumented test in :kolibri:app. AGP wires the fixtures into
    // this module's own unit tests (as in :nyx:data). Kotlin needs the testFixtures flag in
    // gradle.properties.
    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.fragment.ktx)
    // lifecycle-viewmodel-ktx: BaseViewModel (ui.base) extends androidx ViewModel
    // and uses viewModelScope. Explicit rather than leaning on a transitive.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // appcompat: ZoomableImageView extends AppCompatImageView.
    implementation(libs.androidx.appcompat)
    // material: the shared wallpaper-edit Views (SpeedDialFabCluster uses
    // FloatingActionButton, CommandsPanel uses MaterialButton) live here now.
    // Needs the same material-before-appcompat force() as the apps (see the
    // configurations block below) — appcompat drags an older MaterialYou.
    implementation(libs.material)
    // coroutines-android: WallpaperViewBinder's parallel-decode (async/awaitAll/
    // Semaphore) + Main dispatcher for view mutation.
    implementation(libs.kotlinx.coroutines.android)

    // hilt-android for the @Inject/@Singleton + @ApplicationContext annotations on
    // shared components (WallpaperCompositeCache, WallpaperFlattener). The component
    // graph + codegen run in each :app (which applies the hilt plugin) — :common-ui
    // only needs the annotations on its compile classpath, not the plugin/ksp.
    implementation(libs.hilt.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth) // A12: Truth is the one assertion library
    testImplementation(libs.kotlin.test.junit) // assertFailsWith / assertIs (A12 exceptions)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // robolectric: AppLauncherImplTest needs a real ComponentName (its captured
    // package/class are asserted) — the un-mockable Android type the shared
    // AppLauncher builds for startMainActivity.
    testImplementation(libs.robolectric)
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRule + recordEmissions

    // WallpaperParityScenes: WallpaperState in its API (:core), the uniform guard asserts with Truth
    // (A12), createBitmap (core-ktx), and it builds the detached ZoomableImageView (appcompat).
    testFixturesApi(project(":core"))
    testFixturesImplementation(libs.truth)
    testFixturesImplementation(libs.androidx.core.ktx)
    testFixturesImplementation(libs.androidx.appcompat)
}

// `material` MUST resolve before `appcompat` (appcompat drags an older
// MaterialYou) — same force() the apps carry.
configurations.configureEach {
    resolutionStrategy { force(libs.material) }
}
