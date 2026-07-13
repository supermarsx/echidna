# LSPosed Real‑Time Voice Changer (Echidna)

> Codename: **Echidna** (fast, spiky, cute, dangerous if mishandled)

---

## Executive summary (native‑first)

This document is an updated, **native‑first** specification for the LSPosed/Zygisk voice‑changer module. The project will use root (Magisk/Zygisk) where necessary and prioritize **native hooks (AAudio/OpenSL/AudioFlinger/AudioRecord native bridges)** to provide the broadest compatibility and lowest latency across apps that bypass Java APIs.

Key decisions taken:

- **Native hooks are the primary implementation target** (AAudio/OpenSL/native AudioRecord). Java hooks are fallback/compatibility only.
- **Root / Zygisk** usage is acceptable and expected for native hooking and optional HAL/SELinux tweaks.
- Maintain the previously defined UI, DSP pipeline, profiles, and safety features, but expand deployment, low‑level hook design, and installation/compatibility instructions.

## 1) Scope & Use Cases (unchanged)

Same as before: lawful, consented voice modification, privacy masking, VTubing, accessibility, QA/research in controlled environments. Strong consent & legal warnings in UI.

## 2) Target Platform & Compatibility (native emphasized)

- **Android:** API 26 → 35+. Primary testing on API 29, 31, 33, 34.
- **Hooking frameworks:** **Zygisk (Magisk)** + **LSPosed** for Java/NON‑root UI; native hooks implemented with Zygisk native modules (C++). LSPosed used for Java fallbacks and easier per‑process injection when possible.
- **ABIs:** arm64‑v8a (primary), armeabi‑v7a, x86\_64.
- **Audio stacks to support:** Java AudioRecord, OpenSL‑ES, AAudio, AudioFlinger (service), vendor HAL paths.
- **Permissions / capabilities:** Root required for deploying Zygisk modules and optional HAL modifications. App retains RECORD\_AUDIO for self‑tests only.

## 3) High‑Level Native Architecture (detailed)

**Runtime components:**

1. **Zygisk Native Module (core)** — `libechidna.so`
  
  - Loaded into target processes by Zygisk. Runs in‑process to minimize IPC and latency.
  - Responsible for hooking native symbols (JNI bridges, OpenSL callbacks, AAudio callbacks) and for calling the DSP engine in shared memory.
  - Exposes a tiny in‑process control API (C API) that the JS/LSPosed shim or UI binder can query for status.
2. **LSPosed Java Shim (optional)**
  
  - For apps using Java APIs, LSPosed hooks `AudioRecord.read` and forwards buffers to `libechidna.so` via JNI or shared memory.
  - Provides process‑aware profile assignment and simple Java‑side toggles.
3. **DSP Engine (native; JNI lib)** — `libech_dsp.so`
  
  - High‑performance C++ pipeline: ringbuffers, SIMD, low‑latency FFT/pitch engines.
  - Exposes API for processing a single audio block synchronously: `process_block(in_ptr, out_ptr, frames, sr, channels)`.
  - Built against SoundTouch/RubberBand algorithms + custom formant module.
4. **Control & Config Service (Magisk module or companion app)**
  
  - Holds profiles on disk (JSON), pushes configs to hooked processes (via binder, unix socket, or shared memory), and updates the in‑process engine parameters.
  - Runs a privileged helper (root) to manage device‑wide settings (optional HAL toggles) and to install/uninstall native modules.
5. **Companion App (UI)**
  
  - Hands‑on controls: profiles, scopes, diagnostics. Talks to control service via AIDL/Unix sockets.
  - Installs/uninstalls Magisk module helper via intent or external installer guidance (Magisk requires user action).

## 4) Native Hooking Strategy — Technical Plan

We will implement layered hooks so we cover both Java and native audio consumers.

### 4.1 Symbol discovery & hooking method

- Use **Zygisk native module** to run inside the app process with root privileges provided by Magisk/Zygisk.
- Hook using **inline trampolines** (PLT/GOT/elf trampolines) with a small hooking runtime (e.g., libxhook or custom inline hook) to intercept specific exported symbols.
- Prefer hooking stable JNI symbols **and** vendor libc functions that wrap audio reads.

### 4.2 Primary hook targets (priority order)

