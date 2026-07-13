# Echidna TODO

> **Reconciled 2026-07-12 against the Phase 1–3 remediation.** Several items below were previously
> marked `[x]` but were **not** true end-to-end (the app↔service↔native control plane could not
> build, install, or connect). Those have been corrected and annotated. Legend: `[x]` = genuinely
> done and (where noted) host-build-verified; `[~]` = implemented but device-gated / partially
> verified; `[ ]` = not done / remaining. **No item marked `[x]` here has been validated on a live
> rooted device** unless it is host-runnable — see "Remaining / device validation".

## Gap analysis

### Native hooks
- [x] AAudio coverage is limited to data callback; no read/write hook path.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] OpenSL hook logs telemetry only; PCM is not processed.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] libc read fallback captures telemetry but does not run DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Symbol discovery lacks signature-match fallback for vendor-only exports.  
  Spec: [4.1](spec.md#41-symbol-discovery--hooking-method)
- [x] Per-API level guards are not explicit around vendor symbol variants.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)
- [x] Hook failure telemetry does not surface which symbol path was chosen.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)

### DSP pipeline
- [~] Panic and auto-bypass triggers are not implemented in the native path.  
  Native hybrid-worker watchdog + panic timer exist; the app now routes panic through the service.
  In-callback sync overrun guard and a HW button-combo listener remain. Spec:
  [12](spec.md#12-safety--emergency-ux-native-risks)
- [x] Hybrid worker mode lacks watchdog and xrun reporting integration.  
  Spec: [5](spec.md#5-dsp-pipeline--low-level-performance)
- [x] Preset compatibility warnings are not surfaced to the app.  
  Spec: [17](spec.md#17-effect-parameter-reference-safe-ranges--warnings)
- [x] Plugin signature failures are not reported to diagnostics.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

### Control service
- [x] Binder push channel for profile sync is not implemented.  
  Spec: [3](spec.md#3-high-level-native-architecture-detailed)
- [x] Per-app preset binding storage does not flow into whitelist snapshot.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Control service does not expose latency mode overrides per profile.  
  Now wired end-to-end (was implemented with zero callers). Spec:
  [8](spec.md#8-companion-app---native-features--ui-changes)

### Companion app
- [x] Preset import/export/share flows are stubbed in UI.  
  Spec: [16.2](spec.md#162-preset-management)
- [x] Effects chain editor is present but not wired to persistence.  
  Spec: [16.3](spec.md#163-effects-chain-editor-per-preset)
- [x] Diagnostics charts do not show latency histograms or xrun counts.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Compatibility wizard lacks SELinux + HAL probe details.  
  Now reads a real `getModuleStatus`/`refreshStatus` probe (was hardcoded "Qualcomm QSSI"/
  "Enforcing" after a fake delay). Spec: [8](spec.md#8-companion-app---native-features--ui-changes)

### LSPosed shim
- [x] Per-app whitelist UI and profile binding status are missing.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Java AudioRecord hook does not enforce per-app policy fail-closed.  
  Spec: [Java/Kotlin](spec.md#3-high-level-native-architecture-detailed)

### Tooling, CI, docs
- [x] Magisk packaging docs and release pipeline are incomplete.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] CI does not run native hook smoke tests or DSP preset validation.  
  Spec: [11](spec.md#11-testing-plan-native-heavy)
- [x] Doxygen or API reference is missing for public native headers.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

## TODO backlog

### Native hooks
- [x] Add AAudioStream_read/AAudioStream_write hook path with DSP routing.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Implement OpenSL buffer queue PCM conversion and DSP processing.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Add DSP processing to libc read fallback for /dev/snd captures.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Extend PltResolver with PLT/GOT scan and vendor symbol heuristics.  
  Spec: [4.1](spec.md#41-symbol-discovery--hooking-method)
- [x] Add hook selection telemetry (symbol path, lib, fallback reason).  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Add API-level guards to hook managers for vendor ABI changes.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)

### DSP pipeline
- [x] Implement latency watchdog and auto-bypass for sustained overruns.  
  Spec: [12](spec.md#12-safety--emergency-ux-native-risks)
- [x] Add panic bypass toggle and timer to native engine state.  
  Now driven from the app via the service (`triggerPanic`). Spec:
  [12](spec.md#12-safety--emergency-ux-native-risks)
- [x] Expose preset safety warnings in telemetry shared memory.  
  Spec: [17](spec.md#17-effect-parameter-reference-safe-ranges--warnings)
- [x] Report plugin signature failures to diagnostics UI.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

### Control service
- [x] Add binder method to push profile snapshots to native module.  
  `pushProfileSnapshot` now has a caller (was dead). Spec:
  [3](spec.md#3-high-level-native-architecture-detailed)
- [x] Persist per-app preset bindings and include in profile snapshot.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Add process-aware whitelist enforcement in control service API.  
  Snapshot now carries `whitelist`+`appBindings`; the shim resolves policy from it fail-closed.
  Spec: [4.3](spec.md#43-hooking-details--robustness)

### Companion app
- [x] Wire preset import/export/share flows to file picker and share sheet.  
  Spec: [16.2](spec.md#162-preset-management)
- [x] Persist effects chain edits through ControlStateRepository.  
  Spec: [16.3](spec.md#163-effects-chain-editor-per-preset)
- [x] Add latency histogram + CPU chart in Diagnostics screen.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Surface native engine status (installed/active/bypassed) in UI.  
  Now reads real `getModuleStatus` (was hardcoded `nativeInstalled=true/active=true/Enforcing`).
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)
- [x] Expand compatibility wizard with SELinux and HAL probes.  
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)

### LSPosed shim
- [x] Implement per-app whitelist editor with default preset binding.  
  Editor now has binder read-back + PackageManager enumeration (was write-only, hand-typed).
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Enforce fail-closed policy in AudioRecord hook when app not allowed.  
  Spec: [Java/Kotlin](spec.md#3-high-level-native-architecture-detailed)

### Tooling, CI, docs
- [x] Add Magisk module packaging README and example release checklist.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] Add CI job to run native dsp tests and hook smoke runs.  
  Spec: [11](spec.md#11-testing-plan-native-heavy)
- [x] Add Doxygen config and baseline for native API headers.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

## Work started
- [x] Harden profile sync parsing with whitelist validation and hooks flag.  
  Spec: [3](spec.md#3-high-level-native-architecture-detailed)
- [x] Fix Magisk module.prop templating to expand version fields.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] Process libc read fallback buffers through the DSP pipeline.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Process OpenSL buffer queue callbacks through DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Hook AAudio read/write paths and route through DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Capture hook selection telemetry details for diagnostics.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Document Magisk packaging script and release checklist.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] Validate process names before updating the whitelist snapshot.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)

## Remediation delivered (Phase 1–3)

Build, topology, packaging, and delivery work that made the above features actually reachable. All
items here are **host-build-verified** (compile/link/tests on the dev host) unless marked otherwise;
none are validated on a live device.

### Build & configuration (Phase 1)
- [x] App Gradle configuration + version catalog (AGP 8.2.2 / Kotlin 1.9.22 / Compose ext 1.5.10).
- [x] `android.useAndroidX=true` + `buildFeatures { aidl = true }`.
- [x] LSPosed shim Java syntax fix (missing `import` keywords).
- [x] Committed Gradle wrapper (8.5) for all three Android projects.
- [x] Native JNI include fix (`find_package(JNI)`), native-test output path fix, repo `.clang-format`.
- [x] CI `ci.yml` installs the Android SDK + JDK for the Android/native jobs; format gate is green.

### Runnable-on-device topology (Phase 2 / 2.5)
- [x] Control service hosted **in-process** inside the companion APK; phantom
  `com.echidna.control` bind target removed.
- [x] AIDL unified to a single canonical source under `:service`; app duplicate deleted.
- [x] Four engine controls (master/bypass, panic, sidetone, latency) now route through the service
  instead of mutating only local state.
- [x] LSPosed shim de-phantomed — reads per-app policy from the `ProfileSyncBridge` snapshot
  (`{profiles, whitelist, appBindings, control}`) fail-closed; old `getAppConfig` binder deleted.
- [x] `libechidna.so` is a genuine Zygisk module (`REGISTER_ZYGISK_MODULE`), hook lifecycle driven
  from `postAppSpecialize`.
- [x] NDK per-ABI cross-compile (`tools/build_native_ndk.sh`, `native/CMakeLists.txt`) for
  arm64-v8a / armeabi-v7a / x86_64; BoringSSL linked so Ed25519 plugin verify is on for Android.
- [x] Debug APK builds from clean (~20 MB); all six per-ABI native `.so` link with correct ELF arch.
- [x] Compile-blocker fixes surfaced by the first real build (`:service` Kotlin, DSP `from_chars`,
  Zygisk `shm_open`→memfd, etc.).
- [~] `x86_64` inline-hook trampoline implemented (host-harness verified; needs emulator confirm).
- [ ] `armeabi-v7a` inline hooking — currently **graceful-degrade** only (builds/loads, hooks
  disabled with a `hook_unsupported_abi` signal); Thumb-2/IT-block relocation not implemented.

### Ship it (Phase 3)
- [x] Release signing config (keystore.properties / `RELEASE_*` env; never commit keys; debug-signing
  fallback). See [docs/signing.md](docs/signing.md).
- [x] Docs rewritten: developer guide (build/topology/NDK/docker/known-limitation), signing model,
  Magisk release guide; this todo reconciled.
- [~] Flashable per-ABI Magisk module (single module id, `zygisk/<abi>.so`, META-INF installer,
  SELinux/socket bootstrap) — packaging authored; flashing needs a device.
- [~] Dockerized helper images (native-build, android-build, magisk-packager, ci-local, emulator) —
  authored; image builds need a Docker daemon.
- [~] Native DSP correctness + hook smoke tests, Android unit + instrumentation tests — added;
  instrumentation execution needs an emulator/device.
- [ ] CI/release finalization (`release.yml` → `assembleRelease` + keystore secrets + NDK per-ABI +
  Magisk packaging; real smoke test in `ci.yml`).

## Known limitations
- [ ] **Profile-sync socket is single-holder** (`/data/local/tmp/echidna_profiles.sock`,
  unlink-then-bind "last wins", per-process). Do not run the Zygisk module and the LSPosed shim
  together; with multiple hooked apps only the last binder receives pushes (others stay fail-closed).
  Proper fix: native/service redesign (serve-last-snapshot, or a world-readable published snapshot).
  See [docs/developer_readme.md](docs/developer_readme.md#known-limitations).
- [ ] Enable R8/resource-shrinking with a proven keep-rule set (AIDL/JNI/Compose) — disabled for the
  first release. See [docs/signing.md](docs/signing.md#minification).

## Remaining / device validation
Host builds and host-runnable tests are green; **on-device behaviour is unverified.** Before a
release is considered functional, validate on a rooted device / emulator:
- [ ] Live Zygisk module load + real hook install (arm64 primary).
- [ ] LSPosed shim injection + snapshot read under SELinux.
- [ ] SELinux enforcement + audio HAL behaviour on real hardware.
- [ ] Magisk zip flash + module load + socket/SELinux bootstrap.
- [ ] APK install → service bind → live AIDL round-trip.
- [ ] `x86_64` trampoline under real injection; armv7 degrade behaviour.
- [ ] Optional emulator-based end-to-end / instrumentation run (spec §11/§20 ABI matrix).
