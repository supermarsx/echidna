# End-to-End Verification

This page is the honest answer to *"does Echidna actually work end to end?"* Android
capture-path interception is a very hard, device-specific problem, and Echidna is likely
not to work on many phones even when every artifact builds, installs, and launches
correctly. The matrix below separates what was **really built, run, and measured on this
build host, stock emulator, and rooted emulators** from what still needs release-device
validation: full Magisk/LSPosed install, physical-device SELinux behavior, and
supported capture-route coverage. Audio HAL and AudioFlinger are explicitly unsupported
boundaries rather than unverified routes.

> **Reading guide.** Every row in the "Verified" table below corresponds to a real build,
> test run, or on-device action with recorded output. The "Still not verified" section
> lists exactly what was **not** run here, and why, followed by a step-by-step procedure
> to reproduce it on release hardware.

Verification host: Windows 11, PowerShell + Bash. Toolchain: Android SDK `C:\android-sdk`,
NDK r27 (`27.0.12077973`), JBR OpenJDK 21, Gradle 8.5 (wrapper), CMake 4.2.1 / Ninja 1.13.2,
apksigner (build-tools 34.0.0), Docker Desktop 4.72.0 (engine 29.4.2), stock emulator
`emulator-5554` (AVD `echidna_e2e`, API 34 / Android 14, x86_64, **unrooted**), plus rooted
x86_64 AVDs `echidna_e2e33` (Android 13) and `echidna_m26` (Android 14) for the native
`processBlock` testing and the historical, pre-redesign `AudioRecord.read` interception probes.

---

## 1. Verified on this build host / emulator (real, reproducible)

