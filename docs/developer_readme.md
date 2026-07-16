# Echidna Developer Guide

This guide covers how the Echidna sources build, how the pieces fit together on a device, the
release-signing model, and the known limitations you must design around. Android capture-path
interception is a very hard, device-specific problem; Echidna is likely not to work on many phones
even when every artifact builds and installs correctly. This guide reflects the state of the tree
after the Phase 1–3 remediation (buildable debug APK → runnable-on-device layout → signed release
scaffolding).

> **Status honesty (read this first).** The host build is verified on the development host
> (Android SDK, NDK r27, JDK 21, Gradle 8.5): the companion and shim APKs build, the native
> superbuild generates four libraries per ABI, all 12 native outputs have a release transport, and
> host DSP/effect tests pass. `libechidna_preproc.so` is packaged for three ABIs and can be registered
> for the next boot on proven legacy-HIDL system/vendor configurations. It remains default-off; an
> experimental companion setting only permits authorized LSPosed per-session attachment and is not
> device load, enablement, or processing proof. Android 14/15 OEM source adapters for the Stable
> AIDL effect factory are also present and source-contract checked, but have not been built in an
> OEM Soong tree or proven by effect VTS/device FMQs. Rooted Android
> 13/14 emulators also prove the in-app control-service native `processBlock` path. A native
> `AudioRecord.read` interception slice passed before the current explicit-contract redesign and
> is historical evidence only. Magisk flashing, live LSPosed injection, current capture routes,
> and physical-device SELinux behavior are still separate
> release-device validation. Treat a successful build/install as artifact proof only, not as a
> guarantee that the target phone can run Echidna safely or effectively. See
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
  lsposed-shim/      # Installable LSPosed/Xposed Java shim APK.
native/
  dsp/               # libech_dsp.so — the DSP engine (host-testable).
  effects/aidl/      # OEM Stable AIDL AudioEffect V1/V2 source adapters and gate.
  effects/legacy/    # libechidna_preproc.so — default-off legacy input-effect boundary.
  zygisk/            # libechidna.so — the Zygisk module + audio hooks.
  CMakeLists.txt     # Aggregate that configures DSP, preprocessor, and Zygisk targets.
tools/
  build_native_ndk.sh    # Per-ABI NDK cross-compile driver.
  build_magisk_module.sh # Flashable Magisk zip packager.
  analyze_audio_hal_dump.py # Read-only firmware/device dump analyzer for HAL profiles.
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
   ▼ device-gated lookup; fails closed when the module library is unreachable
libechidna.so  (Zygisk module, delivered by the Magisk module)
   │  dlopen("libech_dsp.so") + process audio blocks
   ▼
libech_dsp.so  (DSP engine)

ProfileStore publishes strict policy v2 into PublishedPolicyRegistry
   {schemaVersion, generation, profiles, defaultProfileId, appBindings,
    whitelist, captureOwners, control, appIdentities}
   ├─ Zygisk: authenticated, process-scoped abstract AF_UNIX frames
   └─ LSPosed: authenticated, process-scoped read-only Binder snapshot
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
- The privileged control AIDL lives only in
  `android/control-service/service/src/main/aidl/`; the app's old divergent copy was deleted. The
  narrow exported policy-provider AIDL is mirrored into `android/lsposed-shim` because the shim is a
  separate build. Its method order is append-only and an automated contract check keeps both copies
  identical.
- The old phantom `ServiceManager` binder (`echidna_control` / `IControlService` / `getAppConfig`)
  remains removed. The current `PolicySnapshotService` is a real explicit component with a separate
  narrow AIDL: it is exported only for read-only, caller-UID/process-authenticated LSPosed policy.
  The privileged `EchidnaControlService` remains non-exported.

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

### Release APKs

See [the signing model](signing.md). In short: supply keystore material via a git-ignored
`keystore.properties` or `RELEASE_*` environment variables, then:

```sh
cd android/app
./gradlew :app:assembleRelease
```

The companion can build without the native NDK outputs. The shim intentionally cannot: first run
the native build below, then build the APK so Gradle can package exactly the dedicated shim JNI
bridge and DSP dependency:

```sh
cd android/lsposed-shim
./gradlew :shim:assembleRelease
```

Without keystore material, direct local Gradle release builds fall back to debug signing
(producing non-distributable APKs). The hosted release workflow does not use that fallback: it
validates a complete keystore/private-key entry and certificate pin before creating an automatic
tag, then verifies every APK and bundle before publication. Minification/resource-shrinking is intentionally
disabled for the first release (reflection-sensitive AIDL/JNI/Compose/LSPosed entry points);
enabling R8 with a proven keep-rule set is a documented follow-up.