1. **AAudio callbacks** — apps using AAudio (native low‑latency API) register `AAudioStream_dataCallback` or supply Java‑to‑native bridges. Hook the AAudio data callback function to intercept capture buffers. Symbol patterns to look for: `AAudioStream_read`, `AAudioStream_write`, `AAudioStream_dataCallback`.\* (vendor names may be mangled)
2. **OpenSL ES** — Hook `SLRecord::Process` or `(*SLRecordItf)->Enqueue` / `(*SLRecordItf)->GetBufferQueueState`. Intercept `(*SLRecordItf)->RegisterCallback` and buffer callbacks.
3. **AudioRecord native bridge** — Hook `AudioRecord::read` native implementations, and JNI bindings like `android_media_AudioRecord_native_read()` or similarly named methods in libc. Android codebase has `android_media_AudioRecord_*` native bridges; vendor names vary.
4. **libc read on /dev/snd or ALSA wrapper** — as fallback, intercept `read()` calls from processes that directly access device nodes (rare on stock Android but possible on rooted/custom ROMs).
5. **AudioFlinger client read path** — for extreme cases, patch `AudioFlinger` or its HAL (requires vendor device‑specific work and may need Magisk module hooking at system level).

### 4.3 Hooking details & robustness

- Implement **symbol resolution heuristics**: attempt direct symbol lookup, then scanning PLT for candidate functions by signature, then pattern match exported symbols.
- Implement **versioning shim**: the hook adapts to API level and vendor differences using a probe on attach to pick correct hook point.
- Provide **fallbacks**: if AAudio hook fails, fall back to OpenSL, then JNI bridge.
- Provide **per‑process whitelisting** right inside the module to reduce attack surface and only install heavy hooks for selected apps.

## 5) DSP pipeline & low‑level performance

- DSP runs in the same process context synchronously inside the hooked read callback for minimal copies. Use in‑place processing when allowed by API.
- Use lock‑free ringbuffers for buffering between callback thread and optional background processing thread if heavy ops are requested.
- Implement two processing modes:
  1. **Synchronous (in‑callback)**: small block sizes, minimal latency. Only cheap transforms (pitch shift with phase vocoder, simple EQ/compression). Target < 30 ms.
  2. **Hybrid (callback→worker)**: callback copies to ring buffer, worker applies heavy transforms (formant, high‑quality FFT) and returns; higher latency but preserves quality for non real‑time uses.
- Use NEON/AVX (where available) optimizations and precompile per ABI.

## 6) Security, SELinux, and Root considerations

- **Zygisk** runs modules in a privileged context but still respects SELinux. Some devices enforce stricter policies — expect some installs to require permissive or vendor module path tweaks.
- Provide **Magisk installer zip** that installs `libechidna.so` into appropriate paths and registers the module with Zygisk.
- For devices with strict SELinux, provide fallback that only runs LSPosed Java hooks (less powerful) and show compatibility notice.
- Avoid making system‑wide persistent changes by default — prefer in‑memory hooks and Magisk module controlled installation.

## 7) Installation & Deployment Steps (developer & user flows)

**Developer flow** (build & test):

1. Build `libechidna.so` for arm64 and other ABIs. Build `libech_dsp.so` and package into Magisk module structure.
2. Deploy Magisk module zip to device (adb push or user installs via Magisk Manager). Module registers with Zygisk and is loaded into target processes.
3. Install companion App from debug APK; grant necessary permissions (foreground, notification). App communicates with Magisk helper via local socket.

**User flow (non‑developer)**:

1. User installs Magisk and enables Zygisk (instructions in app with warnings). Root required only for native engine; app can function in Java‑only mode without root.
2. Install Magisk module from app or manual install. Reboot to enable module.
3. Install companion app APK, open, accept warnings, run compatibility check. Create profile and enable hooks for target apps.

## 8) Companion App — Native features & UI changes

Enhance UI to surface native engine status and advanced options:

- **Engine status**: shows `Native Engine: Installed/Active/Requires Reboot/Failed`.
- **Compatibility Wizard**: probes device for AAudio/OpenSL presence, SELinux mode, Audio HAL vendor.
- **Native engine toggles**: allow enabling **synchronous** vs **hybrid** processing per‑profile.
- **Root helper diagnostics**: magisk module version, zygisk enabled, module loaded into process (yes/no). Show process list with check icons when module is attached.

## 9) Diagnostics & Instrumentation (native‑specific)

