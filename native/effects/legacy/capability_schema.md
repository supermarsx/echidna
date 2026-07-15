# Legacy preprocessor capability schema

This document is the authoritative binary contract for authorizing Echidna's legacy Android input
preprocessor. It covers the native control boundary only. Packaging, effect registration, companion
attachment, and device certification remain separate release concerns.

The contract implements the short-lived, authenticated control requirement behind the DSP checks in
[specification Section 20](../../../spec.md#20-qa-checklist-ui--dsp). All parsing, hashing, signature
verification, JSON decoding, and DSP preparation happens in `EFFECT_CMD_SET_PARAM`, outside the audio
callback. The callback reads only atomic gates and the atomic expiry deadline.

## Cryptographic contract

- Algorithm: ECDSA over NIST P-256 with SHA-256.
- Signature encoding: strict ASN.1 DER as accepted by the platform EVP verifier, 64 to 80 bytes.
- Signed bytes: the complete capability body, beginning at body offset 0 and ending after the preset.
- Trust anchor: one DER SubjectPublicKeyInfo file at
  `/system/etc/echidna/preprocessor_controller_p256.spki`.
- The SPKI must be a root-owned regular file, must not be group- or world-writable, must be at most
  1024 bytes, and must survive same-inode metadata checks across the read. Symlinks are rejected.
- The envelope never carries a key or certificate. There is no trust-on-first-use path.
- A missing, unsafe, malformed, wrong-curve, or non-matching key fails closed. Key-unavailable and
  signature failures latch identity bypass for the lifetime of that effect instance.

The production effect always requires owner UID 0 for the SPKI. The options field that substitutes an
owner exists only so an unprivileged host test can exercise the same file checks.

## Standard effect parameter framing

`EFFECT_CMD_SET_PARAM` uses AOSP's standard `effect_param_t` layout:

| Offset | Size | Field | Required value |
| ---: | ---: | --- | --- |
| 0 | 4 | `status` | Set to zero by the sender; ignored on input |
| 4 | 4 | `psize` | 8, native-endian AOSP ABI field |
| 8 | 4 | `vsize` | Capability-envelope byte count, native-endian AOSP ABI field |
| 12 | 8 | Parameter ID | `45 43 48 50 00 02 00 01` (`ECHP`, v2, authorize) |
| 20 | `vsize` | Value | Capability body, signature length, and signature |

The fixed 8-byte parameter ID is already four-byte aligned. The command must have exactly
`12 + align4(psize) + vsize` bytes and must not exceed 65,536 bytes. Extra or truncated bytes,
misaligned command storage, zero sizes, and integer overflow are rejected.

## Canonical signed body

All multi-byte fields inside the signed body use unsigned big-endian encoding. No padding, optional
fields, alternate order, or duplicate representation is permitted.

| Body offset | Size | Field | Constraint |
| ---: | ---: | --- | --- |
| 0 | 4 | Magic | ASCII `ECHC` |
| 4 | 2 | Schema | `0x0001` |
| 6 | 2 | Flags | `0x0001` (authorize), no other bits |
| 8 | 16 | Implementation UUID | `3e66a36e-dee9-5d81-a0d6-49fc3b863530` in canonical bytes |
| 24 | 4 | Audio session ID | Positive signed-32 value, exact effect-instance session |
| 28 | 4 | Target UID | Android user-0 application UID, 10,000 through 99,999 |
| 32 | 8 | Policy generation | 1 through `INT64_MAX`; strictly monotonic across changes |
| 40 | 8 | Issued time | `CLOCK_BOOTTIME` milliseconds |
| 48 | 8 | Expiry time | Later than issued and now; lifetime at most 5,000 ms |
| 56 | 16 | Nonce | Nonzero, fresh for every signed envelope |
| 72 | 32 | Preset hash | SHA-256 of the exact preset bytes below |
| 104 | 2 | Process length | 1 through 255 bytes |
| 106 | 4 | Preset length | 1 through 61,440 bytes |
| 110 | process length | Process | Canonical UTF-8 Android package/process identity |
| variable | preset length | Preset | Exact UTF-8 JSON bytes consumed by the DSP preset loader |

The process must contain a dotted Java-style package. Components begin with an ASCII letter and then
contain only ASCII letters, digits, or underscore. One optional `:suffix` is allowed; it follows the
same identifier rule. Empty components, multiple colons, invalid UTF-8, and alternate spellings are
rejected.

The issued time may be at most 250 ms ahead of the verifier's current `CLOCK_BOOTTIME`. The expiry
must be strictly in the future, strictly later than issuance, and no more than 5,000 ms after issuance.
Wall-clock time is never used.

Immediately after the signed body comes a two-byte big-endian DER-signature length, followed by that
many signature bytes. The length and the total envelope must be exact.

## State transitions

A valid new generation may be installed only while the effect is disabled. `ENABLE` returns `-EPERM`
until a signed capability has installed and prepared a valid preset.

A same-generation renewal is accepted while enabled only when all of these remain byte-for-byte
identical: target UID, process, and preset hash. It also requires a fresh nonce, a nondecreasing issued
time, and a strictly later bounded expiry. A reused nonce is replay, a lower generation is rollback,
and a same-generation identity or preset change is conflict. A higher generation while enabled is
busy and must be retried after disable.

The unsigned local revoke parameter ID is `45 43 48 50 00 02 00 02`. Its value is exactly one zero
byte. Revoke, disable, reset, expiry, and invalid authorization clear the policy/profile gates and
deadline immediately. They do not install data, reset the accepted generation, or weaken replay
checks. A later signed same-generation renewal may re-open the gates only for the already prepared,
identical identity and preset.

`Process` performs identity bypass whenever a gate is false or the wrap-safe 32-bit atomic expiry is
not in the future. Expiry therefore takes effect without JSON, crypto, allocation, locks, or DSP
preparation on the real-time path.

## Status values

Status is returned as the signed 32-bit `SET_PARAM` reply value using Linux/Android errno numbers.

| Status | Meaning |
| ---: | --- |
| `0` | Accepted |
| `-1` (`EPERM`) | Capability session differs from this effect instance, or enable lacks authorization |
| `-16` (`EBUSY`) | New generation attempted while enabled |
| `-17` (`EEXIST`) | Same-generation identity, preset, or expiry conflict |
| `-22` (`EINVAL`) | Noncanonical framing, claims, hash, UTF-8, or preset JSON |
| `-114` (`EALREADY`) | Accepted nonce replay |
| `-116` (`ESTALE`) | Generation rollback |
| `-126` (`ENOKEY`) | SPKI missing or unsafe; instance permanently bypasses |
| `-127` (`EKEYEXPIRED`) | BOOTTIME issuance or expiry window invalid |
| `-129` (`EKEYREJECTED`) | SPKI/signature mismatch; instance permanently bypasses |

## Canonical unsigned fixture

This fixture fixes encoding independently of any nondeterministic ECDSA DER signature.

- Session: `0x01020304`
- UID: 10,000
- Generation: 1
- Issued/expiry: 100,000 / 105,000 BOOTTIME ms
- Nonce: bytes `01` through `10`
- Process: `com.example.recorder` (20 bytes)
- Preset (104 bytes):
  `{"name":"F","engine":{"latencyMode":"LL","blockMs":10},"modules":[{"id":"mix",`
  `"wet":100,"outGain":-12}]}`
- Preset SHA-256: `24f6e2d400359721a2066c755d48e5deb83171e07d429b7c26c82bca8d7fa69a`
- Signed-body size: 234 bytes
- Signed-body SHA-256: `b1c81db05b2c941cfae0ae8c2f403c83585a3bd8431f76a78627c0004841c2af`

Canonical signed-body hex:

```text
45434843000100013e66a36edee95d81a0d649fc3b8635300102030400002710
000000000000000100000000000186a00000000000019a280102030405060708
090a0b0c0d0e0f1024f6e2d400359721a2066c755d48e5deb83171e07d429b7c
26c82bca8d7fa69a001400000068636f6d2e6578616d706c652e7265636f7264
65727b226e616d65223a2246222c22656e67696e65223a7b226c6174656e6379
4d6f6465223a224c4c222c22626c6f636b4d73223a31307d2c226d6f64756c
6573223a5b7b226964223a226d6978222c22776574223a3130302c226f757447
61696e223a2d31327d5d7d
```

Append the big-endian DER signature length and a P-256/SHA-256 signature over exactly these 234 bytes.
`legacy_effect_capability_test` pins the complete body hex and both hashes.

## Identity limitation

The legacy effect ABI provides the effect session ID but does not independently expose the recording
owner UID or process. UID/process are therefore exact signed controller assertions, not AudioFlinger
owner attestation. Device documentation must retain this distinction until a privileged owner signal
exists.
