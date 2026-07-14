# End-to-End Verification

This page is the honest answer to *"does Echidna actually work end to end?"* Android
capture-path interception is a very hard, device-specific problem, and Echidna is likely
not to work on many phones even when every artifact builds, installs, and launches
correctly. The matrix below separates what was **really built, run, and measured on this
build host, stock emulator, and rooted emulators** from what still needs release-device
validation: full Magisk/LSPosed install, physical-device SELinux/HAL behavior, and
broader hook coverage.

> **Reading guide.** Every row in the "Verified" table below corresponds to a real build,
> test run, or on-device action with recorded output. The "Still not verified" section
> lists exactly what was **not** run here, and why, followed by a step-by-step procedure
> to reproduce it on release hardware.

Verification host: Windows 11, PowerShell + Bash. Toolchain: Android SDK `C:\android-sdk`,
NDK r27 (`27.0.12077973`), JBR OpenJDK 21, Gradle 8.5 (wrapper), CMake 4.2.1 / Ninja 1.13.2,
apksigner (build-tools 34.0.0), Docker Desktop 4.72.0 (engine 29.4.2), stock emulator
`emulator-5554` (AVD `echidna_e2e`, API 34 / Android 14, x86_64, **unrooted**), plus rooted
x86_64 AVDs `echidna_e2e33` (Android 13) and `echidna_m26` (Android 14) for the native
`processBlock` and `AudioRecord.read` interception probes.

---

## 1. Verified on this build host / emulator (real, reproducible)