| Check | Method | Result |
| --- | --- | --- |
| **Signed release APKs** | Companion + shim `assembleRelease` with a disposable release key, then `tools/verify_android_artifacts.py` with its certificate SHA-256 | **PASS** — both APK signatures verify, both match the pinned non-debug certificate, and exact package/native payload checks pass. Hosted publication now performs the same fail-closed signing preflight and verification |
| **Debug APK** | `:app:assembleDebug` plus exact payload verification | **PASS** — the companion contains only `libechidna_control_jni.so` for four ABIs; engine/DSP/shim JNI libraries do not leak into it |
| **Per-ABI native libs** | NDK r27 superbuild, arch-checked with `llvm-readelf -h` | **PASS** — four targets × three ABIs: engine, DSP, shim JNI, and `libechidna_preproc.so`. Release tooling transports/verifies all four families |
| **Flashable Magisk zip** | `tools/build_magisk_module.sh` + `tools/verify_magisk_module.py --build-root build` | **PASS** — the deterministic archive has an exact 25-file regular-file layout: three Zygisk engines, three DSP libraries, three inert preprocessor libraries, signer/registration helpers, installer/watchdog files, narrow `sepolicy.rule`, status helper, and license. The verifier binds all nine native entries byte-for-byte to the trusted NDK outputs, checks each ELF ABI, SONAME, required export and exact DT_NEEDED set, enforces Unix modes and duplicate-free entries/policy, rejects generated SPKI/HMAC material, and checks the read-only trust-input label lifecycle and default-off activation contract |
| **Docker native-build → magisk pipeline** | `docker compose build native-build` then `run native-build`, then `magisk-packager` | **PASS** — the packager consumes engine/DSP pairs plus all three preprocessor ABIs; registration is generated only on an eligible device for its next boot |
| **Host native tests** | Native CTest suites | **PASS** — DSP, routing, v2 protocol/generation, authenticated lifecycle/reconnect, and legacy preprocessor ABI/lifecycle/audio/no-allocation tests pass |
| **App unit tests** | `./gradlew :app:testDebugUnitTest` (Robolectric, headless) | **PASS 24/24** — preset serialize/round-trip, status/control/whitelist JSON parsing, repository CRUD |
| **Service unit tests** | `./gradlew :service:testDebugUnitTest` (headless) | **PASS** — strict v2/default/owner/control validation, persistence, panic expiry, authenticated scoped delivery, generation rules, and fail-closed defaults |
| **Android instrumentation** | `:app:connectedDebugAndroidTest` on `emulator-5554` (clean install) | **PASS 12/12** — real in-APK `EchidnaControlService` bind + AIDL round-trips (control-state read, master, panic, whitelist write/read-back, `pushProfileSnapshot`/`listProfiles`, `getModuleStatus`), profile-switch flow, QS-tile state source, Compose dashboard render |
| **Rooted Android app instrumentation** | `.\gradlew.bat :app:connectedDebugAndroidTest` on `echidna_e2e33` (Android 13) and `echidna_m26` (Android 14) | **PASS 13/13 on both** — includes the real in-APK service bind and `processBlockAppliesPresetWhenNativeEngineIsAvailable`, which applies a native cut preset and asserts finite, measurably attenuated output when `libechidna.so`/`libech_dsp.so` are reachable |
| **Historical rooted `AudioRecord` interception probe** | `.\gradlew.bat :interception-probe:connectedDebugAndroidTest` on the same rooted emulators | **PASS 1/1 on both before the current redesign** — useful evidence for the older interception slice, but not proof that current native `AudioRecord` is reachable without its explicit PCM contract |
| **App install + launch** | `adb install -r app-debug.apk`; launch `.MainActivity` | **PASS** — install `Success`, `MainActivity` resumed, process stays up |
| **Crash-free relaunch** | 8 cold relaunches (`am force-stop` + `am start`) with a persisted `echidna_presets.json` present, scanning logcat | **PASS 8/8** — 0 crashes, process alive every launch. This is the exact trigger of the startup crash found by instrumentation (see highlight below) |
| **In-app screen navigation** | `adb input` nav + `uiautomator dump` across screens | **PASS (honest)** — 6 screens rendered live and navigated (Dashboard, Preset Manager, Effects Editor, Diagnostics, Settings, Compatibility Wizard) with correct *module-not-active* state on the unrooted emulator; the Whitelist Editor was wired into the nav graph afterward (t2-e27) and verified to render; the QS tile is a registered `TileService` that cannot be driven headless-unrooted (its state source is unit-verified) |
| **In-app control service bind** | `dumpsys activity services com.echidna.app` | **PASS** — live `ServiceRecord` for `EchidnaControlService` with an active `ConnectionRecord` (BIND_AUTO_CREATE); the app binds its in-app control service at runtime |
| **Format gate** | `clang-format --dry-run -Werror` over all tracked native/android C/C++ | **PASS** — 0 non-conformant of 82 files |

The final full host performance report (200 warmups, 1,200 measured calls, load 0 and 8) passed
40/40 functional checks and 592 scenarios with zero strict-deadline misses and zero safety-gate
failures. Its JSON contract validation passes. These are host processing-cost results only, not
Android callback, HAL, acoustic, or end-to-end call latency. See
[Audio pipeline performance testing](performance-testing.md).

### Latest rooted-emulator recheck

On 2026-07-14, the rooted checks were rerun after adding explicit CPU/ABI and native audio-library
probes to `getModuleStatus()`:

- `emulator-5560` / `echidna_m26` (Android 14, x86_64, Magisk 26.4): **PASS** for
  `:app:connectedDebugAndroidTest` and `:interception-probe:connectedDebugAndroidTest`.
- `emulator-5556` / `echidna_e2e33` (Android 13, x86_64, Magisk 25.2): **PASS** for
  `:interception-probe:connectedDebugAndroidTest`.
- `emulator-5562` / `echidna_e2e` (Android 14, x86_64, Magisk 25.2): **FAIL** for the
  interception probe because no current-process hook evidence was emitted. The AVD has
  `/data/adb/modules/echidna/zygisk/unloaded`, so Magisk is not loading the module there even
  though module files are present.
