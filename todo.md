# Echidna TODO

- Package Magisk/Zygisk module layout (module.prop, service scripts) per spec section 15.
- Add AudioFlinger/libc read fallbacks and vendor HAL probes for edge devices (spec 4.2/4.3).
- Implement native socket/binder control channel and shared profile sync to Zygisk (spec 3/10).
- Wire DSP plugin signature enforcement with baked public key on-device (spec DSP plugin schema).
- Add latency/CPU watchdog and panic bypass flows in companion app and native module (spec 12).
