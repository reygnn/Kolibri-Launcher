plugins {
    `kotlin-dsl`
}

group = "com.github.reygnn.launcher.buildlogic"

dependencies {
    // compileOnly on purpose: the plugins themselves come from the consuming build's
    // classpath (AGP 9 brings Kotlin along — "built-in Kotlin"). A second, runtime copy
    // here would load the Kotlin Gradle plugin twice.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "launcher.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "launcher.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidTest") {
            id = "launcher.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("androidApplication") {
            id = "launcher.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
