# Legacy preprocessor telemetry schema

The legacy AudioFlinger preprocessor exposes two bounded, read-only telemetry parameters through
the standard `EFFECT_CMD_GET_PARAM` command. Schema v1 is the unauthenticated diagnostic snapshot.
Schema v2 is a capability-incarnation-bound HMAC proof for the control service. Neither parameter
is an authorization or configuration interface.

## V1 diagnostic query framing

The parameter key is exactly eight bytes:

```text
45 43 48 54 00 01 00 01
 E  C  H  T  schema=1 id=1
```

The command is an aligned `effect_param_t` header followed by that key. `psize` must be 8 and the
command size must be exactly 20 bytes. `vsize` is the caller's value capacity. The successful reply
is 68 bytes: the 12-byte header, the aligned key, and a fixed 48-byte value. Implementations must
set the required reply size when the supplied capacity is too small. Command and reply storage may
alias, as they do in Android `AudioEffect::getParameter()`.

Unknown well-framed keys receive `status = -EINVAL` and no value. Malformed or misaligned framing
is rejected. No preset JSON, process name, target UID, signature, nonce, or controller key material
is returned.

## Snapshot value

All multibyte fields use big-endian byte order. Counters and sequence numbers are cumulative
unsigned 32-bit values and wrap modulo 2^32.

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `ECHT` |
| 4 | 2 | schema | `1` |
| 6 | 2 | parameter ID | `1` |
| 8 | 2 | encoded size | `48` |
| 10 | 2 | flags | bit 0 enabled, bit 1 authorized, bit 2 expired |
| 12 | 4 | session ID | Signed AudioFlinger session ID encoded as its 32-bit representation |
| 16 | 8 | generation | Last accepted signed capability generation, or zero |
| 24 | 4 | sequence | Successful snapshot sequence; first value is 1 |
| 28 | 4 | blocks | All `process()` invocations, including rejected calls |
| 32 | 4 | frames | Frames in structurally valid process calls |
| 36 | 4 | failures | Invalid calls plus DSP failures, modulo 2^32 |
| 40 | 4 | mutations | Successful DSP blocks whose emitted samples differ from ABI identity |
| 44 | 4 | reserved | Zero; readers must ignore |

`authorized` reflects the non-mutating state at snapshot time. `expired` is set when a signed
generation exists and its full 64-bit boot-time expiry has passed. Disable, reset, expiry, and
revocation do not erase the generation or cumulative counters. A new accepted signed generation is
reported explicitly. Counter fields can advance independently during a concurrent audio callback;
the sequence identifies the diagnostic read, not a transaction over callback atomics.

Mutation comparison is folded into the existing output-write loop. It performs no allocation,
locking, or second sample pass on the real-time path. Bypass and DSP-failure identity output do not
count as DSP mutations.

## Golden value fixture

For session 41, generation `0x0102030405060708`, sequence `0xffffffff`, all flags set, blocks
`0xfffffffe`, frames 5, failures `0xffffffff`, and mutations `0x80000000`, the value is:

```text
454348540001000100300007000000290102030405060708fffffffffffffffe
00000005ffffffff8000000000000000
```

## V2 authenticated proof query

The proof parameter is exactly 24 bytes: an eight-byte identifier followed by the expected nonce
from the signed capability that the caller installed.

```text
45 43 48 54 00 02 00 02 <16-byte signed capability nonce>
 E  C  H  T  schema=2 id=2
```

The command is exactly 36 bytes: a 12-byte aligned `effect_param_t` and the 24-byte parameter.
`psize` is 24 and `vsize` is the caller's value capacity. A successful reply is exactly 148 bytes:
the header, the unchanged 24-byte query, and a fixed 112-byte value. Command and reply storage may
alias. Malformed sizes, padding, overflow, trailing bytes, and misalignment are rejected.

The query nonce must match the most recently accepted signed capability nonce byte-for-byte. A
successful same-generation renewal immediately makes the prior nonce stale. Failed, replayed,
conflicting, or rolled-back capabilities never replace the nonce. The unsigned host-test preset
seam cannot create a proof incarnation. Initialization clears the incarnation; revoke, expiry,
disable, reset, and configuration changes retain the last accepted incarnation so the authenticated
state can report that its authorization gates are closed.

