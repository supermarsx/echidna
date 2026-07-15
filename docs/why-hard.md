# Why It's Hard

Plenty of tools change how audio *sounds coming out* of a phone, and plenty of PC apps
change your voice before a call app sees it. Doing the equivalent **on Android, to the
microphone capture, inside another app's process, in real time** is a genuinely difficult
engineering problem. This page explains *why* — concretely, and without hand-waving — so
the design choices ([Design Rationale](design-rationale.md)) and constraints
([Limitations](limitations.md)) make sense.

The short version: Android offers **no official API** to intercept another app's
microphone capture, the capture path is **fragmented** across several native APIs and
vendor HALs, the work has to happen under a **hard real-time deadline**, it must run as
injected machine code **inside a security-sensitive media process**, and modern Android's
**SELinux** is actively trying to stop exactly this. Each of those is hard on its own;
Echidna has to solve all of them at once.

---

## 1. There is no official API for microphone interception

This is the root of the difficulty. Android's security model is built so that one app
**cannot** see or modify another app's audio. There is:

- no public "virtual microphone" a user app can register (the trick desktop voice changers
  rely on), and
- no supported hook for another app's capture stream.

So the only way in is to **inject code into the target process and patch the audio APIs it
calls** — which immediately requires root (Magisk/Zygisk) and everything that follows on
this page. There is no supported, sandbox-friendly path; the whole project lives in the
space the platform deliberately leaves closed.

## 2. The capture path is fragmented — many APIs, many HAL layers

There is no single "the microphone" to hook. An Android app can read the mic through any
of several native APIs, and each of those descends through vendor-specific layers:

- **AAudio** — the modern low-latency native API; apps register a data callback.
- **OpenSL&nbsp;ES** — the older native audio API, buffer-queue based.
- **`AudioRecord`** (Java, with a native bridge such as `android_media_AudioRecord_*`).
- **AudioFlinger** — the system audio server boundary, not a stable app-process transform ABI.
- **Vendor HAL** — below all of the above, and different on Qualcomm, MediaTek,
  Samsung/Exynos, and Google silicon.

Different apps use different APIs; the same app may behave differently across devices. Current
normal-flow candidates are AAudio, OpenSL ES, tinyalsa, and the LSPosed Java `AudioRecord` shim.
Native `AudioRecord` and libc reads are developer-contract-only because normal specialization does
not provide their PCM metadata. Audio HAL and AudioFlinger are explicitly unsupported
(`unsupported_injection_boundary`) because app-process Zygisk cannot safely own audioserver/vendor
stream objects. See the [capture-route matrix](limitations.md#4-capture-route-coverage-across-socs-and-vendors).

## 3. A hard real-time latency budget — while doing nontrivial DSP

Voice transformation must feel live. The audio callback hands you a small block of samples
and expects the processed block back **before the next block is due** — on the order of a
few milliseconds. Miss it and you get **XRuns** (audible glitches/dropouts).

Inside that budget Echidna has to run a real DSP chain — noise gate, EQ, compressor/AGC,
**pitch shift**, **formant shift**, **Auto-Tune**, reverb, dry/wet mix (see
[DSP & Effects](dsp-effects.md)). Pitch and formant work involves pitch detection and
phase-vocoder/formant processing, which is not cheap. This forces hard choices:

- **Native C++** (not managed code) to avoid garbage-collection pauses that would blow the
  deadline.
- **In-callback, in-place processing** for the low-latency mode to minimize copies, with a
  separate **hybrid callback→worker** mode for heavier, higher-quality transforms that
  can't fit the tightest budget.
- **Lock-free ring buffers** between the callback thread and any worker, because taking a
  lock on the audio thread risks priority inversion and missed deadlines.
- A **fail-safe passthrough**: if processing ever risks overrunning, the block must pass
  through cleanly rather than glitch — and an auto-bypass watchdog trips a process's hook
  if it repeatedly overruns.

Doing quality pitch/formant DSP *and* hitting a single-digit-millisecond deadline *every
block* is the central real-time tension of the project.

## 4. Hooking *inside* a live media process

Echidna doesn't proxy audio; it patches the target process's own code so its audio reads
route through the DSP. That means writing an **inline hook**: overwrite the first bytes of
a function with a jump to your trampoline, and relocate the overwritten instructions so the
original can still run. This is delicate for reasons that have nothing to do with audio:

- You must **decode the target's prologue instructions** to know how many bytes to copy,
  and **fix up** anything position-dependent (PC/RIP-relative addressing, relative
  branches) so it still works from the trampoline's new address.
- You need **symbol resolution** that survives stripped/vendor libraries: try `dlsym`,
  then scan the PLT/GOT, then pattern-match, using `/proc/<pid>/maps` to find loaded
  ranges.
- You are executing in a **security-sensitive target app process**. A mistake does not just fail
  the feature — it can crash that app. Crossing into audioserver would increase the blast radius,
  which is one reason current HAL/AudioFlinger routes remain unsupported.

Because the blast radius is a crash inside someone else's audio process, Echidna's hook
engine is built to **fail closed**: if it cannot *safely* decode and relocate a prologue,
it **declines the hook and patches nothing** rather than guess. That conservatism is a
direct response to how unforgiving in-process patching is.

## 5. ABI and trampoline complexity — every CPU is a different problem

Inline hooking is **machine-code-specific**: the patch, the instruction decoder, and the
relocation logic must be rewritten per CPU architecture, and the architectures differ
sharply in how hard they are.

- **arm64-v8a (aarch64)** — Echidna's primary, fully implemented path (LDR/BR patch +
  relocating trampoline).
