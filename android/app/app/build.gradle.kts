import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipFile

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

// --- Turnkey engine module bundle --------------------------------------------
// The flashable all-ABI Magisk module zip is produced OUTSIDE the APK build by the
// existing native tooling (tools/build_native_ndk.sh + tools/build_magisk_module.sh)
// into <repo>/out/echidna-magisk.zip. The stageEngineModuleAsset task (below) copies
// it into a build-generated assets directory that is wired onto the main source set,
// so the in-app installer (EngineModuleArchive) can install it directly ("turnkey").
//
// Both the source zip (/out/) and this generated location (under the Gradle build dir)
// are gitignored — a ~15 MB per-ABI binary is NEVER committed. When the zip is absent
// (a "lite" build produced without running the native tooling) nothing is staged and
// the installer falls back to the SAF .zip picker, exactly as EngineModuleArchive
// already handles a missing asset.
//
// APK size / ABI note: the bundled zip carries all three module ABIs (arm64-v8a,
// armeabi-v7a, x86_64) because a Magisk module is installed onto the device and Magisk
// selects the matching ABI at runtime, so the zip must be self-contained. This roughly
// doubles the universal APK (~21 MB -> ~37 MB). Gradle ABI *splits* partition only the
// APK's own JNI libs, not shared assets, so a split cannot carry a different per-ABI zip
// without product flavors; to avoid changing the assembleDebug/Release task surface that
// the rest of the app build depends on, this ships a single universal build with the full
// bundle and documents the tradeoff. A per-ABI "lite vs full" flavor split is a follow-up.
val engineModuleZip: java.io.File = rootProject.file("../../out/echidna-magisk.zip")
val engineModuleAssetsDir = layout.buildDirectory.dir("generated/echidna/assets")
val engineModuleAssetName = "echidna-magisk.zip"

// --- Lab in-process DSP engine (libech_dsp.so) -------------------------------
// The "Lab" tab runs the app's OWN mic/test-tone audio through the REAL DSP
// engine in-process (no Zygisk, no root) via the echidna_lab_jni bridge built by
// externalNativeBuild below. That bridge dlopens libech_dsp.so, so the DSP engine
// must be packaged into this APK's jniLibs. The per-ABI .so are produced OUTSIDE
// the APK build by tools/build_native_ndk.sh into <repo>/build/<abi>/lib/libech_dsp.so
// (a multi-MB binary, gitignored — never committed). The stageLabDspJniLibs task
// mirrors them into a build-generated jniLibs dir wired onto the main source set.
//
// Honest about absence: when the engine .so has NOT been built (a "lite" APK from
// a fresh checkout / CI without the native step) nothing is staged and the Lab
// reports "DSP engine unavailable" instead of the build failing — exactly as the
// EchidnaLabDsp bridge already handles a failed dlopen.
val labDspNativeBuildRoot: java.io.File = rootProject.file("../../build")
val labDspAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val labDspJniLibsDir = layout.buildDirectory.dir("generated/echidna/labJniLibs")