- Reinstalling `out/echidna-magisk.zip` with
  `magisk --install-module /sdcard/Download/echidna-magisk.zip` still returned
  `Incomplete Magisk install` on the rooted emulator, so module-manager flashing is still not
  claimed as verified.

These rooted interception results predate the current route-support contract and must not be used as
current native-`AudioRecord` reachability proof. The compatibility checker reports CPU family,
primary ABI, Zygisk ABI, active-hook support,
vendor family, and the presence of `libOpenSLES.so`, `libaudioclient.so`, and `libtinyalsa.so`.
Those are device signals, not live proof that OpenSL ES or tinyalsa processed audio. Audio HAL and
AudioFlinger report `unsupported_injection_boundary` and are not current transform routes.

### Highlight — why end-to-end testing mattered: the startup crash

The most valuable finding of this pass came *only* from running the app on a real emulator,
not from unit tests or a clean compile. The instrumentation run surfaced a
release-blocking startup crash:

- **Symptom.** After any preset edit persisted an `echidna_presets.json`, the app crashed
  on the *next* launch. Reproduced at **7 of 14** cold launches on `emulator-5554`.
- **Root cause.** `ControlStateRepository`'s `activePreset` combine did
  `list.first { it.id == id }`, which throws `NoSuchElementException` when `_activePresetId`
  transiently referenced an id not yet present in `_presets` (defaults regenerate their UUIDs
  each launch, so a persisted file always carried differing ids). It ran on `Dispatchers.Default`,
  so the uncaught exception became a **process crash**.
- **Fix.** Made the combine total (`firstOrNull { … } ?: firstOrNull() ?: first()`) and
  reconciled the active/default ids *before* publishing the preset list.
- **Proof it is gone.** Post-fix, **0 of 16** cold launches crashed on the identical planted
  trigger; independently re-confirmed here with **8/8** clean relaunch cycles. A signed,
  clean-compiling APK would still have shipped this crash — only driving the real app flow
  caught it.

---

## 2. Still not verified / device-gated

The rooted-emulator pass proves service-side native `processBlock` routing through the DSP. Its
native `AudioRecord.read` result predates the explicit PCM-contract redesign and does **not** prove
current route reachability or the whole release install path. Attempts to install the refreshed zip
with `magisk --install-module /sdcard/Download/echidna-magisk.zip` on the rooted emulator images
returned `Incomplete Magisk install`, so Magisk flashing remains unverified here.

Requested coverage, explicitly:

### Current capture-route status

| Route | Code status | Live proof still required |
| --- | --- | --- |
| AAudio | Operational candidate; metadata from stream getters | Physical-device target app |
| OpenSL ES | Operational candidate; recorder PCM descriptor and host lifecycle tests | Physical-device target app |
| tinyalsa | Operational candidate; metadata from `pcm_open` config | Vendor/device target app |
| LSPosed Java AudioRecord | Operational candidate; Java getters + dedicated JNI | Live LSPosed injection under SELinux |
| Legacy input preprocessor | Packaged and next-boot registration implemented for a registered system/vendor HIDL factory PID; host fixtures prove first-OTA refusal and bounded activation rollback; default-off LSPosed attachment manager requires a signed short-lived capability | Real factory discovery, post-fs/mount ordering, magic-mount label/linker namespace, descriptor/maps/AVC, attachment/enablement under enforced SELinux, and device audio mutation |
| Controller capability SPKI | Canonical 91-byte authoritative/derived pair, root:root `0444`, hash/inode checks, and dedicated read-only SELinux policy are host-verified; no generated SPKI ships | Effect-host read access to the separately labelled SPKI and signed-capability verification under enforcing SELinux |
| Telemetry origin-proof key | Per-install root pin/derived copies plus native ECHT v2 HMAC production, LSPosed relay, and control verification are host-tested; no key bytes ship or enter logs | Effect-host SELinux read access, rotation/recovery observation, and end-to-end device proof |
| Native AudioRecord | Developer contract only (`ECHIDNA_AR_*`) | A safe normal-flow metadata producer is not implemented |
| libc raw-device read | Developer contract only (`ECHIDNA_LIBC_*`) | A safe normal-flow metadata producer is not implemented |
| Audio HAL | Unsupported (`unsupported_injection_boundary`) | Requires a new, proven audioserver/vendor-stream design |
| AudioFlinger | Unsupported (`unsupported_injection_boundary`) | Requires a new, stable transform boundary |

