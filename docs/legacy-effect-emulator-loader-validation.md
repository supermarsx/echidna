# Legacy Effect Emulator Loader Validation

This record covers the Android 13 legacy-HIDL effect loader boundary required by Section 20 of
`spec.md`. It must be read with the [developer guide](developer_readme.md) and the
[signing and trust contract](signing.md).

## Scope

Validation ran on 2026-07-15 using only the disposable `echidna_e2e33` AVD:

- serial: `emulator-5554`
- fingerprint:
  `google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys`
- API/ABI/build: API 33, `x86_64`, `userdebug`
- effect factory: `android.hardware.audio.effect@7.0::IEffectsFactory/default`
- SELinux: `Enforcing` before load, after load, and after rollback

The AVD was freshly wiped and booted without disabling verity or remounting any partition. The
loader test used transient bind mounts from `/data/local/tmp`; stopping the AVD removed all staged
state. No other AVD or physical device was used.

## Inputs

The x86_64 preprocessor was built from the current native tree. `llvm-readelf` and `llvm-nm`
confirmed ELF64 x86-64, the `libechidna_preproc.so` SONAME, only platform DT_NEEDED libraries, and
the exported `AELI` descriptor.

- Stock `/vendor/etc/audio_effects.xml`:
  `26b54895f82ece727f871568e6099a6e890e63125e3549b1eb61e91e6f4c2c9b`
- Merged loader-only registry:
  `ebf2b44dff95fe2a43ed687a86b56530b21844f18545ee6accd90855e7ec2868`
- x86_64 `libechidna_preproc.so`:
  `0d571d0cc4f44e464be210a2e29f79fc7c9bb24f408cbc0090a8899766023b80`

A package-local host runner called the repository's `EffectConfigMerger.merge(source, XML, null)`
twice. The second call was unchanged, proving idempotence. The generated XML added one library and
one effect definition and no `preprocess`, `postprocess`, or other automatic application:

- implementation UUID: `3e66a36e-dee9-5d81-a0d6-49fc3b863530`
- type UUID: `c83e3db3-d4f5-5f2c-a095-8775c1edfc6d`

## Device Procedure

The relevant command sequence was:

```sh
adb -s emulator-5554 root
adb -s emulator-5554 shell \
  'cp -a /vendor/lib64/soundfx/. /data/local/tmp/echidna-bind-validation/soundfx/'
adb -s emulator-5554 push libechidna_preproc.so \
  /data/local/tmp/echidna-bind-validation/soundfx/libechidna_preproc.so
adb -s emulator-5554 push audio_effects.xml \
  /data/local/tmp/echidna-bind-validation/audio_effects.xml
```

The complete mirror retained matching hashes for all nine stock libraries and added exactly one
file. It was labelled `vendor_file`; the cloned registry was labelled `vendor_configs_file`.
Ownership and modes matched the stock targets: `root:shell 0755` for the directory and
`root:root 0644` for files.

```sh
adb -s emulator-5554 shell mount -o bind \
  /data/local/tmp/echidna-bind-validation/soundfx /vendor/lib64/soundfx
adb -s emulator-5554 shell mount -o bind \
  /data/local/tmp/echidna-bind-validation/audio_effects.xml \
  /vendor/etc/audio_effects.xml
adb -s emulator-5554 shell setprop ctl.restart vendor.audio-hal
```

Both bind entries appeared in the shell and live HAL process mount tables. Init source identified
the exact service as `vendor.audio-hal`; its declared `onrestart restart audioserver` action was the
only secondary restart. HAL PID changed `317 -> 7973`; audioserver changed `373 -> 7971`.

Before the restart, both kernel and Android logs were cleared. Post-restart evidence was captured
with `lshal -i`, `dumpsys media.audio_flinger`, `/proc/7973/maps`, `dmesg`, and all logcat buffers.

## Loader Result

The loader boundary passed:

- HIDL 7.0 `IEffectsFactory/default` re-registered successfully.
- `/proc/7973/maps` contained executable mappings for
  `/vendor/lib64/soundfx/libechidna_preproc.so`.
- AudioFlinger reported `Library echidna_preproc`, the exact implementation and type UUIDs,
  API version `00020000`, and flags `0000000B`.
- `Libraries NOT loaded` did not contain Echidna.
- No relevant Echidna, soundfx, audio-config, or `hal_audio_default` AVC appeared while SELinux was
  enforcing.

The emulator also logged an absent optional Bluetooth implementation and an absent effect 7.1 VINTF
entry. Those stock-emulator messages were unrelated to the registered HIDL 7.0 Echidna library.

## Rollback Proof

The config bind unmounted normally. The soundfx bind was busy while the HAL mapped the library, and
the platform immediately demand-restarted a requested HAL stop (`7973 -> 8943`). A lazy detach was
therefore used for the directory, followed by an exact `vendor.audio-hal` restart:

```sh
adb -s emulator-5554 shell umount /vendor/etc/audio_effects.xml
adb -s emulator-5554 shell umount -l /vendor/lib64/soundfx
adb -s emulator-5554 shell setprop ctl.restart vendor.audio-hal
```

The rollback restart changed HAL PID `8943 -> 9103` and audioserver PID `8942 -> 9102`. It restored
the stock config hash, all nine stock libraries, and the original labels. Echidna had zero matches
in `/proc/9103/maps` and the fresh AudioFlinger descriptor dump. SELinux remained enforcing, with
zero relevant rollback AVC or Echidna log lines. The AVD was then stopped cleanly.

Local command output is preserved under `build/device-validation/bind-fallback/` in the validation
workspace. That generated directory is intentionally not a release artifact.

## Limits

This proves only that this API 33 x86_64 emulator's registered legacy-HIDL factory can load and
describe the exact Echidna AELI library under enforcing SELinux. It does not prove Magisk install or
boot ordering, an OEM device, controller SPKI access, capability verification, effect creation or
enablement, `AudioRecord` attachment, microphone input, DSP mutation, latency, or transformed audio.
No SPKI, capability, session, or automatic preprocessor application was staged during this run.