- **x86_64** — implemented, but a **variable-length instruction set** (x86) means you
  must write a real length decoder: parse prefixes, REX, ModRM/SIB/displacement, detect
  RIP-relative operands and rel32 branches, and relocate them — declining anything the
  decoder doesn't fully recognize (VEX/EVEX, unusual encodings). It's built with an
  allow-listed decoder that **fails closed on anything it can't prove safe**, and still
  needs on-device/emulator confirmation.
- **armeabi-v7a (32-bit ARM / Thumb-2)** — **not shipped as an active hook.** Correct
  Thumb-2 relocation must handle variable-length (2/4-byte) Thumb encoding, **IT-block**
  hazards (patching mid-IT-block corrupts execution), ARM/Thumb interworking on the low
  address bit, and PC-relative prologue relocation. Getting any of these wrong crashes a
  system audio process, so armv7 hooking is deliberately **disabled** (it logs
  `hook_unsupported_abi` and installs nothing) rather than shipped as fragile untested
  code. See [Limitations §6](limitations.md#6-armeabi-v7a-32-bit-arm-hooking-is-disabled).

"Just support all ABIs" hides an enormous amount of per-architecture instruction-decoding
work, and the safe answer for the hardest ABI is to **not** hook rather than risk a crash.

## 6. SELinux on modern Android is trying to stop you

Even with root, **SELinux enforcing** stands between injected code and the operations it
needs. The contexts that Zygisk-injected code runs in may be denied the ability to connect the
authenticated policy socket, map telemetry, place libraries, or touch certain paths — and the
**policy differs per OEM**.

Echidna's Magisk module defines dedicated config and telemetry file types with narrow grants:
config is app-readable, telemetry is app-readable/writable, and parent traversal is search-only.
It does not grant zygote transitions or binder access. Strict-policy devices may still block a
required operation or limit the system to the **Java-only (LSPosed) fallback**. What works can only
be determined per device.
This is real, ongoing friction, not a one-time setup step — see
[Limitations §3](limitations.md#3-oem-and-selinux-variance).

## 7. Fail-closed security so a bug can't leak your audio

A tool that sits in the microphone path of arbitrary apps is a serious privacy surface. A
bug must never **silently** transform or leak audio from an app the user didn't authorize.
So the whole system is designed to **fail closed**, which is *harder* than failing open:

- The per-app **whitelist defaults to deny** — no policy, unparseable policy, wrong capture owner,
  or an absent/`false` entry leaves installed hooks inert. The Java transaction preserves original
  bytes and the original read result unless a current permit authorizes the transformed commit.
- The **plugin loader** requires a valid **Ed25519** signature and is **fail-closed by
  default**: without a real trusted key provisioned at build time (a deliberate all-zero
  placeholder ships by default), signature verification fails and no plugin loads.
- The hook engine **declines** rather than guesses (item 4); the DSP **passes through**
  rather than glitches (item 3).

Designing every failure mode to end in "do nothing / transform nothing" — instead of the
easier "do something and hope" — is extra work at every layer, and it's the right call for
something this invasive.

## 8. Packaging it all as a flashable module

Finally, all of this has to ship as a **single flashable Magisk/Zygisk module** that works
across devices:

- Correct **Zygisk per-ABI layout** (`zygisk/arm64-v8a.so`, etc. — Magisk selects the ABI
  per process), plus the DSP library staged into the right `system/lib(64)` path and the
  JNI `libechidna.so` where the app's `dlopen` expects it.
- A single canonical **module id** so the runtime's full-path `dlopen`
  (`/data/adb/modules/echidna/...`) resolves.
- Boot scripts (`post-fs-data.sh` / `service.sh`) that arm/clear the watchdog, prepare runtime and
  plugin directories, create narrowly labelled config/telemetry regions, and **fail loudly** if a
  per-ABI library is missing.

Getting 12 cross-compiled native targets built, transporting each through its correct consumer,
and keeping the packaged preprocessor inert unless a legacy-HIDL registry is safely staged —
while doing it reproducibly (the
project builds this both on the host toolchain and in a Docker `magisk-packager`) — is its
own layer of difficulty on top of everything above. See [Build & Install](build-install.md).

---

## Putting it together

None of these problems is unique to Echidna, but the combination is what makes on-device
microphone voice-changing hard:

1. **No API** forces code injection (root).
2. **A fragmented capture path** forces hooking many APIs across many HALs.
3. **A real-time deadline** forces native, lock-free, fail-safe DSP.
4. **In-process patching** forces a careful, decline-on-doubt hook engine.
5. **Per-ABI machine code** forces separate, safety-gated implementations (and disabling
   the hardest one).
6. **SELinux** forces per-OEM policy work and a Java-only fallback.
7. **A privacy-critical surface** forces fail-closed design everywhere.
8. **Cross-device delivery** forces a precise flashable-module packaging.

That is why so few tools attempt this on Android, and why Echidna's honest status
([Verification](verification.md)) keeps the *built-and-verified* parts clearly separated
from the *device-gated* parts.

---

## Related reading

- [Design Rationale](design-rationale.md) — the choices these difficulties motivated.
- [Limitations](limitations.md) — the constraints that remain.
- [Architecture](architecture.md) — the components and hook order.
- [Comparison](comparison.md) — why easier tools don't solve this problem.
