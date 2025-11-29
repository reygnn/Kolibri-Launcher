// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // alias(libs.plugins.android.application) apply false
    // alias(libs.plugins.kotlin.android) apply false
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false   // 2.2.x --> safe to update
    id("com.google.dagger.hilt.android") version "2.57.2" apply false // 2.57.x --> safe to update
}