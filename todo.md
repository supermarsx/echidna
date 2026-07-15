# Echidna TODO and status

> Reconciled 2026-07-15 against the current source, build scripts, release workflow, and host
> tests. `[x]` means implemented and host-verified unless the line explicitly says device-verified;
> `[~]` means implemented but device-gated or intentionally limited; `[ ]` means unmet. A green
> build is not proof that a capture route transforms audio on a phone. See
> [Verification](docs/verification.md).

## Release-critical capture status

The route contract is defined in
`native/zygisk/src/hooks/capture_route_reachability.h` and implements the native-first priority in
[spec section 4.2](spec.md#42-primary-hook-targets-priority-order).

| Route | Status | Remaining work |
| --- | --- | --- |
| AAudio | [~] Operational candidate | Stable getters and processing paths exist; prove input read/callback transform in a whitelisted arm64 app on hardware. |
| OpenSL ES | [~] Operational candidate | PCM descriptor, queue FIFO, rollback, callback, and destroy lifecycle have host coverage; prove live recorder capture. |
| tinyalsa | [~] Operational candidate | `pcm_open` config and read lifetimes exist; prove a real target/vendor route. |
| LSPosed Java `AudioRecord` | [~] Operational candidate | Dedicated JNI + DSP packaging and transactional Java reads exist; prove LSPosed injection, policy snapshot, and transform under enforcing SELinux. |
| Legacy input preprocessor | [~] Registered boundary | ABI/lifecycle/audio/RT tests plus per-ABI packaging and conditional next-boot registration exist; session-attach and prove it before enablement. |
| Native `AudioRecord` | [ ] Developer contract only | Replace `ECHIDNA_AR_SR/CH/FORMAT` with a safe normal-flow PCM metadata/lifecycle source, then prove exact-ABI capture. |
| libc raw-device read | [ ] Developer contract only | Replace `ECHIDNA_LIBC_SR/CH/FORMAT` with safely derived metadata or retain and document it as a developer-only diagnostic route. |
| Audio HAL | [ ] Unsupported | Current result is `unsupported_injection_boundary`. A new design must own audioserver injection, stable per-stream metadata, and vendor lifecycle tests. |
| AudioFlinger | [ ] Unsupported | Current result is `unsupported_injection_boundary`. Do not use private offsets; require a stable, separately proven transform boundary. |

“Operational candidate” means the normal API supplies the required PCM contract. It does not mean
the path has passed on release hardware. Unsupported routes fail closed and must not appear as
installed or active in telemetry.

## Delivered and host-verified

### DSP and buffer safety

- [x] Real DSP chain: gate, EQ, compressor, pitch, formant, Auto-Tune, reverb, and mix.
- [x] Noise-gate attack/release hysteresis closes correctly after the signal falls below threshold.
- [x] Pitch-shift/Auto-Tune state survives arbitrary callback boundaries and sample rates.
- [x] PCM16/float capture routing validates byte/frame boundaries, finite values, and saturation.
- [x] Callback watchdog, xrun telemetry, auto-bypass, master bypass, and timed panic state.
- [ ] Hardware panic-button combination listener from
  [spec section 12](spec.md#12-safety--emergency-ux-native-risks).
- [x] Reproducible host performance/correctness benchmark: the final full host report passed 40/40
  functional checks and 592 scenarios with no strict-deadline misses or safety-gate failures. This
  is processing-cost evidence only; Android callback, acoustic, and end-to-end latency remain device
  work. See [Performance Testing](docs/performance-testing.md).

### Control plane and policy

- [x] Companion APK hosts `EchidnaControlService` in-process with one canonical AIDL.
- [x] Presets, effect edits, bindings, whitelist, master/bypass/panic/sidetone, and engine mode are
  persisted and replayed after restart.
- [x] Complete strict policy v2 persists profiles, explicit default, bindings, whitelist,
  `captureOwners`, full control state, and a monotonic service generation.
- [x] Zygisk policy uses an authenticated, UID-scoped, service-owned abstract `AF_UNIX` publisher;
  disconnect/revoke is fail-closed and late publisher startup reconnects safely.
- [x] LSPosed policy uses an explicit read-only Binder provider that authenticates caller UID and
  claimed process, returns only exact/base scope, and publishes bounded generation invalidations.
- [x] Admission defaults to deny and requires master/bypass/panic, whitelist, default/binding, and
  matching `zygisk`/`lsposed` owner gates. Malformed, incomplete, rollback, and conflicting policy
  fail closed.
- [~] Companion JNI fails closed when the Magisk-delivered engine is not reachable from the app
  namespace; real magic-mount/linker namespace reachability still needs a device.

### Android artifacts and release delivery

- [x] Companion packages exactly `libechidna_control_jni.so` for four ABIs; no engine, DSP, or shim
  JNI leakage.
- [x] Native superbuild produces and release delivery verifies/transports 12 outputs: engine, DSP,
  shim JNI, and default-off preprocessor across three ABIs.
- [x] Package `libechidna_preproc.so` and conditionally stage exact legacy-HIDL registration for the
  next boot without auto-apply.
- [ ] Session-attach and enable `libechidna_preproc.so`, then prove device audio/latency/SELinux.
- [x] LSPosed APK requires the native build and packages exactly dedicated shim JNI + DSP for three
  ABIs; it never packages the Zygisk engine.
- [x] Flashable Magisk package has one module id, per-ABI Zygisk/DSP payloads, recovery markers,
  boot watchdog, narrow config/telemetry SELinux types, and no private-service offset staging.
- [x] Hosted release refuses missing/partial/bad signing inputs, validates the private-key entry
  before tag creation, verifies exact APK payloads and a pinned non-debug certificate, and cleans
  temporary keystores. Direct local Gradle builds retain a non-distributable debug fallback.
- [x] CI release runs from the normal `main` pipeline after its gates; native cppcheck findings are
  blocking with only reviewed, line-scoped style suppressions.
- [ ] Enable R8/resource shrinking only after keep rules prove AIDL, JNI, Compose, LSPosed, widgets,
  notifications, install, launch, service bind, and shim load.

## ABI status

| ABI | Status |
| --- | --- |
| arm64-v8a | [~] Primary implementation; builds/tests pass, physical Zygisk capture proof missing. |
| x86_64 | [~] Relocating trampoline passes its host harness; the older rooted AudioRecord probe predates the current route contract. |
| armeabi-v7a | [ ] Hooking intentionally disabled; module loads but reports `hook_unsupported_abi` until a safe Thumb-2/IT-block relocator is implemented and proven. |

## Required release-device validation

Before describing Echidna as functional for a device/app combination:

- [ ] Flash the current Magisk zip through Magisk Manager, reboot, and prove watchdog/recovery-marker
  behavior plus clean uninstall/disable recovery.
- [ ] Prove arm64 Zygisk specialization, whitelist admission, profile replay, DSP loading, and
  processed PCM in a route-matched target app.
- [ ] Prove AAudio input callback/read, OpenSL recorder queue, and tinyalsa capture separately where
  the device/app actually uses them.
- [ ] Prove LSPosed Java `AudioRecord` injection, authenticated Binder policy, dedicated JNI loading,
  buffer transform, and capture-owner enforcement under enforcing SELinux.
- [ ] Measure callback p50/p95/p99, xruns, CPU, thermal behavior, and audio correctness on the device
  matrix required by [spec section 11](spec.md#11-testing-plan-native-heavy) and
  [spec section 20](spec.md#20-qa-checklist-ui--dsp).
- [ ] Rebuild/install current artifacts and rerun config/telemetry plus both policy transports under
  enforcing SELinux. A 2026-07-15 disposable API 33 x86_64 emulator with an old module v0.0.0 denied
  an `untrusted_app` telemetry write and failed profile-listener creation; that historical snapshot
  is a blocker signal, not evidence that current HEAD fails identically. Fix narrowly if reproduced.
- [ ] Exercise rapid profile switching, app restart, service restart, concurrent readers, master
  bypass, panic, boot failsafe, and module update/rollback.
- [ ] Validate signing-certificate migration: older debug-signed APKs require one-time uninstall;
  subsequent release-signed upgrades must retain certificate continuity and app state.

## Non-release follow-ups

- [ ] Provision a real trusted Ed25519 plugin public key at build time; the shipped all-zero
  placeholder correctly rejects every third-party plugin.
- [ ] Characterize and document which apps/devices use each supported capture candidate without
  inferring hook success from library-presence probes.
- [ ] Add a safe, tested armv7 trampoline only if maintaining 32-bit ARM becomes a release goal.