Well-framed proof errors return an `effect_param_t` prefix with no value:

| Status | Meaning |
| ---: | --- |
| `-61` (`ENODATA`) | No signed capability has ever been accepted after initialization |
| `-116` (`ESTALE`) | Query nonce does not match the accepted capability incarnation |
| `-126` (`ENOKEY`) | The HMAC key was unavailable, unsafe, or cryptography failed |

Insufficient request `vsize` or reply capacity reports the fixed 148-byte requirement and returns
`-ENOSPC`. Stale, unavailable, and malformed requests do not consume the proof sequence or compute
an HMAC. The v1 and v2 sequences are independent, so v2 polling cannot change v1 diagnostics.

## V2 proof value

All multibyte fields are big-endian. The authenticated body is the first 80 bytes. The 32-byte HMAC
tag follows it directly.

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `ECHT` |
| 4 | 2 | schema | `2` |
| 6 | 2 | parameter ID | `2` |
| 8 | 2 | encoded size | `112` |
| 10 | 2 | flags | bit 0 enabled, bit 1 authorized, bit 2 expired |
| 12 | 4 | session ID | Signed AudioFlinger session ID encoded as 32 bits |
| 16 | 8 | generation | Generation of the accepted signed capability |
| 24 | 16 | nonce | Exact nonce supplied by that accepted signed capability |
| 40 | 4 | sequence | Successful authenticated-proof sequence; first value is 1 |
| 44 | 4 | blocks | All `process()` invocations, including rejected calls |
| 48 | 4 | frames | Frames in structurally valid process calls |
| 52 | 4 | failures | Invalid calls plus DSP failures, modulo 2^32 |
| 56 | 4 | mutations | DSP blocks whose emitted samples differ from ABI identity |
| 60 | 16 | key ID | First 16 bytes of SHA-256 over the exact 32-byte HMAC key |
| 76 | 4 | reserved | Zero; readers must reject a nonzero value |
| 80 | 32 | tag | HMAC-SHA256 described below |

The HMAC input is the exact ASCII domain string
`ECHIDNA_PREPROCESSOR_TELEMETRY_PROOF_V2`, without a terminator, followed by value bytes 0 through
79. The key is not included in the response. Verifiers must validate the fixed header, allowed flag
bits, nonzero generation and nonce, zero reserved field, key ID, and HMAC in constant time before
using any state or counter.

The HMAC key is exactly 32 bytes at
`/system/etc/echidna/preprocessor_telemetry_hmac.key`. Production accepts only a regular,
non-symlink file owned by `root:AID_AUDIO` (`0:1005`) with exact mode `0440`. The loader uses
`O_NOFOLLOW`, validates size and metadata with `fstat`, reads exactly 32 bytes, and checks the same
inode and metadata again. Loading and key-ID hashing happen only during `EFFECT_CMD_INIT`, off the
audio callback. HMAC computation happens only for a valid `EFFECT_CMD_GET_PARAM` proof request.

A missing or unsafe proof key disables v2 only. It does not latch audio bypass, change capability
verification, disable DSP processing, or affect v1 diagnostics. The real-time `process()` path does
not read the key, compute hashes, allocate, perform file I/O, or acquire a telemetry mutex.

## V2 golden value fixture

For key bytes `00` through `1f`, session 41, generation `0x0102030405060708`, nonce bytes `01`
through `10`, sequence `0xffffffff`, all flags set, blocks `0xfffffffe`, frames 5, failures
`0xffffffff`, and mutations `0x80000000`, the key ID is
`630dcd2966c4336691125448bbb25b4f`. The complete value is:

```text
4543485400020002007000070000002901020304050607080102030405060708
090a0b0c0d0e0f10fffffffffffffffe00000005ffffffff80000000630dcd29
66c4336691125448bbb25b4f00000000416d73deaf6412d3bd08f9f905d69f07
aace87ae5f58ab1a73935083f8581a0d
```
