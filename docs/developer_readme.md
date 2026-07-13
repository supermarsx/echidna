# Echidna Developer Guide

This guide covers how the Echidna sources build, how the pieces fit together on a device, the
release-signing model, and the known limitations you must design around. It reflects the state of
the tree after the Phase 1–3 remediation (buildable debug APK → runnable-on-device layout → signed
release scaffolding).

> **Status honesty (read this first).** The host build is verified on the development host
> (Android SDK, NDK r27, JDK 21, Gradle 8.5): the APK builds, all six per-ABI native `.so`
> cross-compile, the Magisk zip is packaged, and host DSP tests pass. Rooted Android 13/14
> emulators also prove the in-app control-service native `processBlock` path and one live
> `AudioRecord.read` interception slice. Magisk flashing, live LSPosed injection,
> physical-device SELinux/HAL behavior, and broader hook-manager coverage are still separate
> release-device validation. See
> [Status: verified vs. needs a device](#status-verified-vs-needs-a-device).

## Repository topology

```
android/
  app/               # Companion app (com.echidna.app). Hosts the control service in-process.
    app/             # The application module.
    settings.gradle.kts   # include(":service") with a projectDir redirect (see below).
  control-service/   # The :service Android library folded into the app build.
    service/         # EchidnaControlService, canonical AIDL, echidna_control_jni.
    magisk/          # SELinux/socket bootstrap scripts consumed by the Magisk packager.
  lsposed-shim/      # LSPosed/Xposed Java shim (reads the ProfileSyncBridge snapshot).
native/
  dsp/               # libech_dsp.so — the DSP engine (host-testable).
  zygisk/            # libechidna.so — the Zygisk module + audio hooks.
  CMakeLists.txt     # Aggregate that configures dsp + zygisk together for the NDK build.
tools/
  build_native_ndk.sh    # Per-ABI NDK cross-compile driver.
  build_magisk_module.sh # Flashable Magisk zip packager.
docker/              # Reproducible build/packaging helper images (see Docker helpers).
docs/                # This guide, the Magisk release guide, and the signing model.
```

## Control-plane topology (how the pieces fit)

The control service is hosted **inside the companion APK** — there is no separate installable
`com.echidna.control` app. This is the single most important architectural fact for anyone reading
the old sources: the phantom cross-package bind target and the duplicate/divergent AIDL are gone.

```
Companion app (com.echidna.app)
   │  binds ComponentName(context, EchidnaControlService::class.java)  — in-process, no permission
   ▼
EchidnaControlService (:service library, folded into the app build)
   │  JNI (libechidna_control_jni.so, packaged in the APK)
   ▼
libechidna.so  (Zygisk module, delivered by the Magisk module, dlopen'd at runtime)
   │  dlopen("libech_dsp.so") + process audio blocks
   ▼
libech_dsp.so  (DSP engine)

ProfileStore.buildSnapshotLocked() publishes a JSON snapshot
   {profiles, whitelist, appBindings, control}
   over an Ashmem region + AF_UNIX socket (/data/local/tmp/echidna_profiles.sock)
   │
   ▼
LSPosed shim reads that snapshot (it CANNOT bind the in-app service — it runs with the
host app's identity) and resolves per-app hook policy fail-closed from whitelist + appBindings.
```

Key consequences of the in-app topology:

- The service is declared `exported=false`; there is **no** `signature`-level
  `BIND_CONTROL_SERVICE` permission and no cross-package `<queries>` for the bind — those were
  self-referential once the service moved in-process and have been removed. (A `<queries>` LAUNCHER
  block remains, but for a different reason: the whitelist editor enumerates launchable packages via
  `PackageManager`, which Android 11+ package visibility requires.)
- The `:service` library is included into the app's single Gradle build via `include(":service")`
  plus a `project(":service").projectDir = file("../control-service/service")` redirect in
  `android/app/settings.gradle.kts`. The app declares `implementation(project(":service"))`, so the
  APK bundles `EchidnaControlService`, the canonical AIDL, and the `echidna_control_jni` native lib.
- The **canonical AIDL lives only** in `android/control-service/service/src/main/aidl/`. The app's
  old divergent copy was deleted; the app compiles against the library's complete interface.
- The LSPosed shim's per-app policy comes from the **published snapshot**, not a binder. The old
  phantom `ServiceManager` binder (`echidna_control` / `IControlService` / `getAppConfig`) has been
  removed entirely. See [Known limitations](#known-limitations) for the single-holder socket caveat.

## Building

### Prerequisites

| Tool | Pinned version | Notes |
| ---- | -------------- | ----- |
| JDK | 17 (build) / 21 (host JBR used to verify) | `compileOptions`/`kotlinOptions` target 17. |
| Android SDK | platform-34, build-tools 34 | `compileSdk`/`targetSdk` = 34 (root/sideload target). |
| Android NDK | r27 (`27.0.12077973`) | Per-ABI native cross-compile. |
| Gradle | 8.5 (wrapper) | Committed wrapper for all three Android projects. |
| CMake / Ninja | 3.x+ / 1.x | Host DSP tests and native configure. |

A committed Gradle **wrapper (8.5)** exists for `android/app`, `android/lsposed-shim`, and
`android/control-service`. Use `./gradlew` (not a system Gradle) for reproducible builds.

> The wrapper `gradle-wrapper.jar` binaries are generated by `gradle wrapper` on a Gradle host; if a
> checkout is missing them, run `gradle wrapper --gradle-version 8.5` in each project or use the
> `echidna/android-build` docker image.

### Debug APK

```sh
cd android/app
./gradlew clean :app:assembleDebug
# → android/app/app/build/outputs/apk/debug/app-debug.apk  (~20 MB)
```

This produces a debug-signed APK that bundles `libechidna_control_jni.so` for all default ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). It does **not** contain `libech_dsp.so` or the Zygisk
`libechidna.so` — those ship system-side in the Magisk module, not inside the app.

### Release APK

See [the signing model](signing.md). In short: supply keystore material via a git-ignored
`keystore.properties` or `RELEASE_*` environment variables, then:

```sh
cd android/app
./gradlew :app:assembleRelease
```

Without keystore material the release build falls back to debug signing so CI/local builds still
succeed (producing a non-distributable APK). Minification/resource-shrinking is intentionally
disabled for the first release (reflection-sensitive AIDL/JNI/Compose surfaces); enabling R8 with a
proven keep-rule set is a documented follow-up.

### Native per-ABI build (NDK)

The device libraries are cross-compiled per ABI by `tools/build_native_ndk.sh`, which configures the
`native/` aggregate once per ABI with the NDK toolchain file:

```sh
ANDROID_NDK=/path/to/android-ndk ANDROID_PLATFORM=android-26 \
  bash tools/build_native_ndk.sh
```

Output layout (consumed by the Magisk packager):

```
build/arm64-v8a/lib/libech_dsp.so     build/arm64-v8a/lib/libechidna.so
build/armeabi-v7a/lib/libech_dsp.so   build/armeabi-v7a/lib/libechidna.so
build/x86_64/lib/libech_dsp.so        build/x86_64/lib/libechidna.so
```

ABI set: **arm64-v8a (primary)**, `armeabi-v7a`, `x86_64`. Environment overrides: `ECHIDNA_ABIS`,
`ANDROID_PLATFORM`, `ECHIDNA_BUILD_TYPE`, `ECHIDNA_CMAKE_GENERATOR`, `ECHIDNA_CMAKE_EXTRA_ARGS`,
`CMAKE`; the NDK path also resolves from `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`.

**BoringSSL / plugin signature verify.** The DSP build links crypto in a layered, first-hit-wins
order so the Ed25519 plugin-signature path (`ECHIDNA_HAS_BORINGSSL`) is not silently off on device:
(1) a prebuilt install via `-DECHIDNA_BORINGSSL_ROOT=<dir>`; (2) a toolchain/system `libcrypto`;
(3) FetchContent BoringSSL (Android-only, default on, pinned tag `0.20240913.0`, nothing vendored
into the repo). If none resolve, the loader stays **fail-closed** and logs a loud warning.

> **Build-hygiene note.** The FetchContent BoringSSL sub-build compiles its own large test suite
> even with `-DBUILD_TESTING=OFF` (BoringSSL doesn't gate on the standard flag). It succeeds but
> roughly triples native build time; restrict to the `ech_dsp`/`echidna` targets or disable the
> sub-build's tests to speed CI.

### Host DSP tests

The DSP engine is host-testable (no device needed):

```sh
cmake -S native -B build/native-tests -G Ninja -DBUILD_TESTING=ON
cmake --build build/native-tests --target \
  dsp_preset_test dsp_engine_test dsp_effects_test zygisk_dsp_smoke_test \
  capture_buffer_router_test
ctest --test-dir build/native-tests --output-on-failure
```

Native tests are gated `if(BUILD_TESTING AND NOT ANDROID)` so a cross-compile never pulls host
tests into the device build.

### Docker helper images

`docker/` provides reproducible, offline-capable images so contributors do not need a local SDK/NDK
and CI can produce the device artifacts the host cannot. Orchestration lives in `docker/compose.yaml`
(see `docker/README.md` for the checksum-confirmation notes); the repo is bind-mounted at
`/workspace` rather than copied, so a source change never requires an image rebuild.

| Image | Purpose |
| ----- | ------- |
| `echidna/native-build` | Temurin JDK 17 + pinned CMake 3.30.5 / Ninja 1.12.1 + NDK r27 (`27.0.12077973`); runs `build_native_ndk.sh` to cross-compile both libs for every ABI. |
| `echidna/android-build` | Temurin JDK 17 + Android SDK platform-34 / build-tools 34.0.0 (pre-accepted licenses) + pinned Gradle 8.5; builds the APK offline (also regenerates the wrapper jar if absent). |
| `echidna/magisk-packager` | Alpine + `zip` (+ docker-cli); consumes the per-ABI libs and runs `build_magisk_module.sh` to lay out the flashable zip. |
| `echidna/ci-local` | Orchestrates native-build → magisk-packager → android-build to reproduce the pipeline locally before pushing (drives the host Docker daemon). |
| `echidna/emulator` *(optional)* | KVM Android emulator to host the instrumentation/E2E tests on a real ART runtime (requires `/dev/kvm`). |

All downloaded toolchain archives are checksum-pinned in the Dockerfiles. `docker/compose.yaml` is
the source of truth for service names and mounts.

Build the images first (add `--profile ci` / `--profile emulator` to include those services), then
run individual stages or the whole pipeline:

```sh
docker compose -f docker/compose.yaml build

# (a) per-ABI native libs → build/<abi>/lib/{libech_dsp,libechidna}.so
docker compose -f docker/compose.yaml run --rm native-build
# (b) debug APK (offline after the first Gradle dep fetch; cached in a volume)
docker compose -f docker/compose.yaml run --rm android-build
# (c) flashable Magisk zip (consumes the native-build output) → out/echidna-magisk.zip
docker compose -f docker/compose.yaml run --rm magisk-packager
# (d) full pipeline (needs the host Docker socket; pass the host-absolute repo path)
ECHIDNA_REPO="$PWD" docker compose -f docker/compose.yaml --profile ci run --rm ci-local
# (optional) emulator for instrumentation/E2E (needs /dev/kvm)
docker compose -f docker/compose.yaml --profile emulator run --rm emulator
```

`ci-local` and `emulator` sit behind compose profiles (`ci` / `emulator`) so they do not run by
default.

> This section describes the images authored in Phase 3 (executor t2-e16). **Image builds/runs are
> not verified on the build host** (no Docker daemon — static-validated only). The first
> `android-build` run needs network for Gradle dependencies, and the cmdline-tools checksum
> (`CMDLINE_TOOLS_SHA256`) should be confirmed against Google's published value (overridable; the
> download fails closed on mismatch). See `docker/README.md`.

## Native Control API

The C ABI exported by `libechidna.so` is declared in `native/include/echidna_api.h`. The public
entry points are:

- `uint32_t echidna_api_get_version(void)` exposes a packed `MAJOR.MINOR.PATCH` version to guard
  against ABI drift.
- `echidna_result_t echidna_set_profile(const char *profile_json, size_t length)` updates the
  active routing profile using the preset JSON schema.
- `echidna_result_t echidna_process_block(const float *input, float *output, uint32_t frames,
  uint32_t sample_rate, uint32_t channel_count)` feeds captured audio into the DSP pipeline.
- `echidna_status_t echidna_get_status(void)` reports the internal hook state.

`echidna_result_t` enumerates standard error codes:

| Code | Meaning |
| ---- | ------- |
| `ECHIDNA_RESULT_OK` | Request completed successfully. |
| `ECHIDNA_RESULT_ERROR` | Unexpected runtime failure. |
| `ECHIDNA_RESULT_INVALID_ARGUMENT` | One or more arguments were rejected. |
| `ECHIDNA_RESULT_NOT_INITIALISED` | The DSP stack has not been initialised. |
| `ECHIDNA_RESULT_PERMISSION_DENIED` | Caller lacks the necessary privileges. |
| `ECHIDNA_RESULT_NOT_SUPPORTED` | Feature is disabled on the current build. |
| `ECHIDNA_RESULT_SIGNATURE_INVALID` | Plugin or payload failed signature validation. |
| `ECHIDNA_RESULT_NOT_AVAILABLE` | The control surface could not be reached. |

`echidna_status_t` mirrors the shared state flags used by the hook managers and the companion app.

### Zygisk module registration

`libechidna.so` is a genuine Zygisk module: `native/zygisk/include/zygisk.hpp` provides the Zygisk
API v4 contract, and `native/zygisk/src/module.cpp` defines `class EchidnaModule : public
zygisk::ModuleBase` with `REGISTER_ZYGISK_MODULE(EchidnaModule)`. The hook lifecycle is driven from
`postAppSpecialize` (when `/proc/self/cmdline` reflects the target app), which calls
`echidna_module_attach()` → creates the `ProfileSyncServer` + `AudioHookOrchestrator` → installs
hooks in priority order (AAudio → OpenSL → AudioFlinger → AudioRecord → libc read → tinyalsa → audio
HAL). Hooking stays gated on `hooksEnabled() && isProcessWhitelisted(process)`.

**ABI support.** `arm64-v8a` is the locked primary and fully implemented. `x86_64` has a complete
inline-hook trampoline (host-harness verified; needs emulator confirmation). `armeabi-v7a` builds
and loads but **gracefully degrades** — `install()` returns false and emits a `hook_unsupported_abi`
log signal (Thumb-2/IT-block relocation is not implemented). Plan accordingly for armv7 devices.

## Safety Watchdog

The native DSP bridge implements an auto-bypass watchdog for sustained overruns as described in
spec: [12](https://github.com/supermarsx/echidna/blob/main/spec.md#12-safety--emergency-ux-native-risks). When callback processing exceeds the
threshold for N consecutive blocks, the process enters bypass mode for a short cooldown window.

Runtime tuning is available via environment variables:

- `ECHIDNA_WATCHDOG_US` sets the per-callback overrun threshold in microseconds.
- `ECHIDNA_WATCHDOG_CONSEC` sets the consecutive overrun count needed to trigger auto-bypass.
- `ECHIDNA_BYPASS_MS` sets the auto-bypass cooldown duration in milliseconds.
- `ECHIDNA_PANIC_MS` sets the bypass duration used by the Java panic toggle (0 = manual).

Bypassed callbacks set telemetry flags and increment XRuns in shared memory for diagnostics.

## Control Service Binder Surface

The control service exposes `IEchidnaControlService` over Binder. Because the service is hosted
**in-process** inside the companion app, binding uses an in-package `ComponentName` and requires no
permission (the old `signature`-level `BIND_CONTROL_SERVICE` grant was removed as self-referential).

Core methods:

- `void setProfile(String profile)` → resolves the profile ID to JSON, then wraps
  `echidna_set_profile`. Null or empty profiles are ignored.
- `int getStatus()` → forwards `echidna_get_status`.
- `int processBlock(float[] input, float[] output, int frames, int sampleRate, int channelCount)` →
  forwards `echidna_process_block`. When `output` is null the call is treated as a monitor tap.
- `long getApiVersion()` → returns the packed API version via `echidna_api_get_version`.

Control/status methods added during the topology unification:

- `String getModuleStatus()` / `String refreshStatus()` → real combined status JSON
  (`magiskModuleInstalled`, `zygiskEnabled`, `selinuxState`/`selinuxStatus`, and a live `audioStack`
  HAL probe). This replaces the app's previously fabricated `nativeInstalled/active/Enforcing`
  status and the compat-wizard's hardcoded "Qualcomm QSSI"/"Enforcing" fallback.
- `String getWhitelistBindings()` → read-back JSON `{"whitelist":{proc:bool},"appBindings":{pkg:presetId}}`.
- `void setMasterEnabled(boolean)`, `void setBypass(boolean)`, `void triggerPanic(long holdMs)`,
  `void setSidetone(boolean enabled, float gainDb)`, `void setEngineMode(String engineMode)`,
  `String getControlState()` → global control, persisted and pushed into the snapshot `control`
  object. Compatibility engine mode disables native hook admission while preserving the Java shim
  fallback path.

The service loads `libechidna.so` lazily via JNI (`echidna_control_jni`). If the shared library is
missing the binder methods return `ECHIDNA_RESULT_NOT_AVAILABLE` and the status is forced to
`ECHIDNA_STATUS_ERROR` to fail closed.

## DSP Plugin Schema

`libech_dsp.so` discovers signed plugins from the directory referenced by the `ECHIDNA_PLUGIN_DIR`
environment variable (defaulting to `/data/local/tmp/echidna/plugins`). Plugins must ship two files:

1. `<name>.so` — a shared object exporting `const echidna_plugin_module_t *echidna_get_plugin_module()`.
2. `<name>.so.sig` — a 64 byte Ed25519 signature over the raw `.so` payload, hex encoded using the
   trusted public key baked into the loader.

The module descriptor returned by `echidna_get_plugin_module()` must populate:

- `abi_version` → currently `ECHIDNA_DSP_PLUGIN_ABI_VERSION` (1).
- `descriptors` / `descriptor_count` → a table of effect descriptors.

Each `echidna_plugin_descriptor_t` entry describes a DSP effect:

- `identifier` (required) → unique, stable key.
- `display_name` (optional) → user friendly label.
- `version` → plugin specific semantic version.
- `flags` → bitfield (`ECHIDNA_PLUGIN_FLAG_DEFAULT_ENABLED` enables the effect after load).
- `create` → returns an `echidna::dsp::effects::EffectProcessor` instance.
- `destroy` → releases the instance allocated by `create`.

The loader validates signatures with the built-in Ed25519 public key before calling `dlopen`. The
trusted key is a **build-provisioned** compile definition (`ECHIDNA_TRUSTED_PLUGIN_PUBKEY`) with an
all-zero fail-closed placeholder — provide a real key at build time to enable third-party plugins.
Signature verify is only active when `ECHIDNA_HAS_BORINGSSL` is defined (see the native build). Valid
plugins are prepared and reset whenever the DSP engine reapplies presets, and are inserted into the
processing chain immediately before the mix bus so they receive the fully conditioned wet signal.

## Known limitations

### Profile-sync socket is single-holder

The profile/telemetry sync path uses a filesystem `AF_UNIX` socket at
`/data/local/tmp/echidna_profiles.sock`. Both the native `ProfileSyncServer` (inside the Zygisk
module) and the LSPosed shim's `ProfileSyncReceiver` bind that **same** path with an unlink-then-bind
("last writer wins") sequence, and the server is started **per app process**. Consequences:

- **Do not run the Zygisk module and the LSPosed shim simultaneously** on the same device — they
  contend for the one socket path.
- **With multiple hooked apps, only the last binder receives pushes;** every other hooked process
  stays fail-closed (no snapshot → hooking denied) until the next mutation-triggered push.
- **Freshness:** the service pushes only on mutation and on startup `loadFromDisk`. A process that
  binds after the last push has no snapshot until the next mutation, and stays fail-closed until then.

This is inherited from the native contract and is out of scope for the shim. The proper fix is a
native/service redesign — e.g. a **serve-last-snapshot** model where the server replies with the
current snapshot to each connecting reader, or a **world-readable published snapshot** file that any
hooked process can read. Tracked as a future item in [todo.md](https://github.com/supermarsx/echidna/blob/main/todo.md).

### armeabi-v7a hooking degrades

As noted above, armv7 builds and loads but does not install hooks (graceful degrade with a telemetry
signal). arm64 is the primary target.

## Status: verified vs. needs a device

**Host-build-verified (on the development host, from clean):**

- Debug APK compiles (~20 MB); packages the `:service` JNI for four ABIs; the merged manifest carries
  the `<queries>` block and the in-app `EchidnaControlService` (app↔service topology intact).
- All six per-ABI native artifacts (`libech_dsp.so` + `libechidna.so` × arm64-v8a/armeabi-v7a/x86_64)
  cross-compile and link with the correct ELF architecture.
- Host DSP unit tests (preset + engine) pass; the BoringSSL Android cross-build compiles the Ed25519
  verify path.
- The C/C++ format gate is clean tree-wide.

**Runtime-verified on emulators:**

- App instrumentation passes **13/13** on rooted Android 13 and Android 14 x86_64 emulators,
  including the in-APK service bind and `processBlockAppliesPresetWhenNativeEngineIsAvailable`.
- The native `processBlock` instrumentation test applies a real preset and asserts the output is
  finite and measurably changed when `libechidna.so` and `libech_dsp.so` are reachable.
- `:interception-probe:connectedDebugAndroidTest` passes **1/1** on the same rooted emulators:
  a real `AudioRecord.read` call emits current-process hook evidence with `processed=1`.
- Earlier stock-emulator coverage still proves install, launch, navigation, fallback UI state, and
  the in-app service/AIDL round-trip without root.

**Still release-device-only / NOT verified here:**

- Magisk Manager or `magisk --install-module` flashing, reboot, module-manager load, and
  magic-mount namespace behavior. The emulator `magisk --install-module` attempt returned
  `Incomplete Magisk install`.
- Live LSPosed shim injection into target apps, LSPosed scoping, and snapshot reads under SELinux.
- Physical-device Zygisk lifecycle and hook installation on the arm64 primary path.
- AAudio, OpenSL ES, AudioFlinger, tinyalsa, and HAL-level hook managers in live app processes.
- On-device SELinux enforcement and vendor audio-HAL behavior.
- armeabi-v7a graceful-degrade at runtime.

Echidna is a root/sideload application; on-device validation is a required, separate step before any
release is considered functional.