// --- In-app Help: bundle the repository docs at build time ---------------------
// The Help screen renders the repository's own Markdown documentation offline. Rather
// than hardcode a file list (which would silently rot as docs are added/removed), the
// stageHelpDocs Sync task below mirrors every `docs/**/*.md` (top-level plus subtrees
// like docs/hardening/** and docs/validation/**) into a build-generated assets dir at
// `assets/help/docs/<same relative path>`. The Help repository lists that asset subtree
// at runtime, so the in-app Help always matches whatever docs exist at build/merge time
// (e.g. a docs/recovery.md added on another branch is picked up automatically — no code
// change). The generated dir lives under the Gradle build output (gitignored), so no
// documentation copies are ever committed as assets.
val helpDocsSourceDir: java.io.File = rootProject.file("../../docs")
val helpDocsAssetsDir = layout.buildDirectory.dir("generated/echidna/helpAssets")
// Relative asset path (under assets/) the docs are mirrored into; the app reads the same constant.
val helpDocsAssetSubdir = "help/docs"

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

        // Constrain to the three ABIs the DSP engine (libech_dsp.so) ships for, so the
        // Lab bridge (echidna_lab_jni), the control JNI, and the DSP engine are packaged
        // for the same set of ABIs. Modern emulators are x86_64; legacy x86 is dropped.
        ndk {
            abiFilters += labDspAbis
        }
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

    // Build the Lab in-process DSP bridge (libechidna_lab_jni.so) for each ABI.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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

    // Stage the generated engine-module archive (see stageEngineModuleAsset below) onto the main
    // assets source set so it lands in the APK at the exact name EngineModuleArchive expects
    // (echidna-magisk.zip in the assets root). The directory is under the Gradle build output
    // (gitignored) so no ~15 MB binary is ever tracked.
    sourceSets {
        getByName("main").assets.srcDir(engineModuleAssetsDir)
        // Stage the DSP engine .so (libech_dsp.so) into the APK's jniLibs so the Lab
        // bridge can dlopen it in-process. Empty on a lite build (Lab reports unavailable).
        getByName("main").jniLibs.srcDir(labDspJniLibsDir)
        // Stage the repository Markdown docs into the APK assets so the in-app Help renders
        // them offline. Directory lives under the (gitignored) build output — see stageHelpDocs.
        getByName("main").assets.srcDir(helpDocsAssetsDir)
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
    // Deterministic dispatcher control for LabViewModel's coroutine-driven processing test.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // Compose UI test under Robolectric (JVM) for the DismissibleAlert component tests added by
    // t8-e4: dismiss hides + persists, action button invokes onAction, dismiss-only renders.
    // Versions come from the compose-bom applied to the test classpath below.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

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

// Stage the bundled engine module zip into the generated assets dir at build time. Runs before any
// asset merge so the archive is packaged into the APK. Honest about absence: with no zip present it
// clears the asset (lite build) instead of failing the build.
val stageEngineModuleAsset = tasks.register("stageEngineModuleAsset") {
    description = "Stages the flashable Echidna Magisk module zip into the APK assets when present."
    group = "echidna"
    val srcZip = engineModuleZip
    val destDirProvider = engineModuleAssetsDir
    // Re-run when the source appears/disappears or its contents change.
    inputs.property("zipPresent", srcZip.isFile)
    inputs.property("zipLength", if (srcZip.isFile) srcZip.length() else 0L)
    inputs.property("zipModified", if (srcZip.isFile) srcZip.lastModified() else 0L)
    outputs.dir(destDirProvider)
    doLast {
        val destDir = destDirProvider.get().asFile
        destDir.mkdirs()
        val dest = destDir.resolve(engineModuleAssetName)
        if (srcZip.isFile) {
            srcZip.copyTo(dest, overwrite = true)
            logger.lifecycle(
                "stageEngineModuleAsset: bundled ${srcZip.length()} bytes -> assets/$engineModuleAssetName (turnkey install)"
            )
        } else {
            dest.delete()
            logger.lifecycle(
                "stageEngineModuleAsset: no zip at ${srcZip.path} — building a lite APK (installer uses the SAF .zip picker). " +
                    "Run tools/build_native_ndk.sh + tools/build_magisk_module.sh to produce it."
            )
        }
    }
}

// Asset-merge tasks (mergeDebugAssets / mergeReleaseAssets / test variants) must see the staged
// archive, so make every asset merge depend on the staging task.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(stageEngineModuleAsset)
}
// preBuild dependency covers any variant/tooling path that reads assets before an explicit merge.
tasks.named("preBuild").configure { dependsOn(stageEngineModuleAsset) }

// Stage the DSP engine .so (libech_dsp.so) into a generated jniLibs dir so it is packaged
// alongside the Lab bridge and dlopen-able in-process. Sync so stale/partial ABIs are pruned.
// Never fails the build when the engine has not been built (lite APK): it simply stages nothing
// and the Lab reports "DSP engine unavailable", matching EchidnaLabDsp's honest dlopen fallback.
val stageLabDspJniLibs = tasks.register<Sync>("stageLabDspJniLibs") {
    description = "Stages the per-ABI libech_dsp.so into the APK jniLibs for the in-process Lab DSP test."
    group = "echidna"
    into(labDspJniLibsDir)
    // A canonical owned mirror, not a cache — always run so a removed engine .so is pruned.
    outputs.upToDateWhen { false }
    labDspAbis.forEach { abi ->
        from(File(labDspNativeBuildRoot, "$abi/lib")) {
            include("libech_dsp.so")
            into(abi)
        }
    }
    doLast {
        val staged = labDspAbis.count { abi ->
            File(labDspNativeBuildRoot, "$abi/lib/libech_dsp.so").isFile
        }
        if (staged == 0) {
            logger.lifecycle(
                "stageLabDspJniLibs: no libech_dsp.so under ${labDspNativeBuildRoot.path}/<abi>/lib — " +
                    "building a lite APK (Lab tab reports the DSP engine as unavailable). " +
                    "Run tools/build_native_ndk.sh to bundle the in-process engine."
            )
        } else {
            logger.lifecycle("stageLabDspJniLibs: staged libech_dsp.so for $staged/${labDspAbis.size} ABIs")
        }
    }
}

