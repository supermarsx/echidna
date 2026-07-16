# API 33 legacy preprocessor session evidence

Date: 2026-07-16
Device: `emulator-5554`, Android API 33, x86_64, SELinux enforcing

## Result

| Gate | Result | Evidence |
| --- | --- | --- |
| Companion setup identity and compatibility policy | PASS | LSPosed owner and v7 policy published |
| Raw `FLAG_ONEWAY` registration with Binder PID 0 | PASS | Rejected before handoff callbacks |
| Normal synchronous v7 registration and owner drain | PASS | Generation 30; handoff token 1 |
| Deterministic injected-audio baseline | PASS | RMS 4799.94; peak 12000; 1 kHz magnitude 3745.03 |
| Enabled DSP, ECHT v2 HMAC, replay/tamper/stale-nonce proof | BLOCKED | Current module lifecycle is not ready |

The PASS gates above are module-independent. They do not prove that the legacy effect attached or
mutated audio.

The baseline used 100 raw-stdin gRPC packets of 960 signed 16-bit mono samples at 48 kHz through
the emulator's official `EmulatorController.injectAudio` method. The API v7 result recorded
`oneWayPidZeroRejected=true`, provider generation 30, and the non-silent measurements above.

Running the enabled method without readiness arguments produced AndroidJUnitRunner status `-4`
with `actual=null/null/null/null/null/null`. The cache file list, timestamps, and provider handoff
state were identical before and after, proving that the assumption occurred before evidence cleanup,
provider registration, or effect work.

## Current module blocker

The emulator has no `/data/adb/modules/echidna/module.prop`. Its mounted effect key is still labeled
`u:object_r:system_file:s0`, not `u:object_r:echidna_telemetry_key_file:s0`:

```text
-r--r----- 1 root audio u:object_r:system_file:s0 32 2026-07-16 10:34 preprocessor_telemetry_hmac.key
```

The exact enforcing-SELinux denial captured from the audio HAL is:

```text
type=1400 audit(0.0:132): avc: denied { read } for comm="binder:7111_2" name="preprocessor_telemetry_hmac.key" dev="dm-34" ino=65936 scontext=u:r:hal_audio_default:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

The controller SPKI is affected by the same incomplete install:

```text
type=1400 audit(0.0:133): avc: denied { read } for comm="binder:7111_2" name="preprocessor_controller_p256.spki" dev="dm-34" ino=65935 scontext=u:r:hal_audio_default:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

Source commit `ed8fb3a` fixes the HMAC key-label lifecycle, but it does not fix the separate
controller-SPKI label shown above. The reproducible ZIP from that commit is
`build/echidna-selinux-key-final-a.zip`, SHA-256
`02785906A7ECC3AD74D87894A23BAC7DE1128F3725327CFB8137751693948257`. That ZIP has **not** been
installed or boot-completed on this emulator, and it remains incomplete for the SPKI denial.

Production commit `274c4c5` adds the corresponding controller-SPKI lifecycle fix. Final packaging
HEAD `cea16af9a4a617e6277b5e55cfb5bf2619ebaf0f` hardens verification of the complete artifact. The
final reproducible ZIP was independently verified at SHA-256
`54dd0e373fbc3bc050dd8147ffdfbc6ea613d050dcaf36f09ea3e59ebd95834f`, but it was **not installed**
or boot-completed on this emulator. The live device therefore still represents the incomplete old
Magisk install and both AVCs above, not the final source/package fixes.

The enabled instrumentation test therefore assumption-skips before provider/effect work unless the
host supplies the exact `ready`, module ID, version, version code, source commit, and ZIP hash tuple.
The host may supply that tuple only after verifying the installed module, completed reboot, key
label/read access, and absence of relevant AVCs. Once supplied, effect attachment, authorization,
capability issuance, audio mutation, HMAC, replay, tamper, and stale-nonce failures are hard failures.

## Completion statement

This is **not** feature-complete evidence. A boot-completed install of the final verified ZIP and a
green enabled proof are still required before claiming functional legacy input DSP on API 33.
