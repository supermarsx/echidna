# Limitations

Echidna intercepts the microphone/capture path *inside other apps' processes* on
Android. That is powerful, and it is also intrinsically constrained by things Echidna
does not control: how each vendor builds their OS, how each SoC routes audio, and the
security model of modern Android. This page is a candid inventory of those constraints.
None of them is a bug to be "fixed" in a release; they are properties of the platform
Echidna operates in. Read them before flashing anything.

!!! warning "Status context"
    Everything below reflects the design and the code as it stands. Where a limitation is
    *device-gated* — only observable on rooted hardware or release hardware — it is marked
    as such. The project has rooted-emulator proof for native `processBlock`. An
    `AudioRecord.read` interception slice passed before the current explicit-contract redesign
    and is historical evidence only. Magisk flashing, live LSPosed injection, supported capture
    routes, and physical-device SELinux behavior remain open (see
    [Verification](verification.md)).

!!! danger "⚠️ User responsibility"
    Echidna is experimental and provided as-is for rooted-device power users. It
    may be incompatible with the phone you are using. If you flash it on the
    wrong device, combine incompatible install paths, lose recovery access, or
    brick the phone while experimenting, recovery is your responsibility. The
    project does not warranty safe operation on any specific device.

!!! warning "⚠️ Recovery knowledge is a prerequisite"
    If you do not already know how to disable a Magisk/Zygisk module from
    recovery, adb, safe mode, or another out-of-band path after a bootloop, do
    not install Echidna. The existence of failsafe markers does not guarantee
    that your recovery environment can mount or edit the needed partitions.

---

## Install-risk failsafes are guardrails, not guarantees

Echidna's intended failsafes are designed to help a prepared user boot again if the module causes
trouble. They are not a promise that every device can be recovered through the same path.

The documented disable markers are:

- Magisk's module disable file for `echidna`, normally `/data/adb/modules/echidna/disable`.
- Echidna's own runtime disable marker at `/data/adb/echidna/disable`.
- The project's safe-mode path, when available.
- Early recovery markers at `/cache/echidna-disable` and `/metadata/echidna-disable`.
- An automatic boot watchdog intended to disable Echidna after repeated boots that do not reach
  the late-start service.

These paths can fail to help if recovery cannot decrypt `/data`, the bootloader is locked, adb is
unavailable, `/cache` or `/metadata` is absent or inaccessible, another module breaks earlier in
boot, or the device has vendor-specific recovery behavior. Treat every install as experimental and
device-specific.

## 1. Root is required — there is no rootless mode

Echidna hooks the capture path **inside** the target app's process. Android gives normal
apps no way to do this. That means:

- **A working Zygisk implementation is mandatory** to load the native `libechidna.so`
  module into audio processes. The documented install path remains Magisk + built-in
  Zygisk; standalone Zygisk providers can be detected by the compatibility probe, but
  APatch/KernelSU/Zygisk Next combinations remain device-gated until validated on the
  target hardware. Optionally, LSPosed drives the Java-side capture shim.
- On an **unrooted** device the companion app still runs — you can browse presets,
  configure the effect chain, and run the Compatibility Wizard — but the **engine stays
  "Not Installed"** and no audio is transformed. This is exactly what the app reports on
  an unrooted emulator today: *Native: Not Installed, SELinux: Enforcing (Java-only
  fallback)*.
- Rooting carries real risk (bootloader unlock wipes data; some devices lose banking/DRM
  attestation). That is a decision only you can make for your device.

