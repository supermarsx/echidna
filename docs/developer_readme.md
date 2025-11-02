# Echidna Developer Guide

## Native Control API

The C ABI exported by `libechidna.so` is declared in `native/include/echidna_api.h`. The public
entry points are:

- `uint32_t echidna_api_get_version(void)` exposes a packed `MAJOR.MINOR.PATCH` version to guard
  against ABI drift.
- `echidna_result_t echidna_set_profile(const char *profile)` updates the active routing profile.
- `echidna_result_t echidna_process_block(const float *input, float *output, uint32_t frames,
  uint32_t sample_rate, uint32_t channel_count)` feeds captured audio into the DSP pipeline.
- `echidna_status_t echidna_get_status(void)` reports the internal hook state.

`echidna_result_t` enumerates standard error codes:

| Code | Meaning |
| ---- | ------- |
| `ECHIDNA_RESULT_OK` | Request completed successfully. |
| `ECHIDNA_RESULT_ERROR` | Unexpected runtime failure. |
| `ECHIDNA_RESULT_INVALID_ARGUMENT` | One or more arguments were rejected. |
| `ECHIDNA_RESULT_NOT_INITIALISED` | The DSP stack has not been initialised. |
| `ECHIDNA_RESULT_PERMISSION_DENIED` | Caller lacks the necessary privileges. |
| `ECHIDNA_RESULT_NOT_SUPPORTED` | Feature is disabled on the current build. |
| `ECHIDNA_RESULT_SIGNATURE_INVALID` | Plugin or payload failed signature validation. |
| `ECHIDNA_RESULT_NOT_AVAILABLE` | The control surface could not be reached. |

`echidna_status_t` mirrors the shared state flags used by the hook managers and the companion app.

## Control Service Binder Surface

The control service exposes `IEchidnaControlService` over Binder. Companion applications should
bind with `com.echidna.app.permission.BIND_CONTROL_SERVICE` and invoke the following methods:

- `void setProfile(String profile)` → wraps `echidna_set_profile`. Null or empty profiles are
  ignored.
- `int getStatus()` → forwards `echidna_get_status`.
- `int processBlock(float[] input, float[] output, int frames, int sampleRate, int channelCount)` →
  forwards `echidna_process_block`. When `output` is null the call is treated as a monitor tap.
- `long getApiVersion()` → returns the packed API version via `echidna_api_get_version`.

The service loads `libechidna.so` lazily via JNI (`echidna_control_jni`). If the shared library is
missing the binder methods return `ECHIDNA_RESULT_NOT_AVAILABLE` and the status is forced to
`ECHIDNA_STATUS_ERROR` to fail closed.

## DSP Plugin Schema

`libech_dsp.so` now discovers signed plugins from the directory referenced by the
`ECHIDNA_PLUGIN_DIR` environment variable (defaulting to `/data/local/tmp/echidna/plugins`). Plugins
must ship two files:

1. `<name>.so` — a shared object exporting `const echidna_plugin_module_t *echidna_get_plugin_module()`.
2. `<name>.so.sig` — a 64 byte Ed25519 signature over the raw `.so` payload, hex encoded using the
   trusted public key baked into the loader.

The module descriptor returned by `echidna_get_plugin_module()` must populate:

- `abi_version` → currently `ECHIDNA_DSP_PLUGIN_ABI_VERSION` (1).
- `descriptors` / `descriptor_count` → a table of effect descriptors.

Each `echidna_plugin_descriptor_t` entry describes a DSP effect:

- `identifier` (required) → unique, stable key.
- `display_name` (optional) → user friendly label.
- `version` → plugin specific semantic version.
- `flags` → bitfield (`ECHIDNA_PLUGIN_FLAG_DEFAULT_ENABLED` enables the effect after load).
- `create` → returns an `echidna::dsp::effects::EffectProcessor` instance.
- `destroy` → releases the instance allocated by `create`.

The loader validates signatures with the built-in Ed25519 public key before calling `dlopen`. Valid
plugins are prepared and reset whenever the DSP engine reapplies presets, and are inserted into the
processing chain immediately before the mix bus so they receive the fully conditioned wet signal.
