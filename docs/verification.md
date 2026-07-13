# End-to-End Verification

This page is the honest answer to *"does Echidna actually work end to end?"* It separates
what was **really built, run, and measured on this build host and emulator** (reproducible)
from what is **device-gated** — behaviour that can only be exercised on a rooted
Magisk + Zygisk device and is therefore documented, not claimed as verified.

> **Reading guide.** Every row in the "Verified" table below corresponds to a real build,
> test run, or on-device action with recorded output. The "Device-gated" section lists
> exactly what was **not** run here, and why, followed by a step-by-step procedure to
> reproduce it on real hardware. Nothing in the device-gated section is presented as
> having passed.

Verification host: Windows 11, PowerShell + Bash. Toolchain: Android SDK `C:\android-sdk`,
NDK r27 (`27.0.12077973`), JBR OpenJDK 21, Gradle 8.5 (wrapper), CMake 4.2.1 / Ninja 1.13.2,
apksigner (build-tools 34.0.0), Docker Desktop 4.72.0 (engine 29.4.2), emulator `emulator-5554`
(AVD `echidna_e2e`, API 34 / Android 14, x86_64, google_apis, **unrooted**).

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
| **App install + launch** | `adb install -r app-debug.apk`; launch `.MainActivity` | **PASS** — install `Success`, `MainActivity` resumed, process stays up |
| **Crash-free relaunch** | 8 cold relaunches (`am force-stop` + `am start`) with a persisted `echidna_presets.json` present, scanning logcat | **PASS 8/8** — 0 crashes, process alive every launch. This is the exact trigger of the startup crash found by instrumentation (see highlight below) |
| **In-app screen navigation** | `adb input` nav + `uiautomator dump` across screens | **PASS (honest)** — 6 screens rendered live and navigated (Dashboard, Preset Manager, Effects Editor, Diagnostics, Settings, Compatibility Wizard) with correct *module-not-active* state on the unrooted emulator; the Whitelist Editor was wired into the nav graph afterward (t2-e27) and verified to render; the QS tile is a registered `TileService` that cannot be driven headless-unrooted (its state source is unit-verified) |
| **In-app control service bind** | `dumpsys activity services com.echidna.app` | **PASS** — live `ServiceRecord` for `EchidnaControlService` with an active `ConnectionRecord` (BIND_AUTO_CREATE); the app binds its in-app control service at runtime |
| **Format gate** | `clang-format --dry-run -Werror` over all tracked native/android C/C++ | **PASS** — 0 non-conformant of 82 files |

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

## 2. Device-gated — NOT verified here (requires a rooted Magisk + Zygisk device)

The verification host is a stock **unrooted** AVD (no Magisk, no Zygisk; SELinux enforcing with
no policy tool, so the app runs its Java-only fallback — exactly as the on-device Compatibility
Wizard reports: *Magisk not installed, Zygisk disabled*). The following therefore **cannot be
exercised here and are not claimed as verified.** They require real rooted hardware:

- **Live audio hooking** — symbol resolve + inline patch inside a live victim media process, and
  the on-device Zygisk module load into audio processes (AAudio / OpenSL / AudioFlinger per HAL).
- **Magisk flash + reboot** — `magisk --install-module` / Manager flow, reboot, and the
  **magic-mount namespace resolution** of the bare `dlopen("libech_dsp.so")` against the
  magic-mounted `system/lib(64)`.
- **`magiskpolicy --live` SELinux relaxations** and the SELinux / audio-HAL interaction on a
  real enforcing device.
- **x86_64 trampoline under real injection** — the inline-hook decoder passed a host harness
  (23/23) but the full injection into a live process is emulator/device-pending.
- **armeabi-v7a runtime hooking** — ships as **graceful-degrade** (builds and loads, but
  `install()` returns false and fails closed; Thumb-2/IT-block relocation is untested).
- **Single-holder profile-sync socket** — a known limitation, not a bug: do **not** run the
  Zygisk module and the LSPosed shim simultaneously on the same device, and with multiple hooked
  apps only the last binder receives snapshot pushes (others stay fail-closed).

---

## 3. Reproduce on a real device (the device-gated procedure)

Run this on a **rooted physical device with Magisk 24.0+ and Zygisk enabled** to exercise the
live path that the emulator cannot:

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
   *(Known limitation: the profile-sync socket is single-holder — do not run the Zygisk module
   and the LSPosed shim at the same time.)*
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
discovery (`t2-e18`), crash fix + proof (`t2-e26`), native tests (`t2-e17`).*