// The jniLibs merge must see the staged engine .so, so make every jniLibs merge depend on staging.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(stageLabDspJniLibs)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(stageLabDspJniLibs)
}
tasks.named("preBuild").configure { dependsOn(stageLabDspJniLibs) }

// Mirror the repository Markdown docs (and the image assets they reference) into the generated
// Help assets dir at build time. Sync (not Copy) so a doc/image removed from docs/ is pruned from
// the bundle — the in-app Help is a canonical mirror of the current repo docs, never a stale
// accumulation. Fully dynamic: every *.md under docs/ (recursively) plus every png/webp/svg under
// docs/assets/ is included, preserving relative paths so a doc's `![](assets/screenshots/x.png)`
// (top-level) and `![](../assets/x.png)` (from docs/hardening/) both resolve to the same staged
// asset inside the APK. Honest about absence: if docs/ is missing the task simply stages nothing.
val stageHelpDocs = tasks.register<Sync>("stageHelpDocs") {
    description = "Stages the repository docs/**/*.md and referenced image assets into the APK assets for the in-app Help screen."
    group = "echidna"
    into(helpDocsAssetsDir.map { it.dir(helpDocsAssetSubdir) })
    if (helpDocsSourceDir.isDirectory) {
        from(helpDocsSourceDir) {
            include("**/*.md")
            // Raster image assets the Help renderer can decode inline (PNG/WebP) plus SVG, which the
            // renderer degrades to a caption but is still staged so a future/web-only reference resolves.
            include("assets/**/*.png")
            include("assets/**/*.webp")
            include("assets/**/*.svg")
        }
    }
    // A canonical owned mirror, not a cache — always run so added/removed docs are reflected.
    outputs.upToDateWhen { false }
    doLast {
        val root = helpDocsAssetsDir.get().dir(helpDocsAssetSubdir).asFile
        val docs = root.walkTopDown().count { it.isFile && it.extension == "md" }
        val images = root.walkTopDown()
            .count { it.isFile && it.extension.lowercase() in setOf("png", "webp", "svg") }
        logger.lifecycle("stageHelpDocs: bundled $docs Markdown doc(s) + $images image asset(s) -> assets/$helpDocsAssetSubdir/")
    }
}

// Asset merges (incl. the unit-test and androidTest variants) must see the staged docs.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(stageHelpDocs)
}
tasks.named("preBuild").configure { dependsOn(stageHelpDocs) }

tasks.register("verifyDebugNativePackaging") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apk.isFile) { "Missing companion debug APK: $apk" }

        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        // The control JNI and the in-process Lab DSP bridge must always be packaged for
        // every ABI. The DSP engine (libech_dsp.so) is required too when the native engine
        // has been built (full APK); a lite APK omits it and the Lab reports it unavailable.
        val requiredBridges = abis.flatMap { abi ->
            listOf(
                "lib/$abi/libechidna_control_jni.so",
                "lib/$abi/libechidna_lab_jni.so",
            )
        }.toSet()
        val packaged = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/") }
                .map { it.name }
                .toSet()
        }
        check(packaged.containsAll(requiredBridges)) {
            "Companion native package missing bridge libraries. Expected all of $requiredBridges but found $packaged"
        }
        // The DSP engine, when present, must be packaged consistently for all ABIs so the
        // Lab works on whichever ABI the device runs.
        val dspAbis = abis.filter { abi -> packaged.contains("lib/$abi/libech_dsp.so") }
        check(dspAbis.isEmpty() || dspAbis.size == abis.size) {
            "libech_dsp.so packaged for an inconsistent ABI subset ($dspAbis); expected none or all of $abis"
        }
        // The Zygisk module and the LSPosed shim JNI must NEVER leak into the app APK. The DSP
        // engine (libech_dsp.so) is intentionally allowed here — it is the in-process Lab engine.
        val forbidden = setOf(
            "libechidna.so",
            "libechidna_shim_jni.so",
        )
        check(packaged.none { entry -> forbidden.any(entry::endsWith) }) {
            "A Zygisk/shim library leaked into the companion APK: $packaged"
        }
    }
}
