# Echidna — Real-Time Voice Changer for Android

[![Native Build](https://img.shields.io/github/actions/workflow/status/supermarsx/echidna/native-ci.yml?label=native%20build)](https://github.com/supermarsx/echidna/actions)
[![Android Build](https://img.shields.io/github/actions/workflow/status/supermarsx/echidna/android-ci.yml?label=android%20build)](https://github.com/supermarsx/echidna/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](license.md)
[![Spec Coverage](https://img.shields.io/badge/spec-native--first-blueviolet)](spec.md)

Echidna is the LSPosed/Zygisk-based, native-first voice changer designed for
consented, real-time voice transformation across Android apps. The module hooks
AAudio, OpenSL ES, AudioRecord, and AudioFlinger paths directly inside target
processes to minimise latency while keeping Java fallbacks for compatibility.
The companion app exposes rich preset, diagnostics, and safety controls tailored
for power users and researchers. 【F:spec.md†L1-L159】【F:spec.md†L160-L259】

## Project Highlights

- **Native-first audio interception** across AAudio, OpenSL ES, and AudioRecord,
  with layered fallbacks down to libc `read()` for edge cases. 【F:spec.md†L31-L125】
- **High-performance DSP pipeline** built around `libech_dsp.so`, supporting
  synchronous and hybrid processing modes with SIMD optimisations. 【F:spec.md†L126-L159】
- **Root-aware deployment** via Magisk/Zygisk, backed by a companion app for
  installation, diagnostics, and per-app profile management. 【F:spec.md†L19-L76】【F:spec.md†L160-L259】
- **Preset-rich user experience** featuring effect chains, Auto-Tune controls,
  import/export flows, and diagnostics dashboards. 【F:spec.md†L260-L360】
- **Safety tooling** including panic bypasses, latency watchdogs, and fallback
  modes to preserve call stability on marginal devices. 【F:spec.md†L126-L159】【F:spec.md†L160-L259】

## Repository Layout

```
android/           # LSPosed shim & companion app sources (Kotlin/Java)
native/            # Zygisk module, DSP engine, and build scripts (C++17)
spec.md            # Native-first product specification and roadmap
license.md         # MIT license covering the entire codebase
```

Additional scaffolding such as the Magisk module packaging and CI workflows are
referenced in the specification and will live alongside the above directories as
they are implemented. 【F:spec.md†L160-L259】

## Architecture Overview

The system is composed of four cooperating layers that communicate through
shared memory and lightweight IPC channels. 【F:spec.md†L31-L159】

1. **Zygisk Native Module (`libechidna.so`)** – Injected into target processes,
   responsible for symbol discovery, inline hooking, buffer interception, and
   dispatching audio blocks to the DSP engine. It exposes a small C API for
   status and control queries. 【F:spec.md†L31-L125】
2. **LSPosed Java Shim** – Provides Java-level hooks for apps that never cross
   into native audio paths, forwarding buffers into the native engine and
   exposing per-process toggles. 【F:spec.md†L40-L125】
3. **DSP Engine (`libech_dsp.so`)** – Runs synchronous or hybrid processing
   chains featuring noise gates, EQ, compression, pitch/formant shifts, Auto-Tune,
   and reverb with NEON/AVX acceleration. 【F:spec.md†L126-L159】【F:spec.md†L260-L360】
4. **Control & Config Service / Companion App** – Ships Magisk installers,
   manages preset JSON, controls latency modes, and presents diagnostics and
   safety controls to the user. 【F:spec.md†L76-L259】【F:spec.md†L260-L360】

Data/processing flow (monospace sketch):
```
Capture source
    ↓ hooks (AAudio → OpenSL → AudioFlinger → AudioRecord → libc read → tinyalsa → audio HAL)
    ↓ DSP (libech_dsp.so) via echidna_process_block
    ↓ Processed PCM delivered back to app/system consumer
```

## Feature Matrix

| Area | Key Capabilities |
| --- | --- |
| Native Hooks | AAudio callbacks, OpenSL buffer queues, AudioRecord JNI, libc read fallbacks, AudioFlinger client intercepts. |
| DSP Modes | Low-latency in-callback processing, hybrid worker pipeline, SIMD-tuned stages. |
| Effects | Gate, EQ, Compressor/AGC, Pitch, Formant, Auto-Tune, Reverb, Dry/Wet mix. |
| Presets | Built-in catalog (Natural Mask, Darth Vader, Helium, Radio Comms, Studio Warm, Robotizer, Cher-Tune, Anonymous) with tags. |
| Safety | Panic bypass, auto-bypass on overload, SELinux-aware deployment, per-app whitelisting. |
| Diagnostics | Latency histograms, CPU sampling, symbol scan logs, tuner and visualizers. |

All features align with sections 4–16 of the specification; consult `spec.md`
for the canonical requirements and roadmap. 【F:spec.md†L31-L360】

## Getting Started

1. **Review the spec:** Understand the target platform matrix, hook order, DSP
   constraints, and safety requirements before contributing. 【F:spec.md†L1-L159】
2. **Prepare your device:** Enable Magisk with Zygisk, unlock bootloader if
   required, and install LSPosed for the Java shim. 【F:spec.md†L19-L125】
3. **Clone & sync:**
   ```bash
   git clone https://github.com/supermarsx/echidna.git
   cd echidna
   ```
4. **Build native components:** Follow the upcoming `native/README` (to be added)
   for CMake-based builds targeting `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
   Ensure you package the resulting libraries into the Magisk module layout. 【F:spec.md†L76-L259】
5. **Build Android artifacts:** Use Android Studio or Gradle to compile the
   companion app and LSPosed module once the Android sources are available. 【F:spec.md†L76-L259】【F:spec.md†L260-L360】
6. **Deploy & verify:** Flash the Magisk module, reboot, install the companion
   app, and run the compatibility wizard and diagnostics views before enabling
   hooks for production apps. 【F:spec.md†L160-L360】

## Roadmap Snapshot

The near-term milestones focus on delivering the native pipeline first, then
broadening compatibility and UX polish. 【F:spec.md†L160-L259】

- **v0.1:** Native module skeleton with AAudio proof-of-concept and minimal UI.
- **v0.2:** Comprehensive OpenSL support, symbol heuristics, per-app profiles.
- **v0.5:** Hybrid processing, vendor HAL probes, optional HAL shim packaging.
- **v1.0:** Multi-device certification, plugin API, full diagnostics suite.

Refer to the specification for the full deliverables list and future stretch
goals. 【F:spec.md†L160-L259】

## Contributing

We welcome contributions that adhere to the native-first architecture and DSP
performance requirements outlined in the spec. Please:

- Match the coding standards described in `agents.md` for C++17, Kotlin/Java,
  and scripting work.
- Keep commits focused, with descriptive messages and documented test results.
- Add or update documentation when you change user-visible behaviour or preset
  formats.

Before opening a pull request, ensure you have run the relevant native, Android,
and DSP tests, or document why they could not be executed. 【F:agents.md†L1-L54】

## License

Echidna is released under the MIT License. See [license.md](license.md) for
full details. 【F:license.md†L1-L20】
