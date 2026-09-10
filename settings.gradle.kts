pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions a JDK matching the toolchain (21) so builds don't fail on
    // machines with a different system JDK. Inherited from both source repos.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "unity-launcher"

// Phase 0 (MONOREPO_MERGE_SPEC §6): both codebases mounted as-is under nested
// module paths, no code/namespace changes yet. Both apps build unchanged.
include(":core")
include(":kolibri:app")
include(":kolibri:domain")
include(":kolibri:data")
include(":kolibri:macrobenchmark")
include(":kolibri:baselineprofile")

include(":nyx:app")
include(":nyx:domain")
include(":nyx:data")