| Check | Method | Result |
| --- | --- | --- |
| **Signed release APK** | `./gradlew clean :app:assembleRelease` with a real (throwaway) 2048-bit RSA release key | **PASS** — `app-release.apk` = **14,729,186 B (~14.7 MB)**; `apksigner verify` PASS; signer DN `CN=Echidna Release Gate, O=Echidna, C=US` (a real release key, not the Android debug key) |
| **Debug APK** | `:app:assembleDebug` in the same clean run | **PASS** — `app-debug.apk` = **20,648,459 B (~20.6 MB)**, green |
| **Per-ABI native libs** | `tools/build_native_ndk.sh` (NDK r27), arch-checked with `llvm-readelf -h` | **PASS** — all **6** `.so`: `{libech_dsp, libechidna}.so` × `arm64-v8a` (AArch64), `armeabi-v7a` (ARM), `x86_64` (x86-64), each arch-confirmed. BoringSSL test/tool targets excluded (0 built; only the 2 libs link) |
| **Flashable Magisk zip** | `tools/build_magisk_module.sh` | **PASS** — flashable zip, **21 entries**, single module `id=echidna`, `minMagisk=24000`; `zygisk/<abi>.so` ×3 + `libs/<abi>/libech_dsp.so` ×3 + `META-INF` installer + `customize.sh`/`post-fs-data.sh`/`service.sh`/`module.prop`. All packaged `.so` are Android ELFs, arch-matched, no host ELF leaked |
| **Docker native-build → magisk pipeline** | `docker compose build native-build` then `run native-build`, then `magisk-packager` | **PASS** — the container reproduced all **6** `.so` (arch/size matching the host build) and produced `out/echidna-magisk.zip` (8,537,632 B) with the correct layout. Full native → magisk chain works end-to-end in containers |
| **Host native tests** | `cmake -S native -B … -DBUILD_TESTING=ON`, then root `ctest` | **PASS 5/5** — `dsp_preset_test`, `dsp_engine_test`, `dsp_effects_test` (6 DSP suites: pitch/formant/auto-tune/gate/compressor/EQ), `zygisk_dsp_smoke_test` (synth-PCM → int16↔float → real DSP-block harness with the fail-closed passthrough guard), and `capture_buffer_router_test` (production hook-body router for int16/float PCM). Exit 0 each |
| **App unit tests** | `./gradlew :app:testDebugUnitTest` (Robolectric, headless) | **PASS 24/24** — preset serialize/round-trip, status/control/whitelist JSON parsing, repository CRUD |
| **Service unit tests** | `./gradlew :service:testDebugUnitTest` (headless) | **PASS 11/11** — unified control-plane snapshot, master/bypass/panic propagation, fail-closed whitelist default |
| **Android instrumentation** | `:app:connectedDebugAndroidTest` on `emulator-5554` (clean install) | **PASS 12/12** — real in-APK `EchidnaControlService` bind + AIDL round-trips (control-state read, master, panic, whitelist write/read-back, `pushProfileSnapshot`/`listProfiles`, `getModuleStatus`), profile-switch flow, QS-tile state source, Compose dashboard render |
| **Rooted Android app instrumentation** | `.\gradlew.bat :app:connectedDebugAndroidTest` on `echidna_e2e33` (Android 13) and `echidna_m26` (Android 14) | **PASS 13/13 on both** — includes the real in-APK service bind and `processBlockAppliesPresetWhenNativeEngineIsAvailable`, which applies a native cut preset and asserts finite, measurably attenuated output when `libechidna.so`/`libech_dsp.so` are reachable |
| **Rooted `AudioRecord` interception probe** | `.\gradlew.bat :interception-probe:connectedDebugAndroidTest` on the same rooted emulators | **PASS 1/1 on both** — the probe opens Android `AudioRecord`, reads real PCM, and observes current-process hook evidence with `processed=1`; telemetry hook metadata is checked when available |
| **App install + launch** | `adb install -r app-debug.apk`; launch `.MainActivity` | **PASS** — install `Success`, `MainActivity` resumed, process stays up |
| **Crash-free relaunch** | 8 cold relaunches (`am force-stop` + `am start`) with a persisted `echidna_presets.json` present, scanning logcat | **PASS 8/8** — 0 crashes, process alive every launch. This is the exact trigger of the startup crash found by instrumentation (see highlight below) |
| **In-app screen navigation** | `adb input` nav + `uiautomator dump` across screens | **PASS (honest)** — 6 screens rendered live and navigated (Dashboard, Preset Manager, Effects Editor, Diagnostics, Settings, Compatibility Wizard) with correct *module-not-active* state on the unrooted emulator; the Whitelist Editor was wired into the nav graph afterward (t2-e27) and verified to render; the QS tile is a registered `TileService` that cannot be driven headless-unrooted (its state source is unit-verified) |
| **In-app control service bind** | `dumpsys activity services com.echidna.app` | **PASS** — live `ServiceRecord` for `EchidnaControlService` with an active `ConnectionRecord` (BIND_AUTO_CREATE); the app binds its in-app control service at runtime |
| **Format gate** | `clang-format --dry-run -Werror` over all tracked native/android C/C++ | **PASS** — 0 non-conformant of 82 files |

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

The compatibility checker now reports CPU family, primary ABI, Zygisk ABI, active-hook support,
vendor family, and the presence of `libOpenSLES.so`, `libaudioclient.so`, and `libtinyalsa.so`.
Those are device signals, not live proof that OpenSL ES, AudioFlinger, tinyalsa, or vendor-HAL hooks
successfully processed audio in a target app.

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

The rooted-emulator pass proves a narrow but important live slice: x86_64 `AudioRecord.read`
interception in the current process, plus service-side native `processBlock` routing through the
DSP. It does **not** prove the whole release install path. Attempts to install the refreshed zip
with `magisk --install-module /sdcard/Download/echidna-magisk.zip` on the rooted emulator images
returned `Incomplete Magisk install`, so Magisk flashing remains unverified here.

Requested coverage, explicitly:

