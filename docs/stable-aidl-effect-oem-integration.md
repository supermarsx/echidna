# Stable AIDL AudioEffect OEM integration

Echidna now contains product-source adapters for the Android 14 and Android 15 Stable AIDL
AudioEffect contracts. This is an OEM build integration, not a runtime Magisk overlay. The existing
Magisk registration path must continue to fail closed when the device exposes only a Stable AIDL
factory until the active OEM product includes and verifies this library.

The implementation follows the native-first capture architecture in `spec.md`. It reuses the same
`EffectContext`, capability verifier, preset loader, DSP engine, and ECHT v2 HMAC signer as the
legacy HIDL library. It does not add another `IFactory` service. The OEM's existing factory loads
`libechidna_preproc_aidl.so` from `soundfx` and calls the standard `createEffect`, `queryEffect`, and
`destroyEffect` exports.

The relevant upstream contracts are the Android
[Stable AIDL Audio HAL documentation](https://source.android.com/docs/core/audio/aidl-implement),
the AOSP
[reference effect library build](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/default/extension/Android.bp),
and the AOSP
[effect factory loader](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/default/EffectFactory.cpp).

## Platform matrix

| Product | AIDL contract | Product module | Parameter bridge | FMQ lifecycle |
| --- | --- | --- | --- | --- |
| Android 14 / API 34 | `android.hardware.audio.effect-V1` | `libechidna_preproc_aidl_v1` | SET value bytes; GET key/value bytes | Allocate at `open`; release at `close` |
| Android 15 / API 35 | `android.hardware.audio.effect-V2` | `libechidna_preproc_aidl_v2` | Complete `effect_param_t` | V2 `reopen` inherited from AOSP `EffectImpl` |

The descriptor is identical across both adapters:

- Type UUID: `c83e3db3-d4f5-5f2c-a095-8775c1edfc6d`
- Implementation UUID: `3e66a36e-dee9-5d81-a0d6-49fc3b863530`
- Flags: `PRE_PROC`, `FIRST`, no hardware acceleration, no bypass flag
- PCM: float32, mono or stereo, 8 kHz through 192 kHz

Android 14's framework conversion drops the vendor parameter key on SET. The V1 adapter therefore
treats an empty value as revoke and every non-empty value as a candidate capability envelope. The
envelope still has to pass the existing signature, implementation UUID, session, expiry, nonce,
generation, process, and preset checks. Android 15 transports the complete packet and uses the
existing legacy command parser unchanged.

`START` without a capability succeeds in identity mode, as required for generic effect clients and
VTS. The AIDL worker may consume and reproduce FMQ samples, but the shared legacy DSP remains
disabled. A later valid capability activates DSP without requiring a second `START`; revoke or
expiry returns immediately to identity. This is a lifecycle state, not authorization bypass.

On Android 15, input and output FMQ depths may change independently. A depth-only `reopen`
preserves the active DSP configuration. A sample-rate, channel-layout, or format change disables
DSP and revokes authorization before reconfiguration; a fresh capability is then required to leave
identity mode.

The contract audit used these exact release snapshots as its baseline. OEM branches may carry
additional patches, so the checker verifies required source behavior rather than requiring an
identical commit:

| Branch | `hardware/interfaces` | `frameworks/av` | `system/media` |
| --- | --- | --- | --- |
| `android14-release` | `40e9f1537e308ed49e3b561ce333e3f2bb64f31e` | `2c377d34a8a6264f088818828fa3255bcbb5bff2` | `d4e04cf1c330e4fd0bb4279782b5c44bfab6cc5a` |
| `android15-release` | `488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6` | `1863cb0ea5a9e1340f11a435618ff863e017da29` | `0287f16fc74c607c04a9570ee3ae914e1285cd2c` |

## OEM source integration

Place this repository at a stable path such as `vendor/echidna` in the product source tree. Select
exactly one module:

```make
# Android 14
PRODUCT_PACKAGES += libechidna_preproc_aidl_v1

# Android 15
PRODUCT_PACKAGES += libechidna_preproc_aidl_v2
```

Do not select both. Both modules intentionally install the same library filename, and their private
AOSP `EffectImpl` contracts are not source-compatible.

Merge the `library` and `effect` children from the matching file into the active OEM
`audio_effects_config.xml`:

- `native/effects/aidl/integration/audio_effects_config.api34.xml`
- `native/effects/aidl/integration/audio_effects_config.api35.xml`

Do not add Echidna to a global `<preprocess>` stream. The companion app attaches it to an explicitly
authorized recording session. Global attachment would create an unusable effect for sessions that
do not possess a capability.

Android 14 cannot declare a custom effect type in XML. Apply the pinned type-map patch to the
Android 14 `hardware/interfaces` checkout:

```sh
git -C hardware/interfaces apply \
  ../../vendor/echidna/native/effects/aidl/integration/android14-effect-type-map.patch
```

Android 15 supports the `type` XML attribute and needs no factory source patch.

The factory process must be able to read these existing trust inputs under enforcing SELinux:

| Path | Owner and mode | Purpose |
| --- | --- | --- |
| `/system/etc/echidna/preprocessor_controller_p256.spki` | `root:root`, `0444` | Capability signature verification |
| `/system/etc/echidna/preprocessor_telemetry_hmac.key` | `root:audio`, `0440` | ECHT v2 telemetry proof |

The reference service runs as `audioserver` with the `audio` group, but an OEM may use another
domain or identity. Prove actual access with the product policy; do not add a broad SELinux rule by
assumption.

## Build and source-contract checks

Build inside the matching AOSP branch:

```sh
source build/envsetup.sh
lunch <product>-userdebug
m libechidna_preproc_aidl_v1  # Android 14
m libechidna_preproc_aidl_v2  # Android 15
```

The repository checker validates the Echidna source plus the exact framework transport and
`EffectImpl` contract in the selected AOSP checkout:

```sh
python3 vendor/echidna/tools/verify_stable_aidl_effect.py \
  --api 34 \
  --repo-root vendor/echidna \
  --hardware-interfaces-root hardware/interfaces \
  --frameworks-av-root frameworks/av
```

Use `--api 35` on Android 15. Supplying only one AOSP source root fails the gate.

## Required device proof

Run the generic factory and effect VTS modules against the product's declared factory:

```sh
atest VtsHalAudioEffectFactoryTargetTest VtsHalAudioEffectTargetTest
```

Then exercise the Echidna UUID on a real capture session and record all of these results:

1. Exactly one `android.hardware.audio.effect.IFactory/default` instance is registered.
2. The OEM factory loads `libechidna_preproc_aidl.so` from its `soundfx` search path.
3. `create`, `open`, FMQ processing, `STOP`, `RESET`, `close`, and destroy succeed.
4. API 35 changes the common buffer size, signals the data-MQ update, and successfully reopens.
5. A session without a valid capability is identity to a maximum absolute difference of `1e-7`.
6. A valid capability and non-neutral preset change at least one output sample.
7. ECHT v2 reports processed frames and mutations, and its HMAC verifies for the active nonce.
8. The factory reads both trust files while SELinux is enforcing, without an AVC denial.

Copy `native/effects/aidl/integration/device-evidence.template.json`, populate it from the product
test run, and invoke the fail-closed product gate. The untouched template intentionally fails.

```sh
python3 vendor/echidna/tools/verify_stable_aidl_effect.py \
  --api 35 \
  --repo-root vendor/echidna \
  --product-config out/target/product/<product>/vendor/etc/audio_effects_config.xml \
  --product-packages device/<vendor>/<product>/device.mk \
  --device-evidence out/echidna/stable-aidl-device-evidence.json \
  --require-product-gate
```

For API 34, also pass the patched source with:

```text
--factory-source hardware/interfaces/audio/aidl/default/EffectConfig.cpp
```

Passing source checks alone proves compatibility with the inspected AOSP contracts. It does not
prove that an OEM selected the module, loaded it, passed VTS, processed an FMQ, reopened it, or
mutated device audio. Only the product gate plus its underlying logs and audio evidence can support
that claim.
