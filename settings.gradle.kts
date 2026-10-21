pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Central first: it holds the Kotlin libraries, and Google Maven is only asked for what Central lacks.
        mavenCentral()
        google()
    }
}

rootProject.name = "GymQuest"

include(":core")
include(":app")