| Requested item | Current status | What is covered / missing |
| --- | --- | --- |
| Live Zygisk module load + real hook install on **arm64 primary** | **Release-device-only / NOT verified here** | `arm64-v8a` native artifacts build and package, but no physical arm64 Magisk/Zygisk load, app-specialize, or hook-install run has been recorded. |
| LSPosed shim injection + authenticated Binder policy under SELinux | **Release-device-only / NOT verified here** | The shim and explicit provider build, but LSPosed install/scoping, injected-process binding, caller authentication, and transform were not run. |
| SELinux enforcement + supported capture candidates on real hardware | **Release-device-only / NOT verified here** | The app reports SELinux/audio-stack signals, but no enforcing physical device has proven AAudio, OpenSL, tinyalsa, or LSPosed capture processing. |
| Native AudioRecord/libc normal-flow metadata | **Not implemented; developer contract only** | These routes require explicit `ECHIDNA_AR_*` / `ECHIDNA_LIBC_*` sample-rate, channel, and format values that normal app specialization does not supply. |
| Audio HAL / AudioFlinger transformation | **Unsupported** | Both fail closed with `unsupported_injection_boundary`; app-process Zygisk does not own a safe audioserver/vendor-stream ABI. |
| x86_64 trampoline under real injection | **Host harness verified; current release path NOT verified** | The older rooted probe predates the current route contract; Magisk manager flash/reboot and arbitrary target-app injection are not claimed. |
| armv7 direct-route relocator | **Host-proven; on-device execution device-gated** | `armeabi-v7a` artifacts build and the ARM32/Thumb-2 prologue relocator is host-proven (relocation harness + `libechidna.so` links under NDK); the relocator fails closed per function, and no real armv7 device run has yet proved on-hardware install/execution. |
| APK install -> service bind -> live AIDL round-trip | **Verified on emulator/rooted emulator** | App instrumentation covers installable APK execution, in-app `EchidnaControlService` bind, and live AIDL round-trips; this does not prove Magisk/LSPosed hardware paths. |

Still not claimed as verified:

- **Magisk Manager / `magisk --install-module` flash + reboot** — including magic-mount namespace
  resolution of `dlopen("libech_dsp.so")` against `system/lib(64)`.
- **Full Zygisk lifecycle on release hardware** — loading from Magisk at zygote time, specializing
  into arbitrary target apps, and surviving reboot/module-manager install paths.
- **Supported normal-flow candidates** — AAudio, OpenSL ES, tinyalsa, and LSPosed Java AudioRecord
  still need live device coverage. Native AudioRecord/libc are developer-contract-only; Audio HAL
  and AudioFlinger are unsupported.
- **Live LSPosed shim injection** — the shim and Binder policy consumer are implemented, but
  LSPosed installation, scoping, injected-process Binder policy, and SELinux access were not exercised.
- **Narrow config and effect-trust SELinux policy** on a real enforcing device, including separate
  controller-SPKI and telemetry-HMAC labels and effect-host reads.
- **arm64 primary hardware and armeabi-v7a runtime behavior** — arm64 still needs physical-device
  proof; the armv7 relocator is host-proven but its on-hardware install/execution is device-gated.
- **Live authenticated policy delivery under SELinux** — UID-scoped Zygisk socket frames and
  caller/process-scoped LSPosed Binder views build and have host/unit coverage, but simultaneous
  injected-app delivery still needs enforcing-device validation.

### Historical enforcing-emulator blocker signal