- **Per‑process latency histogram** from callback timestamps.
- **CPU hot‑path sampling** to show which DSP stages cost most.
- **Symbol scan logs**: which hook points succeeded/failed (useful for device support matrix).
- **Fallback telemetry** (opt‑in): report aggregated success/failure rates by Android API level and CPU family (no app names unless user explicit) to help maintain compatibility.

## 10) Developer APIs & Extensibility

- **C ABI**: `echidna_set_profile(json_ptr, len)`, `echidna_process_block(in_ptr, out_ptr, frames, sr, channels)`, `echidna_get_status(buf, len)`.
- **Local socket / binder** for companion app to push profiles into running process.
- **Plugin API**: allow third‑party DSP modules (signed) to be loaded into `libech_dsp.so` in controlled mode — optional for power users.

## 11) Testing plan (native heavy)

- Unit and fuzz tests for symbol detection & hooking code.
- ABI compatibility matrix: test on Pixel, Samsung, OnePlus on different API levels.
- Real‑world E2E: Discord/Telegram/WhatsApp calls with lab counterpart; measure latency and perceived quality.
- Stress tests: sustained capture with heavy CPU load, simulated XRuns, crash injection.

## 12) Safety & Emergency UX (native risks)

- **Global panic**: hardware button combo to instantly bypass native engine and unload module hooks for N minutes.
- **Auto‑bypass**: if in‑callback processing crosses threshold for X consecutive callbacks, auto‑bypass native hook for that process and notify user.
- **Rollback**: Magisk module uninstall helper that attempts safe cleanup (but instruct user to uninstall via Magisk Manager if needed).

## 13) Roadmap adjustments (native first)

- **v0.1**: Native module skeleton + proof‑of‑concept on AAudio + simple pitch/EQ in callback, companion app minimal.
- **v0.2**: Broad OpenSL support, improved symbol heuristics, per‑app profiles, watchdogs.
- **v0.5**: Hybrid processing, vendor HAL probes, optional HAL shim (Magisk) for advanced routing.
- **v1.0**: Robust multi‑device compatibility, plugin API, polished UI/diagnostics.

## 14) Implementation notes & quick code pointers

- Inline hook libraries: consider `MSHook` style trampolines or `frida-gum` but prefer small custom trampolines for size.
- Use `dlopen`/`dlsym` + `/proc/<pid>/maps` scanning when needed to find private symbols.
- Use thread‑affinity & `SCHED_FIFO` only for bench/test modes — avoid in production to prevent system instability.

## 15) Deliverables

- Magisk module zip (native libs + module scripts)
- Zygisk native module (`libechidna.so`) and native hooking runtime
- Companion App APK (UI + diagnostics)
- Sample profiles & presets
- CI pipelines for ABI builds and emulator tests

---

### Next steps I can take right now

Choose one and I will produce the requested artifact in this chat:

1. **Native module skeleton (C/C++)** with example hook for `AAudioStream_read` (arm64), including `CMakeLists.txt` and basic DSP stub.
2. **Zygisk Magisk module layout** and install scripts + example module `module.prop` and updater‑script.
3. **Companion app UI mockups** (screens + resource names + navigation flow) updated for native engine controls.
4. **All three** (I will produce a compact but complete repo scaffold with module, installer, and app UI files).

Pick an option and I’ll generate code + files accordingly.

---

## 16) UI Controls & Preset Management — Detailed Spec

### 16.1 Global controls (Dashboard)

- **Master Enable/Bypass**: Toggle switch.
- **Profile Picker**: Dropdown + left/right arrows to cycle; shows preset name + badge (LL/HQ).
- **Dry/Wet Mix**: Continuous slider 0–100%.
- **Sidetone Level**: Slider −∞ (off) to −6 dB.
- **Latency Mode**: Segmented control → *Low‑Latency* (10–15 ms), *Balanced* (20 ms), *High‑Quality* (30 ms+).
- **Engine Status**: Native Engine state (Installed/Active/Bypassed/Failed) with tap‑for‑details.
- **Meters**: Input RMS/Peak, Output RMS/Peak, CPU %, End‑to‑end Latency (ms), XRuns.
- **Panic**: Button (and HW combo) → global bypass N minutes.

### 16.2 Preset management