The normalized release certificate pin also gates the Magisk trust bootstrap. Release tooling
builds a module-owned API-26-compatible Dex helper, embeds the pin, and refuses missing or
debug-only production inputs. At late-start on API 26–33, the helper verifies PackageManager's
current `com.echidna.app` signer, user-0 UID/dataDir, and app-owned P-256 SPKI before staging an
inert root-owned next-boot pin. A shared fail-closed helper then validates the canonical 91-byte
P-256 SPKI pair and the per-install telemetry HMAC pair before trust bootstrap reports success.
The SPKI uses root:root `0444` and `echidna_controller_spki_file`; the derived HMAC copy uses
root:audio `0440` and `echidna_telemetry_key_file`. Each derived file must match its authoritative
pin by hash and retain stable inode metadata across relabelling. A one-sided pair, unsafe link,
mode drift, or label mismatch
removes only the unsafe derived copy and refuses exposure. The release ZIP ships neither generated
trust input. These host-verified contracts do not prove effect-host access on an enforcing device.

### Native per-ABI build (NDK)

The device libraries are cross-compiled per ABI by `tools/build_native_ndk.sh`, which configures the
`native/` aggregate once per ABI with the NDK toolchain file:

```sh
ANDROID_NDK=/path/to/android-ndk ANDROID_PLATFORM=android-26 \
  bash tools/build_native_ndk.sh
```

Output layout (consumed by the Magisk packager):

```
build/arm64-v8a/lib/{libech_dsp.so,libechidna.so,libechidna_shim_jni.so,libechidna_preproc.so}
build/armeabi-v7a/lib/{libech_dsp.so,libechidna.so,libechidna_shim_jni.so,libechidna_preproc.so}
build/x86_64/lib/{libech_dsp.so,libechidna.so,libechidna_shim_jni.so,libechidna_preproc.so}
```

That is 12 generated Android shared objects across the three ABIs. Supported release transport
still verifies exactly nine: the engine/DSP/shim-JNI triplet per ABI. The Magisk packager consumes
the engine and DSP pairs; the LSPosed Gradle build consumes the dedicated shim JNI and DSP pairs.
No release step copies `libechidna_preproc.so`; no effect XML registers it or capture session
attaches it. ABI set:
**arm64-v8a (primary)**, `armeabi-v7a`, `x86_64`. Environment overrides: `ECHIDNA_ABIS`,
`ANDROID_PLATFORM`, `ECHIDNA_BUILD_TYPE`, `ECHIDNA_CMAKE_GENERATOR`, `ECHIDNA_CMAKE_EXTRA_ARGS`,
`CMAKE`; the NDK path also resolves from `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`.

**BoringSSL / plugin signature verify.** The DSP build links crypto in a layered, first-hit-wins
order so the Ed25519 plugin-signature path (`ECHIDNA_HAS_BORINGSSL`) is not silently off on device:
(1) a prebuilt install via `-DECHIDNA_BORINGSSL_ROOT=<dir>`; (2) a toolchain/system `libcrypto`;
(3) FetchContent BoringSSL (Android-only, default on, pinned tag `0.20240913.0`, nothing vendored
into the repo). If none resolve, the loader stays **fail-closed** and logs a loud warning.

