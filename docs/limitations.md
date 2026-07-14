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
    as such. The project now has rooted-emulator proof for native `processBlock` and one
    `AudioRecord.read` interception slice, but Magisk flashing, live LSPosed injection,
    broader hook managers, and physical-device SELinux/HAL behavior remain open (see
    [Verification](verification.md)).

!!! danger "User responsibility"
    Echidna is provided as-is for rooted-device power users. If you flash it on the
    wrong device, combine incompatible install paths, lose recovery access, or brick
    the phone while experimenting, recovery is your responsibility. The project does
    not warranty safe operation on any specific device.

---

## 1. Root is required — there is no rootless mode

Echidna hooks the capture path **inside** the target app's process. Android gives normal
apps no way to do this. That means:

- **Magisk + Zygisk are mandatory** to load the native `libechidna.so` module into audio
  processes. Optionally, LSPosed drives the Java-side shim / control plane.
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

- On many devices, the Magisk module's bootstrap (`post-fs-data.sh` / `service.sh`) needs
  to apply **`magiskpolicy` relaxations** so the hooked processes may connect to the
  snapshot publisher, write telemetry, and load the DSP libraries. What is required
  differs per OEM.
- Some strict-policy devices may only allow the **weaker Java-only (LSPosed) fallback**,
  not the full native hook path. The Compatibility Wizard reports SELinux mode and flags
  this ("Java-only fallback") so you know which path is active.
- **Device-gated:** the exact policy needed on any given phone can only be determined on
  that phone. This has not been validated on hardware here.

## 4. Audio HAL variance across SoCs and vendors

There is no single "the microphone" on Android. Which native API an app uses (AAudio vs.
OpenSL&nbsp;ES vs. `AudioRecord` vs. going straight through AudioFlinger) and how that
routes down to the vendor **HAL** differs by app *and* by chipset — Qualcomm, MediaTek,
Samsung/Exynos, and Google's own silicon all differ.

- Echidna hooks **multiple** capture APIs precisely because no single one is universal,
  and it probes at attach time to pick a hook point (see [Architecture](architecture.md)).
- Even so, some apps or devices route audio through a path Echidna does not yet hook, or
  through a vendor-specific HAL variation, in which case that app's capture is **not**
  transformed (and, being fail-closed, is left untouched rather than corrupted).
- Hook offsets and symbol/pattern matches can require **per-device tuning**. A layout that
  works on one HAL build is not guaranteed on another.
- The Compatibility Wizard reports CPU family, primary ABI, Zygisk ABI, vendor family, and
  whether common native audio libraries (`libOpenSLES.so`, `libaudioclient.so`,
  `libtinyalsa.so`) are present. These checks help identify obvious install and hardware
  mismatches, but they do **not** prove live OpenSL ES, AudioFlinger, tinyalsa, or vendor-HAL
  hook success on their own.
- **Device-gated:** which combinations work is inherently a per-device matrix and is not
  characterized on hardware in this project.

## 5. Profile-sync is multi-reader, but duplicate hook scopes still matter

The old profile-sync channel was a filesystem AF_UNIX socket where each hooked process
tried to bind `/data/local/tmp/echidna_profiles.sock`; only the last binder received
profile pushes. That single-holder limitation is now removed.

Current builds use a service-owned abstract AF_UNIX socket named `echidna_profiles`.
`ProfileSyncBridge` caches the latest `{profiles, whitelist, appBindings, control}`
snapshot and serves it immediately to every connecting Zygisk or LSPosed reader. Readers
also stay connected for later update frames, so a process that starts after the last
mutation no longer waits for another profile change before receiving policy.

Remaining caveats:

- **Device-gated socket reachability:** SELinux/OEM policy can still block injected code
  from connecting to the service-owned endpoint. Failure remains fail-closed.
- **Do not scope the same target app into both hook stacks unless testing.** Socket
  contention is gone, but duplicate native + Java hooks can double-process Java
  `AudioRecord` captures or make telemetry ambiguous.

## 6. `armeabi-v7a` (32-bit ARM) hooking is disabled

Echidna's inline hooking is **ABI-specific**:

| ABI | Hooking status |
| --- | --- |
| **arm64-v8a (aarch64)** | Primary, fully implemented. |
| **x86_64** | Implemented (absolute-jump patch + relocating trampoline with a fail-closed length decoder); rooted-emulator `AudioRecord` slice verified, broader hook coverage still pending. |
| **armeabi-v7a (32-bit ARM / Thumb-2)** | **Graceful degrade — hooking disabled.** The module builds and loads, but `install()` returns `false` and emits a `hook_unsupported_abi` log signal; **no audio hooks activate.** |

Why armv7 degrades instead of shipping a trampoline: correct ARM/Thumb-2 relocation must
handle variable-length (2/4-byte) Thumb encoding, IT-block hazards (patching mid-IT-block
corrupts execution), ARM/Thumb interworking on the low pointer bit, and PC-relative
prologue relocation — and the failure mode is a **crash inside a system audio process**.
Since arm64 is the locked primary target and an armv7 trampoline could not be validated
on-device, armv7 hooking ships **safely inactive** rather than as fragile, untested
machine-code generation. On a 32-bit-ARM device, expect no voice transformation until a
validated trampoline is added.

The 64-bit path (arm64-v8a) covers essentially all current mainstream devices, so in
practice this affects only older/32-bit hardware.

The app now reports CPU/ABI support directly in Compatibility and Diagnostics. A supported
module ABI does not always mean active hooks are enabled: `arm64-v8a` and `x86_64` report native
hook support, while `armeabi-v7a` reports that the module can load but audio hooks are disabled
fail-closed.

## 7. What runs today vs. what needs hardware

To keep expectations honest:

| Capability | Status |
| --- | --- |
| Build (signed APK, 6 per-ABI `.so`, flashable Magisk zip) | **Verified** on a full toolchain |
| Companion app: install, launch, screen navigation, in-app service bind | **Verified** on an unrooted emulator (crash-free) |
| Native `processBlock` and `AudioRecord.read` interception slice | **Verified** on rooted Android 13/14 emulators |
| Magisk flash + reboot, live LSPosed injection, broader hook coverage | **Device-gated** — not validated here |
| SELinux policy interaction, per-vendor audio-HAL routing | **Device-gated** — per-device |
| Multi-app simultaneous hooking | **Supported by profile sync; device/HAL validation still required** |
| armv7 voice transformation | **Not available** (item 6) |

See [Verification](verification.md) for the full proven-vs-device-gated matrix and a
reproduce-on-hardware procedure.

---

## Related reading

- [Why It's Hard](why-hard.md) — the engineering reasons these constraints exist.
- [Comparison](comparison.md) — how these constraints compare to rootless/desktop alternatives.
- [Architecture](architecture.md) — the components and hook order these limits apply to.
- [Verification](verification.md) — what is proven vs. needs a rooted device.