- **New Preset**: Button opens modal → Name (required), Description (optional), Base on: [Empty | Current | Any preset].
- **Rename Preset**: Inline edit or contextual menu.
- **Duplicate Preset**: Copies with “(copy)” suffix.
- **Delete Preset**: Confirm dialog with “also remove per‑app bindings?” checkbox.
- **Set as Default**: Marks preset used for “unassigned apps”.
- **Import Preset(s)**: File picker (JSON). Shows preview → schema validate → conflict resolution (Rename/Overwrite/Skip).
- **Export Preset**: Single or multiple selection → JSON file. Option: “Export with safe ranges only”.
- **Share Preset**: Android share sheet with JSON.
- **Preset Tags/Badges**: LL (low latency), HQ (high quality), FX (heavy FX), NAT (natural).

### 16.3 Effects Chain Editor (per‑preset)

Common UI pattern for each effect: **On/Off toggle**, **Order handle** (drag), **Reset**, **A/B compare**, **Copy from Preset…**

**Chain (default order; reorderable):** Gate → EQ → Compressor/AGC → Pitch → Formant → **Auto‑Tune** → Reverb → Mix

#### A) Noise Gate

- Threshold (dBFS): −80 … −20 (default −45)
- Attack (ms): 1 … 50 (default 5)
- Release (ms): 20 … 500 (default 80)
- Hysteresis (dB): 0 … 12 (default 3)

#### B) Equalizer (Parametric)

- Bands: 3 / 5 / 8 (selector)
- Per band: Freq (20 Hz…12 kHz), Gain (−12…+12 dB), Q (0.3…10)
- Presets: Phone, AM Radio, Warmth, Bright, De‑mud, De‑harsh, V‑curve

#### C) Compressor / AGC

- Mode: **Manual** / **Auto (AGC)**
- Threshold (dBFS): −60 … −5 (default −24)
- Ratio: 1.2:1 … 6:1 (default 3:1)
- Knee: Hard/Soft (soft amount 0…12 dB)
- Attack (ms): 1 … 50
- Release (ms): 20 … 500
- Makeup Gain (dB): 0 … +12 (auto in AGC)

#### D) Pitch Shift

- Semitones: −12 … +12 (default 0)
- Fine (cents): −100 … +100 (default 0)
- Quality: **LL** (granular), **HQ** (phase vocoder)
- Preserve Formants: On/Off (delegates to formant stage when on)

#### E) Formant Shift

- Cents: −600 … +600 (default 0)
- Intelligibility Assist: On/Off (tilts EQ slightly to preserve clarity)

#### F) **Auto‑Tune (Pitch Correction)**

- Key: C, C♯/D♭, D, …, B
- Scale: Major, Minor, Chromatic, Dorian, Phrygian, Lydian, Mixolydian, Aeolian, Locrian
- Retune Speed (ms): 1 … 200 (fast→robotic; slow→natural)
- Humanize (%): 0 … 100 (adds tolerance around target note)
- Flex‑Tune (%): 0 … 100 (amount of off‑note allowance)
- Formant Preserve: On/Off
- Snap Strength (%): 0 … 100 (blend between input pitch and target)

#### G) Reverb

- Room Size: 0 … 100
- Damping: 0 … 100
- Pre‑Delay (ms): 0 … 40
- Mix: 0 … 50%

#### H) Mix (Global)

- Dry/Wet (%): 0 … 100
- Output Gain (dB): −12 … +12

### 16.4 Per‑App Scope (unchanged, with preset binding)

- Toggle **Hooked** per app
- Assign **Preset** per app (dropdown)
- Fallbacks: Bypass for telephony / only for VoIP / only with headset

### 16.5 Import/Export formats

- **Preset JSON schema** (excerpt):
  
  ```json
  {
  "name": "Darth Vader",
  "version": 1,
  "meta": {"tags":["LL","FX"], "description":"Low‑pitch + formant down + low‑pass"},
  "engine": {"latencyMode":"LL", "blockMs":15},
  "modules": [
    {"id":"gate","enabled":true,"threshold":-50,"attackMs":5,"releaseMs":80,"hysteresis":3},
    {"id":"eq","enabled":true,"bands":[{"f":120,"g":+3,"q":1.0},{"f":3500,"g":-2,"q":2.0}]},
    {"id":"comp","enabled":true,"mode":"manual","threshold":-26,"ratio":3.5,"knee":6,"attackMs":5,"releaseMs":120,"makeup":4},
    {"id":"pitch","enabled":true,"semitones":-6,"cents":0,"quality":"LL","preserveFormants":false},
    {"id":"formant","enabled":true,"cents":-250,"intelligibility":true},
    {"id":"reverb","enabled":true,"room":8,"damp":20,"predelayMs":5,"mix":8},
    {"id":"mix","wet":70,"outGain":0}
  ]
  }
  ```
  