> **Build-hygiene note.** The FetchContent BoringSSL sub-build compiles its own large test suite
> even with `-DBUILD_TESTING=OFF` (BoringSSL doesn't gate on the standard flag). It succeeds but
> roughly triples native build time; restrict to the `ech_dsp`, `echidna`, and
> `echidna_shim_jni` targets or disable the sub-build's tests to speed CI.

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

# (a) per-ABI native libs → build/<abi>/lib/{libech_dsp,libechidna,libechidna_shim_jni}.so
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

> The native-build → Magisk packager chain has run on the current Docker daemon and reproduced the
> architecture-checked payload. The first `android-build` run still needs network for Gradle
> dependencies. A container build is artifact evidence, not a substitute for the signed-release
> payload/certificate verifier or a live-device capture test. See `docker/README.md`.

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
API v3 contract, and `native/zygisk/src/module.cpp` defines `class EchidnaModule : public
zygisk::ModuleBase` with `REGISTER_ZYGISK_MODULE(EchidnaModule)`. The hook lifecycle is driven from
`preAppSpecialize` caches the target process and resolves the trusted companion UID while the
package registry remains readable. `postAppSpecialize` creates a fail-closed profile reader and
activation worker. It verifies the socket publisher with `SO_PEERCRED`, negotiates v2, reconnects
after cold start/disconnect, and installs hooks only after a current generation admits the target,
assigns the `zygisk` capture owner, and passes master/bypass/panic/whitelist gates.

The route contract is explicit:

| Route | Current support | Metadata / unavailable reason |
| --- | --- | --- |
| AAudio | Operational candidate | AAudio stream getters. |
| OpenSL ES | Operational candidate | Recorder sink PCM descriptor. |
| tinyalsa | Operational candidate | `pcm_open` config. |
| LSPosed Java `AudioRecord` | Operational candidate | Java stream getters and dedicated JNI. |
| Legacy input preprocessor effect ABI | Experimental attachment candidate | Packaged and next-boot registered only for proven legacy-HIDL system/vendor configs; a default-off LSPosed path can request authorized per-session attachment. |
| Stable AIDL input preprocessor effect | OEM integration candidate | API 34 V1 and API 35 V2 source modules exist; OEM Soong, VTS, FMQ/reopen, SELinux, and transformed-audio proof are still required. |
| Native `AudioRecord` | Developer contract only | `ECHIDNA_AR_SR/CH/FORMAT`; no normal-flow producer. |
| libc raw-device read | Developer contract only | `ECHIDNA_LIBC_SR/CH/FORMAT`; no normal-flow producer. |
| Audio HAL | Unsupported | `unsupported_injection_boundary`. |
| AudioFlinger | Unsupported | `unsupported_injection_boundary`. |

“Operational candidate” describes a reachable code contract, not physical-device proof. Native
AudioRecord/libc fail closed unless a developer supplies a valid PCM contract. App-process Zygisk
does not own audioserver or a stable vendor stream ABI, so HAL and AudioFlinger do not install.

**ABI support.** `arm64-v8a` is the locked primary implementation, but live arm64 Zygisk loading
and hook installation still need physical-device proof. `x86_64` has a complete inline-hook
trampoline; its earlier rooted-emulator `AudioRecord.read` probe predates the current route
contract. Broader target-app injection is not claimed. `armeabi-v7a` builds but **gracefully
degrades**: AAudio, OpenSL ES, tinyalsa, native `AudioRecord`, and libc-read report
`unsupported_armv7_late_symbol_hooking` before installation. Thumb-2/IT-block relocation is not
implemented, and the one-shot Zygisk v3 PLT API cannot cover caller libraries loaded after
specialization and authenticated policy delivery. LSPosed Java/JNI and the official legacy
preprocessor use separate attachment boundaries and remain eligible. Real armv7 runtime telemetry
still needs device proof.

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
  (`magiskModuleInstalled`, `zygiskEnabled`, `selinuxState`/`selinuxStatus`,
  `policyToolAvailable`, `policyAppliedVerified`, `nativeRouteVerified`,
  `javaFallbackRecommended`, and an `audioStack` capability probe). Availability and a fallback
  recommendation are not transformed-buffer runtime proof. This replaces fabricated active state.
- `String getWhitelistBindings()` → read-back JSON `{"whitelist":{proc:bool},"appBindings":{pkg:presetId}}`.
- `void setMasterEnabled(boolean)`, `void setBypass(boolean)`, `void triggerPanic(long holdMs)`,
  `void setSidetone(boolean enabled, float gainDb)`, `void setEngineMode(String engineMode)`,
  `String getControlState()` → global control, persisted and pushed into the snapshot `control`
  object. Compatibility engine mode disables native hook admission while preserving the Java shim
  fallback path.

The service attempts to load module-provided `libechidna.so` lazily via JNI
(`echidna_control_jni`). The companion APK deliberately contains only `libechidna_control_jni.so`,
not the full engine or DSP. If the module library is unreachable in the app namespace, binder
methods return `ECHIDNA_RESULT_NOT_AVAILABLE` and status is forced to `ECHIDNA_STATUS_ERROR`.

For broader Samsung, Qualcomm, MediaTek, and Tensor HAL work, run the read-only static analyzer:

```sh
python tools/analyze_audio_hal_dump.py /path/to/extracted-root \
  --output out/audio-hal-analysis.json
```

It classifies vendor profiles, scans audio libraries and policy/kernel hints, and ranks possible
hook surfaces. Treat its output as planning evidence only; [Vendor HAL Analysis](vendor-hal-analysis.md)
defines the live-device proof required before claiming a vendor path is supported.

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

### Policy v2 is authenticated, scoped, and multi-reader

`ProfileStore` owns a complete strict v2 document and monotonic generation. Required fields include
the explicit default profile, per-process whitelist and capture owner, bindings, and all global
master/bypass/panic/sidetone/engine controls. Persisted publications also bind policy packages to
the full Android UID/user and current APK signing digests. Mutations persist before replay; rollback,
same-generation conflict, malformed/duplicate keys, incomplete controls, invalid owners, an old
identity-free store, and current install-identity drift fail closed. Transport views omit the
internal identity table.

Zygisk readers negotiate on a service-owned, per-user abstract `AF_UNIX` socket. User 0 retains
`echidna_profiles`; nonzero users use `echidna_profiles_u<userId>` to prevent cross-user listener
collisions. The companion must be installed and started in the target Android user. The publisher
binds the v3 process claim and full `SO_PEERCRED` UID to the current published identity,
while native code accepts only the companion UID resolved before specialization. PID belongs only to
that socket incarnation. Disconnect revokes processing, keeps the generation watermark, and
reconnects with bounded backoff so a late publisher can activate safely.

LSPosed does not use that socket. It explicitly binds exported read-only `PolicySnapshotService`;
the provider binds `Binder.getCallingUid()` to the same published package/user/signing identity and
pins PID plus the callback Binder to one registration incarnation. It returns an exact/base scoped
view. Bounded listeners carry generation invalidations only. The shim fetches the new snapshot,
rejects rollback/conflict, and preserves original audio if policy changes in-flight. Provider API
v7 makes every PID-bound capability, telemetry, proof, and drain report synchronous only through
UID/PID validation and bounded queue admission. Signing and proof verification remain offloaded.
Retained v2-v6 one-way report transactions cannot supply a caller PID and therefore fail closed.

Do not assign one process to both capture owners. The policy schema permits only `zygisk` or
`lsposed`, and each consumer requires its own owner before processing.

### armeabi-v7a hooking degrades

As noted above, armv7 builds and loads but rejects direct inline-symbol routes before installation
(graceful degrade with an exact diagnostic reason). LSPosed Java/JNI and the official legacy
preprocessor remain eligible through their separate boundaries. arm64 is the primary native-hook
target.

### Capture routes are not all operational

AAudio, OpenSL ES, tinyalsa, and the LSPosed Java fallback have normal-flow metadata sources but
remain device-gated. Native `AudioRecord` and libc reads are developer-contract-only. Audio HAL and
AudioFlinger are explicitly unsupported (`unsupported_injection_boundary`), not hooks awaiting only
more testing. See the route table above and [Architecture](architecture.md#data-plane-audio-capture-to-dsp).

The legacy input preprocessor is an official Android effect-ABI boundary, not a private HAL hook.
The module ships it in inert per-ABI staging. Late-start may prepare a same-partition system/vendor
registry outside the auto-mounted tree only after a registered `lshal` factory PID and its
`/proc` maps/ELF identity pass. On the next boot, `post-fs-data` removes stale backing and validates
fingerprint, stock config, registry, library, key, and metadata before exposing the transient config
to Magisk's later mount phase. Any mismatch leaves the stock config active; Stable-AIDL-only,
ambiguous-PID, and active-ODM configurations fail closed. Registration adds no automatic
`preprocess`/`pre_processing` application. The companion's **Legacy AudioFlinger preprocessor
(experimental)** switch is a separate, companion-UID-only persisted flag. Enabling it only permits
LSPosed to request a short-lived capability and attach the registered effect to an eligible
`AudioRecord` session; policy/profile updates do not overwrite the flag.
Because the module has one global user-0 signer/HMAC trust domain, the control service clears and
rejects this flag, and skips legacy key preparation, when its companion UID belongs to another
Android user. This limitation is specific to the optional legacy effect; the authenticated Zygisk
and LSPosed policy transports remain same-user, full-UID scoped.

Registration and post-fs exposure also require both legacy-effect trust inputs to pass the shared
label lifecycle. The authoritative controller SPKI at
`trust/next-boot/preprocessor_controller_p256.spki` must match the root:root `0444` derived copy at
`system/etc/echidna/preprocessor_controller_p256.spki`; the telemetry root pin and root:audio
`0440` effect copy must match independently. The two files have distinct SELinux types and only
`audioserver` plus `hal_audio_server` receive `{ getattr open read }`. No app domain receives either
trust input. This is a packaging and boot-script invariant, not a claim that a real OEM effect host
has loaded either file successfully.

Attachment still requires signer trust and effect registration staged on a prior boot, a restart,
a supported legacy HIDL factory, an LSPosed-injected target, an explicit trusted user-0 whitelist
entry with the LSPosed capture owner, and fresh route-matched mutation evidence. Stable-AIDL-only
devices remain unsupported by the Magisk runtime-registration path. A separate OEM source-build
path now exists for Android 14/15; see
[Stable AIDL AudioEffect OEM integration](stable-aidl-effect-oem-integration.md). The switch itself
does not make an SDK-level compatibility verdict;
runtime HIDL and effect evidence determine eligibility. The switch is permission, not proof of
effect load, enablement, linker/label access, enforced-SELinux operation, or transformed audio.

## Status: verified vs. needs a device

**Host-build-verified (on the development host, from clean):**

- Debug companion APK compiles (~20 MB); packages the `:service` JNI for four ABIs; the merged
  manifest carries the `<queries>` block and the in-app `EchidnaControlService` (app↔service
  topology intact).
- LSPosed shim APK compiles as an installable `com.echidna.lsposed` package.
- The native superbuild cross-compiles 12 outputs (four libraries across three ABIs). Release
  delivery verifies all four library families; the preprocessor is staged only in the Magisk module.
- Host DSP unit tests (preset + engine) pass; the BoringSSL Android cross-build compiles the Ed25519
  verify path.
- The C/C++ format gate is clean tree-wide.

**Runtime-verified on emulators:**

- App instrumentation passes **13/13** on rooted Android 13 and Android 14 x86_64 emulators,
  including the in-APK service bind and `processBlockAppliesPresetWhenNativeEngineIsAvailable`.
- The native `processBlock` instrumentation test applies a real preset and asserts the output is
  finite and measurably changed when `libechidna.so` and `libech_dsp.so` are reachable.
- `:interception-probe:connectedDebugAndroidTest` passed **1/1** on the same rooted emulators before
  the explicit native-`AudioRecord` PCM-contract redesign. It is historical regression evidence,
  not current reachability proof.
- Earlier stock-emulator coverage still proves install, launch, navigation, fallback UI state, and
  the in-app service/AIDL round-trip without root.

**Requested coverage calls, explicitly:**

| Requested item | Status |
| --- | --- |
| Live Zygisk module load + real hook install on arm64 primary | **Release-device-only / NOT verified here** |
| LSPosed shim injection + authenticated Binder policy under SELinux | **Release-device-only / NOT verified here** |
| Legacy input preprocessor registration | **Implemented for proven legacy-HIDL system/vendor configs; device load proof pending** |
| Legacy input preprocessor session attachment/enablement | **Default-off LSPosed candidate implemented; physical-device load, activation, and audio proof pending** |
| Stable AIDL preprocessor library | **API 34/35 OEM source integration implemented; Soong build, VTS, FMQ/reopen, SELinux, and device audio proof pending** |
| SELinux enforcement and supported capture candidates on real hardware | **Release-device-only / NOT verified here** |
| Native AudioRecord/libc normal-flow metadata | **Not implemented; developer contract only** |
| Audio HAL / AudioFlinger transformation | **Unsupported injection boundary** |
| x86_64 trampoline under real injection | **Host harness verified; full current release injection NOT verified** |
| armv7 degrade behavior | **Build/code-path covered; real armv7 runtime NOT verified** |
| APK install -> service bind -> live AIDL round-trip | **Emulator/rooted-emulator verified** |

**Still release-device-only / NOT verified here:**

- Magisk Manager or `magisk --install-module` flashing, reboot, module-manager load, and
  magic-mount namespace behavior. The emulator `magisk --install-module` attempt returned
  `Incomplete Magisk install`.
- Live LSPosed shim injection into target apps, LSPosed scoping, and authenticated Binder policy
  reads under SELinux.
- Physical-device Zygisk lifecycle and hook installation on the arm64 primary path.
- AAudio, OpenSL ES, and tinyalsa managers in live app processes.
- On-device SELinux enforcement and vendor audio-stack behavior for supported candidates.
- Effect-host reads of the separately labelled controller SPKI and telemetry HMAC key under
  enforcing SELinux.
- armeabi-v7a graceful-degrade at runtime.

Echidna is a root/sideload application; on-device validation is a required, separate step before any
release is considered functional.
