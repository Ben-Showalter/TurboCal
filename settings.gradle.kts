pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Only Android/AndroidX artifacts come from Google's repo, so a :core-only
        // build never needs to reach it.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.testing\\..*")
            }
        }
    }
}

rootProject.name = "TurboCal"

include(":core")

// -Pturbocal.jvmOnly=true builds and tests :core without the Android SDK
// (e.g. on a machine or CI runner that only has a JDK). See AGENTS.md §6.
if (providers.gradleProperty("turbocal.jvmOnly").orNull != "true") {
    include(":app")
}
