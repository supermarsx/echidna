# Echidna Documentation

Echidna is a native-first, root-level real-time voice changer for Android. It
combines a companion app, an in-process control service, a Zygisk/LSPosed hook
layer, and a C++ DSP engine to transform microphone audio inside selected target
apps.

!!! warning "Current verification status"
    The repository builds the companion APK, per-ABI native libraries, and a
    flashable Magisk/Zygisk module. Emulator validation covers install, launch,
    navigation, app/service binding, fallback UI state, rooted-emulator native
    `processBlock`, and one rooted-emulator `AudioRecord.read` interception
    probe. Magisk flashing, live LSPosed injection, physical-device SELinux
    policy interaction, and broader HAL behavior still require release hardware.
    Start with
    [Verification](verification.md) before treating any device path as proven.

The source requirements live in [spec.md][spec]. This index links the pages that
explain the implemented architecture, user-facing app surface, and remaining
release-device work.

## Start Here

- [Verification](verification.md) - the proven host/emulator checks, the
  remaining device-gated items, and the rooted-device procedure for live hook
  validation.
- [Build & Install](build-install.md) - local, Docker, Android, native, and
  Magisk packaging commands for building the current tree.
- [Architecture](architecture.md) - component boundaries, hook order, profile
  sync, and the native-first flow required by
  [spec sections 3-4][spec-3-4].
- [Limitations](limitations.md) - root, SELinux, HAL, ABI, and duplicate hook-scope
  constraints to read before flashing.

## App And User Docs

- [Screenshots](screenshots.md) - emulator captures for the companion app plus
  a labeled generated Settings documentation screenshot. This supports the UI
  scope in [spec section 16][spec-16] and the QA split in
  [spec section 20][spec-20].
- [DSP & Effects](dsp-effects.md) - effect-chain order, preset catalog, safe
  ranges, and low-latency implications from [spec section 5][spec-5].
- [Comparison](comparison.md) - how Echidna differs from app-level,
  desktop-routed, and generic Magisk audio tools.
- [Why It's Hard](why-hard.md) - the engineering risks behind native audio
  hooks, OEM variance, SELinux, and real-time DSP.

## Developer Reference

- [Developer Guide](developer_readme.md) - source topology, in-process control
  service, build facts, native API, plugin schema, and known implementation
  limits. Treat this as the source of truth for build and control-plane facts.
- [Signing](signing.md) - release APK signing, keystore sources, debug fallback,
  and native/Magisk signing boundaries. Treat this as the source of truth for
  signing facts.
- [Magisk Release](magisk_release.md) - flashable module layout, package
  contents, installation path, and release-gate expectations from
  [spec section 7][spec-7].
- [Design Rationale](design-rationale.md) - why the project uses native hooks,
  a fail-closed whitelist, and a rooted/sideload distribution model.

## Documentation Map

| Need | Read |
| --- | --- |
| "Can I trust this build?" | [Verification](verification.md) |
| "How do I build the APK and module?" | [Build & Install](build-install.md) |
| "How does audio flow through the system?" | [Architecture](architecture.md) |
| "What does the app look like today?" | [Screenshots](screenshots.md) |
| "Which effects and presets exist?" | [DSP & Effects](dsp-effects.md) |
| "What will break on real devices?" | [Limitations](limitations.md) |
| Build/topology/signing facts | [Developer Guide](developer_readme.md), [Signing](signing.md) |

## Responsible Use

Echidna is intended for consented, authorized voice transformation on devices you
control. Root-level hooks can affect privacy, app policy compliance, and system
stability. Keep the per-app whitelist fail-closed, avoid simultaneous Zygisk and
LSPosed scope for the same target app unless you are testing both paths, and
validate live behavior on hardware before using it in calls or production apps.

[spec]: https://github.com/supermarsx/echidna/blob/main/spec.md
[spec-3-4]: https://github.com/supermarsx/echidna/blob/main/spec.md#3-highlevel-native-architecture-detailed
[spec-5]: https://github.com/supermarsx/echidna/blob/main/spec.md#5-dsp-pipeline--lowlevel-performance
[spec-7]: https://github.com/supermarsx/echidna/blob/main/spec.md#7-installation--deployment-steps-developer--user-flows
[spec-16]: https://github.com/supermarsx/echidna/blob/main/spec.md#16-ui-controls--preset-management--detailed-spec
[spec-20]: https://github.com/supermarsx/echidna/blob/main/spec.md#20-qa-checklist-ui--dsp
