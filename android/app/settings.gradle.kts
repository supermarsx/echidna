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
        google()
        mavenCentral()
    }
}

rootProject.name = "EchidnaApp"
include(":app")
include(":interception-probe")

// Host the control service INSIDE this APK: pull the control-service Android
// library module into the app build. Plugin versions resolve from the root
// android/app/build.gradle.kts (AGP 8.2.2 / Kotlin 1.9.22, apply false), and its
// repositories flow through the dependencyResolutionManagement block above (the
// module declares no project-level repositories{}, so FAIL_ON_PROJECT_REPOS holds).
include(":service")
project(":service").projectDir = file("../control-service/service")