- **Bundle export**: zip with multiple presets and optional app‑binding map `{ "com.discord": "Natural Mask" }`.

### 16.6 Predefined Preset Catalog (ships with app)

- **Natural Mask (NAT, LL)** — +2 to +3 semitones, −100 to −200 formant cents, light comp, subtle EQ tilt.
- **Darth Vader (FX, LL)** — pitch −6 to −8 semitones, formant −200 to −300 cents, low‑pass ~3.5 kHz, light short room reverb.
- **Helium (FX, LL)** — pitch +5 to +7, formant +150 to +250, high‑pass 160 Hz, brightness +2 dB @ 3 kHz.
- **Radio Comms (NAT, LL)** — band‑pass 300–3.4 kHz, mild comp (4:1), noise gate tighter.
- **Studio Warm (HQ, NAT)** — gentle low‑shelf @ 120 Hz +2 dB, high‑shelf −1.5 dB @ 8 kHz, comp 2:1, subtle plate reverb.
- **Robotizer (FX, HQ)** — fast Auto‑Tune (retune 10–20 ms, chromatic), formant fixed, bit of metallic comb (optional), wet 80%.
- **Cher‑Tune (FX, HQ)** — Auto‑Tune in musical key (selectable), **retune 1–5 ms**, Humanize 0–10%, formant preserve ON.
- **Anonymous (NAT, LL)** — pitch −2, formant −150, de‑ess (EQ @ 6–8 kHz −3 dB), dry/wet 60%.

### 16.7 Quick Settings Tile & Widget

- **Tap**: toggle Enable/Bypass.
- **Long‑press**: opens mini panel (profile picker, dry/wet slider, status).
- **Cycle Mode**: optional “cycle through favorite presets” (starred) with each tap‑and‑hold.

### 16.8 Diagnostics UX additions

- **Auto‑Tune Tuner View**: real‑time note detector (A4=440 configurable), shows detected vs target note, correction amount.
- **Formant/Pitch Visualizer**: small scope indicating shifts applied per block.
- **Preset Validator**: warns if parameters exceed safe ranges for low‑latency.

---

## 17) Effect Parameter Reference (safe ranges & warnings)

- **Pitch**: default safe −6…+6 st (warn beyond 8); **LL** prefers ≤ ±4.
- **Formant**: default safe −300…+300 cents (warn beyond ±450).
- **Auto‑Tune**: retune < 10 ms may sound robotic; highlight as “FX mode”.
- **EQ**: gain per band |≤ 6 dB| recommended for NAT presets.
- **Reverb**: mix > 20% can reduce intelligibility in calls — show tooltip.
- **Compressor**: ratio > 4:1 and fast release may pump — show hint.

---

## 18) DSP Implementation Notes for New Effects

- **Auto‑Tune**: monophonic pitch detector (YIN/ACF/cepstrum hybrid) + quantizer to nearest scale note → pitch‑shifter to target; blend with `Snap Strength` and modulate with `Humanize`. Formant‑preserve path preferred in HQ.
- **Darth Vader**: chain macro: `Pitch(−7 st, LL)` → `Formant(−250 c)` → `EQ low‑pass @ 3.5 kHz, Q 0.7, −∞ above` → `Short Room Reverb (mix 8%)` → `Comp (3:1, soft knee)`.
- **Cher‑Tune**: Auto‑Tune with **Chromatic OFF** (use chosen key/scale), Retune 1–3 ms, Humanize 0–5%, Formant Preserve ON, Wet 80–100%.

---

## 19) Data Model Updates

- Preset schema version bump to `version: 1` with `engine.latencyMode`, `modules[*].id`, and specific param keys per module.
- App‑binding map stored separately to keep presets portable.

---

## 20) QA Checklist (UI + DSP)

- Create/rename/delete/duplicate presets.
- Import/export single & multi preset bundles.
- Switch profiles while hooked (no audio pop > 3 dB, < 30 ms gap).
- Verify Auto‑Tune tuner shows correct detected pitch (±10 cents).
- Verify Darth Vader preset applies expected spectral tilt and pitch/formant.
- QS tile toggles/binds correctly with background state persistence.