| Requested item | Current status | What is covered / missing |
| --- | --- | --- |
| Live Zygisk module load + real hook install on **arm64 primary** | **Release-device-only / NOT verified here** | `arm64-v8a` native artifacts build and package, but no physical arm64 Magisk/Zygisk load, app-specialize, or hook-install run has been recorded. |
| LSPosed shim injection + snapshot read under SELinux | **Release-device-only / NOT verified here** | The shim APK builds and the snapshot-reader code exists, but LSPosed install, scoping, injected-process execution, and SELinux-gated snapshot access were not run. |
| SELinux enforcement + audio HAL behavior on real hardware | **Release-device-only / NOT verified here** | The app reports SELinux/audio-stack signals, but no enforcing physical device has proven vendor-HAL capture processing. |
| x86_64 trampoline under real injection | **Rooted-emulator slice verified; broader release path NOT verified** | Rooted Android 13/14 x86_64 emulators prove one live `AudioRecord.read` hook path with `processed=1`; Magisk manager flash/reboot and arbitrary target-app injection are not claimed. |
| armv7 degrade behavior | **Build/code-path covered; runtime device behavior NOT verified** | `armeabi-v7a` artifacts build, and the hook path is intended to fail closed with `hook_unsupported_abi`; no real armv7 device run has proved the runtime telemetry. |
| APK install -> service bind -> live AIDL round-trip | **Verified on emulator/rooted emulator** | App instrumentation covers installable APK execution, in-app `EchidnaControlService` bind, and live AIDL round-trips; this does not prove Magisk/LSPosed hardware paths. |

Still not claimed as verified:

- **Magisk Manager / `magisk --install-module` flash + reboot** — including magic-mount namespace
  resolution of `dlopen("libech_dsp.so")` against `system/lib(64)`.
- **Full Zygisk lifecycle on release hardware** — loading from Magisk at zygote time, specializing
  into arbitrary target apps, and surviving reboot/module-manager install paths.
- **Non-`AudioRecord` hook managers** — AAudio, OpenSL ES, AudioFlinger, tinyalsa, and HAL-level
  paths still need live device coverage.
- **Live LSPosed shim injection** — the shim builds and its snapshot reader is implemented, but
  LSPosed installation, scoping, injected-process execution, and SELinux access were not exercised.
- **`magiskpolicy --live` SELinux relaxations** and the SELinux / audio-HAL interaction on a real
  enforcing device.
- **arm64 primary hardware and armeabi-v7a runtime behavior** — x86_64 has rooted-emulator coverage
  for the `AudioRecord` slice; arm64 still needs physical-device proof, and armv7 intentionally
  fails closed.
- **Live multi-reader profile-sync under SELinux** — the service-owned snapshot publisher and
  native/LSPosed reader code paths build, but simultaneous hooked-app delivery still needs rooted
  device validation under enforcing policy.

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
   `libechidna.so` for the in-app JNI `dlopen` path.
4. **Install the LSPosed shim (Java fallback / control plane).** Install LSPosed (Zygisk
   flavour), enable the Echidna module in LSPosed, and scope it to the target app(s). This
   drives the `ProfileSyncBridge` snapshot path when the native hook is degraded or unavailable.
5. **Grant the per-app whitelist.** In the companion app, enable the target package in the
   whitelist / app-binding. The system is **fail-closed** — unlisted processes are never hooked.
   Avoid scoping the same target app into both Zygisk and LSPosed unless this test is explicitly
   checking duplicate-hook behavior.
6. **Open the target app** (e.g. a voice / call / recorder app) so its media process spawns and
   the Zygisk module hooks the audio path.
7. **Verify pitch/effect live.** Select a pitch/formant preset (e.g. Darth Vader or Helium),
   speak, and confirm the transform in the target app's monitor or sidetone. Cross-check on-device
   via **Diagnostics**: the **Tuner** should show a non-zero *Detected Hz / offset (cents)*, the
   **latency histogram** should populate (no longer *"No latency data yet"*), the CPU heatmap
   should show samples, and **Engine** should read *Installed / active* instead of *Not Installed*.
   The **Compatibility Wizard** should then report Magisk **installed** + Zygisk **enabled** — the
   inverse of the unrooted state captured on the emulator.

---

*Sources: release-readiness gate (`t2-e20`), docker native → magisk container run (`t3-e1b`),
emulator install/launch/nav + crash-free confirmation (`t3-e3`), instrumentation + crash
discovery (`t2-e18`), crash fix + proof (`t2-e26`), native tests (`t2-e17`), rooted Android
13/14 app instrumentation, and rooted Android 13/14 `AudioRecord` interception probe.*
