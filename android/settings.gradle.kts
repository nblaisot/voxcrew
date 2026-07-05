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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Silero VAD (on-device neural voice activity detection) is only published on
        // JitPack — see https://github.com/gkonovalov/android-vad. Scoped to the one
        // group we trust from this repository.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.gkonovalov.android-vad") }
        }
    }
}

rootProject.name = "VoxCrew"
include(":app")
