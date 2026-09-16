// :common-ui — shared, product-neutral Android UI utilities (MONOREPO_MERGE_SPEC
// §4.1, Phase 2). Android-library consumed by both apps. Starts as the minimal
// set the :feature-crashreporting extraction needs (ToastSafe, LaunchTrace); the
// rest of ui.base / ui.util migrates here as further features are shared. The
// shared dispatchTouchEvent gesture stack (GestureDispatchCore + analyzer +
// GestureFrameLayout) lives in the `gesture` package, consumed by both apps.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.reygnn.launcher.common.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    testOptions {
        // JVM unit tests here mock Context and touch android.* stubs (e.g.
        // DateFormat.is24HourFormat in ClockDelegateTest); return defaults instead
        // of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.fragment.ktx)
    // lifecycle-viewmodel-ktx: BaseViewModel (ui.base) extends androidx ViewModel
    // and uses viewModelScope. Explicit rather than leaning on a transitive.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // appcompat: ZoomableImageView extends AppCompatImageView. material is NOT
    // pulled here (the FAB/edit-toolbar Views stay in kolibri); the
    // material-before-appcompat force() rule lives in each :app that has both.
    implementation(libs.androidx.appcompat)
    // coroutines-android: WallpaperViewBinder's parallel-decode (async/awaitAll/
    // Semaphore) + Main dispatcher for view mutation.
    implementation(libs.kotlinx.coroutines.android)

    // hilt-android for the @Inject/@Singleton + @ApplicationContext annotations on
    // shared components (WallpaperCompositeCache, WallpaperFlattener). The component
    // graph + codegen run in each :app (which applies the hilt plugin) — :common-ui
    // only needs the annotations on its compile classpath, not the plugin/ksp.
    implementation(libs.hilt.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
