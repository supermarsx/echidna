# Echidna TODO

- Add AudioFlinger/libc read fallbacks and vendor HAL probes for edge devices (spec 4.2/4.3).
- Harden profile sync parsing and validation; add binder control channel for profile pushes (spec 3/10).
- Wire DSP plugin signature enforcement with baked public key on-device (spec DSP plugin schema).
- Add latency/CPU watchdog and panic bypass flows in companion app and native module (spec 12).
- Package installer README and scripted zip assembly for Magisk release pipeline (polish).
- Add CI smoke tests for native hooks and DSP validation (unit + minimal audio block round-trip).
- Implement per-app profile binding and status reflection in LSPosed shim UI.
- Capture telemetry snapshots (latency histograms, CPU) and stream to companion diagnostics.
- Add Doxygen documentation across public headers (C API, DSP API, hook managers, profile sync).
