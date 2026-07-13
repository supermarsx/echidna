// Root build script for the Echidna companion app project.
//
// The :app module (app/build.gradle.kts) applies the Android + Kotlin plugins
// WITHOUT a version; the versions are pinned here so the plugin ids resolve.
// These are deliberately kept in lockstep with the other two Gradle projects in
// this repo — android/control-service and android/lsposed-shim both pin
// AGP 8.2.2 (com.android.tools.build:gradle:8.2.2) and Kotlin 1.9.22 — so the
// three projects share one toolchain. Repositories that host these plugins
// (google(), gradlePluginPortal()) are declared in settings.gradle.kts's
// pluginManagement block.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
