// :common-ui — shared, product-neutral Android UI utilities (MONOREPO_MERGE_SPEC
// §4.1, Phase 2). Android-library consumed by both apps. Starts as the minimal
// set the :feature-crashreporting extraction needs (ToastSafe, LaunchTrace); the
// rest of ui.base / ui.util migrates here as further features are shared.
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
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.fragment.ktx)
}
