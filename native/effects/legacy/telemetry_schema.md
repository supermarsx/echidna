# Legacy preprocessor telemetry schema

The legacy AudioFlinger preprocessor exposes one bounded, read-only telemetry parameter through the
standard `EFFECT_CMD_GET_PARAM` command. This is a diagnostic snapshot, not an authorization or
configuration interface.

## Query framing

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
