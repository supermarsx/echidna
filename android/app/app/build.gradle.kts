import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Release signing inputs (never committed) ---------------------------------
// The release signing material is resolved, in priority order, from:
//   1. a git-ignored `keystore.properties` next to this app project
//      (see keystore.properties.example for the expected keys), then
//   2. Gradle properties / environment variables:
//      RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD.
// A real keystore or password is NEVER hardcoded or committed. When no keystore is
// available (local debug builds, CI without secrets) the release build gracefully
// falls back to debug signing (see buildTypes.release below) so the build still
// succeeds — it just produces a debug-signed, non-distributable APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

// Resolve a single signing value: keystore.properties first, then a Gradle property,
// then an environment variable of the given name.
fun releaseSigningValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)
        ?: (project.findProperty(envKey) as String?)
        ?: System.getenv(envKey)

val releaseStoreFile: String? = releaseSigningValue("storeFile", "RELEASE_STORE_FILE")
val hasReleaseKeystore: Boolean = releaseStoreFile != null

android {
    namespace = "com.echidna.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.echidna.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        vectorDrawables.useSupportLibrary = true

        // Instrumentation runner for the androidTest sources added by t2-e18.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The "release" config is always declared, but only populated when signing
        // material is actually available. Leaving storeFile null (no keystore) keeps
        // this config inert; buildTypes.release then falls back to debug signing so
        // the build never fails for a missing key.
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSigningValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Minification decision: DISABLED for the first release.
            // The app is heavy on reflection-sensitive entry points — AIDL stubs
            // (IEchidnaControlService), JNI (libechidna_control_jni), and Compose —
            // so shipping R8/resource-shrinking without a proven keep-rule set risks
            // silently stripping live code. Ship an unshrunk, signed release first;
            // enabling R8 (with a proguard-rules.pro keeping the AIDL/JNI/Compose
            // surfaces) is a deliberate follow-up.
            // TODO(release-hardening): enable isMinifyEnabled + proguard-rules.pro.
            isMinifyEnabled = false
            isShrinkResources = false

            // Sign with the release keystore when one is configured; otherwise fall
            // back to debug signing so CI/local builds without secrets still produce
            // an installable (debug-signed, non-distributable) APK.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        // AIDL is now owned solely by the :service library (single source of
        // truth); the app consumes the library's compiled IEchidnaControlService /
        // IEchidnaTelemetryListener via the project dependency below. Kept enabled
        // so any future app-local .aidl still compiles.
        aidl = true
    }

    composeOptions {
        // Compose compiler extension must match the Kotlin version. The project
        // now pins Kotlin 1.9.22 (see root build.gradle.kts, matching the other
        // two projects); 1.5.10 is the Compose compiler build for Kotlin 1.9.22.
        // (Was 1.5.7, which required Kotlin 1.9.21.)
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    testOptions {
        // Robolectric (used by t2-e18's JVM unit tests that touch Android framework
        // classes) needs merged Android resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Host the control service in-process: bundles EchidnaControlService, the
    // canonical AIDL, and the echidna_control_jni native lib into this APK.
    implementation(project(":service"))

    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Provides the Theme.Material3.* XML themes referenced by res/values/themes.xml
    // (Theme.Echidna extends Theme.Material3.DayNight.NoActionBar). 1.11.0 supplies
    // the Material3 theme set and is compatible with compileSdk 34 / AGP 8.2.2.
    implementation("com.google.android.material:material:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Test dependencies (consumed by t2-e18) -------------------------------
    // JVM unit tests (src/test): preset CRUD, import/export bundles, serializer
    // round-trip. Robolectric supports tests that touch Android framework classes.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.ext:junit:1.1.5")

    // Instrumentation tests (src/androidTest): profile-switch-while-hooked, tuner
    // accuracy, QS-tile persistence, service-binding. Compose UI test versions come
    // from the compose-bom already applied above (androidTestImplementation(composeBom)).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose test manifest (activity for createAndroidComposeRule) — already provided
    // by the debugImplementation("androidx.compose.ui:ui-test-manifest") line above.
}
