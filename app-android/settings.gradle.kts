// The Android app needs Google's Maven repository. Pass -PcoreOnly to build just
// the pure-Kotlin :core module where that repository is not reachable.
val coreOnly = providers.gradleProperty("coreOnly").isPresent

pluginManagement {
    repositories {
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}

rootProject.name = "agentring-android"
include(":core")
if (!coreOnly) {
    include(":app")
}