On 2026-07-15, disposable API 33 x86_64 AVD `echidna_e2e33` was observed with SELinux Enforcing and
a pre-existing Echidna Magisk snapshot reporting module v0.0.0. It was **not current HEAD**. Live
logcat included `avc: denied { write }` from Maps in `untrusted_app` to
`echidna_telemetry_file`; the same process logged `echidna_profile_sync: Failed to create profile
listener`. The AVD was stopped without installing rebuilt artifacts. This makes a current-artifact
enforcing-SELinux rerun release-blocking; it does not establish that the current authenticated
socket/Binder implementation fails identically.

---

## 3. Reproduce on a real device (the device-gated procedure)

Run this on a **rooted physical device with Magisk 24.0+ and Zygisk enabled** to exercise the
release path that the rooted-emulator probe did not cover:

1. **Root + Magisk.** Unlock the bootloader and install Magisk (patched boot image /
   `fastboot`). Confirm the Magisk app shows *installed* and `su` works.
2. **Enable Zygisk.** Magisk → Settings → **Zygisk ON** → reboot. Zygisk is required for the
   `libechidna` module to load into audio processes.
3. **Flash the Echidna module.** Build `out/echidna-magisk.zip` (per-ABI, single id `echidna`;
   via `tools/build_magisk_module.sh` or the docker `magisk-packager` image — both verified
   above). Magisk → Modules → *Install from storage* → select the zip → **reboot**.
   `customize.sh` places `libech_dsp.so` per ABI into `system/lib(64)` and stages
   `libechidna.so` for the in-app JNI `dlopen` path. Before calling the legacy effect ready, verify
   that the controller SPKI has `echidna_controller_spki_file`, the telemetry HMAC has
   `echidna_telemetry_key_file`, and only the expected effect-host domains read them. A successful
   host verifier or module install alone is not this device proof.
4. **Install the LSPosed shim (Java fallback).** Install LSPosed (Zygisk
   flavour), enable the Echidna module in LSPosed, and scope it to the target app(s). This
   drives the authenticated read-only Binder policy path when native capture is unavailable.
5. **Grant the per-app whitelist.** In the companion app, enable the target package in the
   whitelist / app-binding. The system is **fail-closed** — unlisted processes are never hooked.
   Avoid scoping the same target app into both Zygisk and LSPosed unless this test is explicitly
   checking duplicate-hook behavior.
6. **Open a route-matched target app.** First identify whether it records through AAudio, OpenSL,
   tinyalsa, or Java `AudioRecord`; then start its capture path. Do not treat HAL/AudioFlinger or an
   unconfigured native AudioRecord/libc manager as a valid current test target.
7. **Verify pitch/effect live.** Select a pitch/formant preset (e.g. Darth Vader or Helium),
   speak, and confirm the transform in the target app's monitor or sidetone. Cross-check on-device
   via **Diagnostics**: the **Tuner** should show a non-zero *Detected Hz / offset (cents)*, the
   **latency histogram** should populate (no longer *"No latency data yet"*), the CPU heatmap
   should show samples. Treat *installed*, Zygisk availability, and fallback recommendations as
   capability signals only; require recent transformed-buffer telemetry for a verified route.
   The **Compatibility Wizard** should then report Magisk **installed** + Zygisk **enabled** — the
   inverse of the unrooted state captured on the emulator.
8. **Export diagnostics for support.** In **Diagnostics -> Logs & safety**, enable telemetry export
   and use **Export diagnostic internals**. The bundle is sanitized and includes action codes such
   as `configure_whitelist` when no target apps are enabled. Combine it with a static HAL analyzer
   report through `tools/build_hook_probe_report.py` before filing a hook-support issue.

---

*Sources: release-readiness gate (`t2-e20`), docker native → magisk container run (`t3-e1b`),
emulator install/launch/nav + crash-free confirmation (`t3-e3`), instrumentation + crash
discovery (`t2-e18`), crash fix + proof (`t2-e26`), native tests (`t2-e17`), rooted Android
13/14 app instrumentation, and the historical pre-redesign rooted Android 13/14 `AudioRecord`
interception probe.*