There is no plan for a rootless mode, because the capability Echidna needs (intercepting
another app's microphone capture) is precisely what Android's sandbox is designed to
prevent.

## 2. Not distributed through an app store

Echidna is delivered as a **sideloaded companion APK** plus a **flashable Magisk/Zygisk
zip**. It will not appear on Google Play or, in its rooting form, on F-Droid. You install
the APK manually and flash the module in Magisk. There is no auto-update channel; you
update by re-flashing/re-installing. This is a direct consequence of item&nbsp;1.

## 3. OEM and SELinux variance

Modern Android runs SELinux in **enforcing** mode, and **every vendor ships different
policy**. Echidna's native module, abstract profile-sync socket, shared telemetry/config
files, and DSP library placement touch operations that a vendor's policy may or may not
allow to the contexts Zygisk-injected code runs in.

- The Magisk module defines dedicated config and telemetry file types. It grants app domains
  read-only access to config, read/write access to telemetry, and only the parent-directory
  traversal needed to reach those files. It does not grant zygote transitions or binder access.
  A vendor may still deny a required operation outside this narrow policy.
- Some strict-policy devices may only allow the **weaker Java-only (LSPosed) fallback**,
  not the full native hook path. Diagnostics may recommend that path; a recommendation is not
  evidence that LSPosed injection or transformed-buffer processing is active.
- A disposable enforcing API 33 x86_64 emulator observed on 2026-07-15 had an old installed Echidna
  module v0.0.0, not current HEAD. Maps (`untrusted_app`) logged `avc: denied { write }` to
  `echidna_telemetry_file` and then `Failed to create profile listener`. This is historical live
  evidence that rebuilt current artifacts need an enforcing-SELinux rerun, not proof that the
  current authenticated transports fail the same way.
- **Device-gated:** the exact policy needed on any given phone can only be determined on
  that phone. This has not been validated on hardware here.

## 4. Capture-route coverage across SoCs and vendors

There is no single "the microphone" on Android. Which API an app uses and how it routes to the
vendor audio stack differs by app and chipset. Echidna therefore exposes a support matrix instead
of claiming that every manager is operational:

| Route | Current status |
| --- | --- |
| AAudio | Operational candidate; stable stream getters provide PCM metadata. |
| OpenSL ES | Operational candidate; recorder sink provides the PCM descriptor. |
| tinyalsa | Operational candidate; `pcm_open` config provides metadata. |
| LSPosed Java `AudioRecord` | Operational candidate through Java getters and dedicated JNI. |
| Legacy input preprocessor | Packaged and conditionally registered for next boot; default-off authorized LSPosed attachment candidate with no device audio proof. |
| Telemetry HMAC key | Per-install provisioning plus native ECHT v2 production, LSPosed relay, and control verification are implemented and host-tested; effect-host SELinux reads and end-to-end origin proof remain device-gated. |
| Native `AudioRecord` | Developer contract only (`ECHIDNA_AR_SR/CH/FORMAT`). |
| libc raw-device read | Developer contract only (`ECHIDNA_LIBC_SR/CH/FORMAT`). |
| Audio HAL | Unsupported (`unsupported_injection_boundary`). |
| AudioFlinger | Unsupported (`unsupported_injection_boundary`). |

- Echidna attempts every eligible normal-flow candidate because an app may touch multiple APIs
  (see [Architecture](architecture.md)). “Operational candidate” is code reachability, not device
  proof.
- The native rows are ABI-qualified. On `armeabi-v7a`, AAudio, OpenSL ES, tinyalsa, native
  `AudioRecord`, and libc-read are unsupported before installation; see item 6. LSPosed Java/JNI
  and the legacy input preprocessor remain eligible under their separate policy and device gates.
- Even so, some apps or devices route audio through a path Echidna does not yet hook, or
  through a vendor-specific HAL variation, in which case that app's capture is **not**
  transformed (and, being fail-closed, is left untouched rather than corrupted).
- Native `AudioRecord` and libc reads stay disabled during normal specialization because no safe
  producer supplies their explicit PCM contracts. Audio HAL and AudioFlinger live across an
  audioserver/vendor boundary that app-process Zygisk does not own; they fail closed instead of
  attempting private offsets or unstable object layouts.
- The Compatibility Wizard reports CPU family, primary ABI, Zygisk ABI, vendor family, and
  whether common native audio libraries (`libOpenSLES.so`, `libaudioclient.so`,
  `libtinyalsa.so`) are present. These checks help identify obvious install and hardware
  mismatches, but they do **not** prove live AAudio, OpenSL ES, tinyalsa, or LSPosed processing.
- **Device-gated:** which combinations work is inherently a per-device matrix and is not
  characterized on hardware in this project.

## 5. Policy delivery is authenticated and transport-specific

The old profile-sync channel was a filesystem AF_UNIX socket where each hooked process
tried to bind `/data/local/tmp/echidna_profiles.sock`; only the last binder received
profile pushes. That single-holder limitation is now removed.

Current builds publish one strict v2 policy with a monotonic generation, explicit default profile,
whitelist, `captureOwners`, bindings, and complete control state. Zygisk uses authenticated,
UID-scoped frames over the service-owned abstract AF_UNIX socket `echidna_profiles`. LSPosed does
not use that socket: it binds an explicit read-only Binder provider, which authenticates the caller
UID and claimed process and returns only the exact/base scoped view.

Remaining caveats:

- **Device-gated transport reachability:** SELinux/OEM policy can still block the native socket,
  exported Binder bind/call, or telemetry mapping. Failure remains fail-closed.
- **One capture owner per process:** policy assigns `zygisk` or `lsposed`; consumers require their
  own owner. Do not try to make both stacks own the same process.
- **Late/restarted publisher:** native processing is revoked on disconnect and reconnects with
  bounded backoff. LSPosed fails closed and rebinds. Neither uses stale policy as admission proof.

## 6. `armeabi-v7a` (32-bit ARM) hooking is disabled

Echidna's inline hooking is **ABI-specific**:

| ABI | Hooking status |
| --- | --- |
| **arm64-v8a (aarch64)** | Primary, fully implemented. |
| **x86_64** | Implemented (absolute-jump patch + relocating trampoline with a fail-closed length decoder); host harness verified. The older rooted `AudioRecord` slice predates the current route contract. |
| **armeabi-v7a (32-bit ARM / Thumb-2)** | **Graceful degrade — direct inline-symbol routes disabled.** The module builds and loads, but AAudio, OpenSL ES, tinyalsa, native `AudioRecord`, and libc-read report `unsupported_armv7_late_symbol_hooking` before installation. |

Why armv7 degrades instead of shipping a trampoline: correct ARM/Thumb-2 relocation must
handle variable-length (2/4-byte) Thumb encoding, IT-block hazards (patching mid-IT-block
corrupts execution), ARM/Thumb interworking on the low pointer bit, and PC-relative
prologue relocation — and the failure mode is a **crash inside a system audio process**.
Zygisk's PLT API is not a safe late-load replacement. The bundled API v3 contract says API
functions stop working after `postAppSpecialize`; its Magisk v25.2 implementation registers with
`xhook`, refreshes the ELFs currently loaded in memory, and then clears the registration set.
Current Magisk retains the same one-shot property: the compatibility path scans current maps at
commit and clears its registrations. Echidna intentionally waits for authenticated process policy
after specialization, when the API is no longer available and app-native caller libraries can
still load later. It therefore cannot provide complete, target-scoped PLT coverage without adding
an unsafe loader hook.

Primary-source references: Magisk v25.2
[`api.hpp` lines 162–220](https://github.com/topjohnwu/Magisk/blob/6066b5cf86703512451a021cf1aaf1a877530af7/native/jni/zygisk/api.hpp#L162-L220)
and
[`hook.cpp` lines 306–312](https://github.com/topjohnwu/Magisk/blob/6066b5cf86703512451a021cf1aaf1a877530af7/native/jni/zygisk/hook.cpp#L306-L312),
plus the current compatibility implementation
[`module.cpp` lines 164–220](https://github.com/topjohnwu/Magisk/blob/14ea5cfb4a5771c742f7c3fd1e685bdbfac7aa8c/native/src/core/zygisk/module.cpp#L164-L220).

Since arm64 is the locked primary target and neither a complete PLT transaction nor an armv7
trampoline can be validated safely, direct armv7 symbol hooking ships inactive. This does not
disable the LSPosed Java/JNI route or the official legacy input preprocessor, which use different
attachment boundaries and retain their existing policy and device gates.

The 64-bit path (arm64-v8a) covers essentially all current mainstream devices, so in
practice this affects only older/32-bit hardware.

The app now reports CPU/ABI support directly in Compatibility and Diagnostics. A supported
module ABI does not always mean active hooks are enabled: `arm64-v8a` and `x86_64` report native
hook support, while `armeabi-v7a` reports that direct inline-symbol routes are disabled
fail-closed.

## 7. What runs today vs. what needs hardware

To keep expectations honest:

| Capability | Status |
| --- | --- |
| Build (signed APKs, 12 native targets, 9 release-delivery artifacts, Magisk zip) | **Verified** on a full toolchain |
| Companion app: install, launch, screen navigation, in-app service bind | **Verified** on an unrooted emulator (crash-free) |
| Native `processBlock` | **Verified** on rooted Android 13/14 emulators |
| Historical native `AudioRecord.read` slice | Passed before current route redesign; not reachability proof |
| AAudio/OpenSL/tinyalsa/LSPosed capture | **Device-gated** — not validated here |
| Legacy input preprocessor | **Device-gated** — packaging/registration and a default-off authorized LSPosed session manager exist for proven system/vendor HIDL configs; device load/enable/audio proof remains |
| Native AudioRecord/libc | **Developer contract only** — not a normal-flow route |
| Audio HAL / AudioFlinger transform | **Unsupported injection boundary** |
| SELinux interaction for supported routes | **Device-gated** — per-device |
| Multi-app simultaneous policy delivery | **Implemented with scoped socket/Binder transports; live capture still device-gated** |
| armv7 voice transformation | **Not available** (item 6) |

See [Verification](verification.md) for the full proven-vs-device-gated matrix and a
reproduce-on-hardware procedure.

---

## Related reading

- [Why It's Hard](why-hard.md) — the engineering reasons these constraints exist.
- [Comparison](comparison.md) — how these constraints compare to rootless/desktop alternatives.
- [Architecture](architecture.md) — the components and hook order these limits apply to.
- [Verification](verification.md) — what is proven vs. needs a rooted device.
