# Magisk Module Packaging

## Overview

This repo ships a helper script to package the Zygisk native module and DSP library
into a Magisk-compatible zip. This aligns with the deployment flow in
[spec.md](spec.md#7-installation--deployment-steps-developer--user-flows).

## Inputs

- `build/zygisk/lib/libechidna.so` (native hook library)
- `build/dsp/lib/libech_dsp.so` (DSP engine library)
- Optional: `out/echidna_af_offsets.txt` if AudioFlinger offsets were captured

## Script usage

From the repo root:

```sh
tools/build_magisk_module.sh
```

Environment overrides:

- `ECHIDNA_VERSION` (default `0.0.0`)
- `ECHIDNA_VERSION_CODE` (optional; defaults to numeric digits from version)
- `ECHIDNA_AF_OFFSETS` (optional path to AudioFlinger offsets file)

Output:

- `out/echidna-magisk.zip`

## Release checklist

- Verify native and DSP builds match target ABI list.
- Run the relevant native tests and hook smoke checks.
- Package the module with `tools/build_magisk_module.sh`.
- Install via Magisk and reboot to activate.
- Validate hooks using the companion app diagnostics view.
