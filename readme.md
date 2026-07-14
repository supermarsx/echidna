<div align="center">

# Echidna

### Native-first, root real-time voice changer for Android

Hook the audio path directly inside target apps — transform your voice in calls,
recordings, and games with a low-latency native DSP engine, per-app profiles, and
a rich companion app.

[![CI](https://img.shields.io/github/actions/workflow/status/supermarsx/echidna/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/supermarsx/echidna/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/github/actions/workflow/status/supermarsx/echidna/docs.yml?branch=main&label=docs&style=flat-square)](https://github.com/supermarsx/echidna/actions/workflows/docs.yml)
[![License](https://img.shields.io/badge/license-MIT--derived%20noncommercial-orange.svg?style=flat-square)](license.md)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84.svg?style=flat-square)](docs/build-install.md)
[![Root](https://img.shields.io/badge/root-required-critical.svg?style=flat-square)](docs/limitations.md)
[![Runtime](https://img.shields.io/badge/runtime-Zygisk%20%2B%20LSPosed-blueviolet.svg?style=flat-square)](docs/architecture.md)

<br />

<img src="docs/assets/screenshots/01-dashboard.png" alt="Echidna dashboard — active preset, latency mode, and engine status" width="300" />
<br />
<sub>Actual app entry screen captured from the emulator; the full gallery is in the docs.</sub>

</div>

---

## Table of Contents

- [What is Echidna](#what-is-echidna)
- [High-Risk Notice](#high-risk-notice)
- [Features](#features)
- [Quickstart](#quickstart)
- [Release Files](#release-files)
- [Install Methods](#install-methods)
- [How Echidna Functions](#how-echidna-functions)
- [Architecture](#architecture)
- [Documentation](#documentation)
- [Comparison & Limitations](#comparison--limitations)
- [Contributing](#contributing)
- [License](#license)

---

## What is Echidna

Echidna is a **native-first, root-based real-time voice changer** for Android. Instead of
routing audio through a virtual device or a userspace app, it uses **Zygisk + LSPosed** to
inject a native module (`libechidna.so`) directly into target processes and intercept the
audio path at its source — AAudio, OpenSL ES, `AudioRecord`, and AudioFlinger — with layered
fallbacks down to libc `read()`. Captured PCM is processed by a C++17 DSP engine
(`libech_dsp.so`) and handed back to the app or system consumer with minimal added latency.
A Jetpack Compose companion app drives presets, per-app profiles, diagnostics, and safety
controls. It targets power users and researchers on rooted devices; it is not a Play Store app.

## High-Risk Notice

Echidna is for a narrow subset of Android users who already understand bootloader unlocks, Magisk,
Zygisk, LSPosed, SELinux, sideloaded APKs, and recovery. It can affect system audio processes and
root-level boot behavior. If you misconfigure it, flash it on the wrong device, combine hook stacks
badly, or recover incorrectly, you can soft-brick a phone and, in extreme cases, contribute to a
hard-brick scenario. You are responsible for backups, recovery images, device-specific research,
and every install decision. The software is provided as-is, without warranties or guarantees of
functioning.

## Features

| Area | Capabilities |
| --- | --- |
| **Native hooks** | AAudio callbacks, OpenSL buffer queues, `AudioRecord` JNI, AudioFlinger client intercepts, libc `read()` fallback. |
| **DSP effects** | Noise gate, EQ, compressor/AGC, pitch shift, formant shift, Auto-Tune, reverb, dry/wet mix — SIMD-tuned. |
| **Presets** | Built-in catalog (Natural Mask, Darth Vader, Helium, Radio Comms, Studio Warm, Robotizer, Cher-Tune, Anonymous) with import/export. |
| **Per-app whitelist** | Enable Echidna per application and bind a specific preset to each app. |
| **Latency modes** | Low-latency in-callback processing and a hybrid worker pipeline. |
| **Quick Settings tile** | Toggle the engine from the notification shade. |
| **Diagnostics** | Latency and CPU sampling, symbol-scan logs, tuner, compatibility wizard. |
| **Safety** | Panic bypass, auto-bypass on overload, SELinux-aware deployment, fail-closed defaults. |

## Quickstart

Full, host-verified steps live in **[docs/build-install.md](docs/build-install.md)**. In short:

```bash
git clone https://github.com/supermarsx/echidna.git
cd echidna

# 1. Build the per-ABI native libraries (needs the Android NDK)
ANDROID_NDK=/path/to/ndk bash tools/build_native_ndk.sh
#    …or reproducibly in Docker:
docker build -t echidna-native docker/native-build && \
docker run --rm -v "$PWD:/workspace" echidna-native

# 2. Package the flashable Magisk module
bash tools/build_magisk_module.sh   # -> out/echidna-magisk.zip

# 3. Build the companion APK
cd android/app && ./gradlew :app:assembleDebug   # or assembleRelease with a keystore
```

Then, **on a rooted device** (Magisk 24+ with Zygisk enabled, LSPosed installed):

1. Flash `out/echidna-magisk.zip` in Magisk and reboot.
2. Install the companion APK and open it.
3. Enable the Echidna module in **Zygisk / LSPosed**.
4. Add your target apps in the **per-app whitelist** and pick a preset.
5. Run the **compatibility wizard** and **diagnostics** before enabling hooks for production apps.

> Do not run the Zygisk module and the LSPosed shim simultaneously — the profile-sync socket is
> single-holder. See [Limitations](docs/limitations.md).

Current validation goes beyond a compile: Android instrumentation proves the in-app control service
and native `processBlock` path on Android 13/14 emulators, and a rooted-emulator
`AudioRecord.read` probe shows captured buffers routed through DSP (`processed=1`). Magisk module
flashing and live LSPosed injection still need release-device validation.

## Release Files

Every successful `main` CI release publishes a GitHub Release with separate installable files:

- `echidna-companion-<tag>.apk` - install this Android companion app first. It
  contains the UI and in-process control service.
- `echidna-magisk-<tag>.zip` - flash this in Magisk to install the native
  Zygisk/DSP engine.
- `echidna-lsposed-shim-<tag>.apk` - optional Java fallback for LSPosed-scoped
  apps that use `AudioRecord`.
- `echidna-apks-<tag>.zip` - convenience package containing the companion APK
  and LSPosed shim APK.
- `echidna-native-libs-<tag>.zip` - raw per-ABI `.so` files for inspection or
  integrators, not the normal install path.
- `echidna-complete-<tag>.zip` - all release assets plus `RELEASE_ARTIFACTS.md`;
  useful for archiving a full release.
- `SHA256SUMS.txt` - hashes for verifying downloaded release files.

For a normal install, use the companion APK plus the Magisk zip. Add the LSPosed shim only when you
need or are testing the Java fallback path. Do not scope the same target app into both Zygisk and
LSPosed unless you are intentionally testing duplicate-hook behavior.

## Install Methods

- **Prebuilt release:** download the release assets, verify `SHA256SUMS.txt`,
  install the companion APK, flash the Magisk zip, reboot, then run Diagnostics.
- **Local host build:** build APKs with Gradle, cross-compile native libraries
  with `tools/build_native_ndk.sh`, then package `out/echidna-magisk.zip`.
- **Docker build:** use `docker/compose.yaml` to run native build, Android
  build, and Magisk packaging with pinned toolchains.
- **Development/debug APK only:** install `app-debug.apk` when you only want to
  inspect UI behavior. No audio is transformed until the Magisk module/native
  path is installed.

Device activation is always manual: enable Zygisk in Magisk, install or enable LSPosed only if you
need the fallback shim, whitelist target apps in the companion, then run the compatibility wizard.

## How Echidna Functions

Echidna has several operating paths:

- **Native-first Zygisk path:** the Magisk module loads `libechidna.so` into target processes,
  attaches native audio hooks, and routes PCM through `libech_dsp.so`.
- **LSPosed Java fallback:** the optional shim covers Java `AudioRecord` apps that never hit the
  native hook path. It is a fallback, not a replacement for the Magisk module.
- **Compatibility mode:** the app can prefer safer fallback behavior on fragile devices or unknown
  HALs while still keeping controls available.
- **Bypass and panic:** bypass passes audio through untouched; panic forces bypass/master-off for a
  configured hold window so the user can recover from bad audio behavior.

## Architecture

Echidna is four cooperating layers. The **companion app** hosts an in-process control service
(AIDL + JNI) that manages presets and profiles and publishes a snapshot over a shared-memory
`ProfileSyncBridge`. The **Zygisk native module** injects into target processes, discovers and
inline-hooks the audio symbols, and streams buffers to the **DSP engine**. An **LSPosed Java
shim** covers apps that never cross into native audio paths. Read the full walkthrough — hook
order, IPC, and data flow — in **[docs/architecture.md](docs/architecture.md)** and the reasoning
behind each choice in **[docs/design-rationale.md](docs/design-rationale.md)**.

```
Capture source
    ↓ hooks (AAudio → OpenSL → AudioFlinger → AudioRecord → libc read)
    ↓ DSP (libech_dsp.so) via echidna_process_block
    ↓ processed PCM delivered back to the app / system consumer
```

**Stack:** Kotlin + Jetpack Compose (Material3) companion app · in-app control service (AIDL, JNI) ·
Java/Xposed LSPosed shim · C++17 native (CMake + NDK r27, per-ABI) · flashable Magisk/Zygisk module.

## Documentation

The full documentation site is built with MkDocs Material and published to
**[GitHub Pages](https://supermarsx.github.io/echidna/)**.

| Topic | Page |
| --- | --- |
| Architecture & data flow | [architecture.md](docs/architecture.md) |
| Design rationale | [design-rationale.md](docs/design-rationale.md) |
| Why this is hard to build | [why-hard.md](docs/why-hard.md) |
| Build & install | [build-install.md](docs/build-install.md) |
| DSP & effects reference | [dsp-effects.md](docs/dsp-effects.md) |
| Screenshots gallery | [screenshots.md](docs/screenshots.md) |
| Comparison vs alternatives | [comparison.md](docs/comparison.md) |
| Limitations | [limitations.md](docs/limitations.md) |
| E2E verification report | [verification.md](docs/verification.md) |
| Vendor HAL analysis | [vendor-hal-analysis.md](docs/vendor-hal-analysis.md) |
| Developer guide · Signing · Magisk release | [developer_readme.md](docs/developer_readme.md) · [signing.md](docs/signing.md) · [magisk_release.md](docs/magisk_release.md) |

## Comparison & Limitations

Unlike app-level tools (RootlessJamesDSP, Voicemod-style desktop apps, Clownfish) or generic
Magisk audio-mod modules, Echidna intercepts **inside the target process** for true real-time,
per-app transformation. That power comes with constraints: it **requires root**, behaviour varies
across OEM audio HALs and SELinux policies, `armeabi-v7a` hooking degrades gracefully (fails
closed), and the profile-sync socket is single-holder. See
**[comparison.md](docs/comparison.md)** and **[limitations.md](docs/limitations.md)** for the honest
detail.

## Contributing

Contributions that respect the native-first architecture and DSP performance goals are welcome:

- Follow the coding standards in [`agents.md`](agents.md) for C++17, Kotlin/Java, and scripting.
- Keep commits focused, with descriptive messages and documented test results.
- Update the relevant docs when you change user-visible behaviour or preset formats.
- Run the native, Android, and DSP tests before opening a pull request (or document why they
  could not run). See [build-install.md](docs/build-install.md) and [verification.md](docs/verification.md).

## License

Echidna is released under an **MIT-derived non-commercial license** with a
separate commercial license requirement — see [license.md](license.md).
Use it responsibly: only transform audio you are authorised to modify, and comply with the terms
of the apps and services you use it with.
