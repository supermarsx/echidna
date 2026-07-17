# AudioFlinger Capture Route — Design & Decision Note

**Status: Device-gated / not viable from the Zygisk vantage.**
The live AudioFlinger `RecordThread` capture transform is **not closeable from
this module** on a stock device. It is disabled in code, stays disabled, and is
explicitly **out of the landable set** for host- and emulator-tested work. What
*is* landable — a fail-closed, host-tested capture-buffer admission guard, hard
-OFF by default — is described in §5.

> **Governing honesty rule (from the report's prime directive):**
> route-presence ≠ route-use. A file named `audioflinger_hook_manager.cpp`
> existing is not interception. This route emits a truthful refusal
> (`install()` returns `false`) and must never report success it cannot back.

---

## 1. What the route *could* do (the intent)

Android's `AudioFlinger::RecordThread` is the in-process owner of a live capture
stream inside the **`audioserver`** system service. Every microphone path —
`AudioRecord`, AAudio input, OpenSL ES recorders, the app-side libc/HAL reads —
ultimately draws PCM that `RecordThread` has assembled from the input HAL. A hook
placed *there* would, in principle, be a single choke point that sees capture
audio for **every** app on the device at once, below the per-API app-process
routes Echidna already implements (AAudio, OpenSL, tinyalsa, libc_read,
AudioRecord). That breadth is the entire appeal of the route.

The transform it would perform is the same one every other route performs: read
the assembled PCM buffer, convert to float, run the DSP block, re-encode in
place. The DSP machinery for that already exists and is host-tested
(`audio/pcm_buffer_processor.cpp`, `dsp/`).

---

## 2. Exactly why it is gated off

The route is disabled for **architectural** reasons, not a missing patch. Two
independent walls each individually block it:

### 2.1 Zygisk never injects `audioserver`

Magisk's Zygisk injects code into processes **forked from the zygote** — i.e.
whitelisted *application* processes — by specializing them at fork time.
`audioserver` is a **native `init` service**, started directly from an `.rc`
file; it is never a zygote child and is never specialized by Zygisk. The module
therefore has **no code-execution vantage inside `audioserver`** at all. There is
nothing to place a PLT/inline hook *from*: the target address space is one the
module is never mapped into.

This is the same wall that gates the sibling **Audio HAL** route
(`audiohal_hook_manager.cpp`, `capture_route_reachability.h` →
`kAudioHalRoute` = `kUnsupported`). Both routes live behind the `audioserver`
process boundary.

### 2.2 `RecordThread` exposes no stable PCM-buffer transform ABI

Even hypothetically executing inside `audioserver`, there is no stable contract
to hook:

- `RecordThread::threadLoop()` is an internal C++ method with **no stable mangled
  symbol** and no PCM-buffer parameter in its signature — it drives an internal
  state machine, not a "here is a buffer, transform it" callback. A telemetry
  -only hook of it would observe activity but could **not** mutate capture audio;
  treating that as "capture interception" would be dishonest.
- `RecordThread::read()` / `processVolume()` and the buffer-provider vtables are
  **not stable AOSP contracts**. Their mangled names, object layouts, buffer
  ownership, and format metadata **change across Android versions and vendor
  forks**. There is no ABI-independent way, from a late symbol resolve, to know
  a candidate buffer's sample rate, channel count, format, or frame layout, nor
  to know it is safe to write back into. Guessing any of that is how you corrupt
  a system-service audio path and panic the device.

So the route fails **both** the "can we run code here" test **and** the "is there
a stable buffer transform ABI" test. Either one alone is fatal.

---

## 3. What it would actually take

Closing the *live* transform requires machinery that is deliberately outside this
module and outside t9's landable scope:

1. **A separately proven `audioserver`-injection boundary.** Not Zygisk — a
   distinct mechanism (e.g. an `LD_PRELOAD`/`.rc`-level companion, a Magisk
   service-mode payload, or a vendor-integration seam) that establishes
   *code execution inside `audioserver`* and proves it survives SELinux
   (`audioserver` runs in a confined domain) and the service's own restart
   behaviour.
2. **Per-device / per-version `RecordThread` layout facts**, supplied by that
   companion as stable, attested metadata — because the ABI is not stable, the
   layout cannot be assumed, it must be *provided and validated*.
3. **A separate product lane** to carry all of that, since it is device-specific
   and cannot be host- or emulator-proven the way the app-process routes can.

None of these are a code change to *this* Zygisk module. Enabling the route here
without them would fabricate capability.

---

## 4. Honest status

| Property | Verdict |
| --- | --- |
| Live `RecordThread` capture transform from Zygisk | **Not viable** (both walls in §2) |
| Reachability descriptor | `kAudioFlingerRoute` = `kUnsupported`, reason `unsupported_injection_boundary` (`capture_route_reachability.h`) |
| `install()` behaviour | Truthful refusal — returns `false`, logs the unsupported boundary |
| Host-/emulator-closeable in t9 | **No.** Device-gated, out of the landable set |
| Prerequisite to ever revisit | A separately proven `audioserver`-injection boundary (§3) |

This confirms the planner's finding. No refutation: no safe live path exists from
the Zygisk vantage on a stock device.

---

## 5. What *is* landed (the honest slice)

The one part that can be reasoned about and verified **without a device** is the
**admission-control predicate** a future `audioserver` companion would have to
satisfy *before* it were ever allowed to hand a raw `RecordThread` buffer to the
DSP. That predicate is landed as a standalone, host-tested header, **hard-OFF by
default**:

- **`native/zygisk/src/hooks/audioflinger_format.h`** — describes a candidate
  capture buffer in ABI-independent terms (a byte span plus PCM geometry: frame
  count, channel count, `PcmFormat`) and applies two independent fail-closed
  checks:
  1. **Boundary provenance.** `ECHIDNA_AUDIOFLINGER_BOUNDARY_PROVEN` defaults to
     `0`. While it is `0`, `AdmitAudioFlingerCaptureBuffer()` refuses **every**
     buffer regardless of contents — the code-level expression of "this route is
     disabled". Defining it to `1` is meaningful only inside a separate companion
     build that has actually proven the boundary of §3; it does not, on its own,
     make the route reachable in this module.
  2. **Descriptor self-consistency.** `ValidateAudioFlingerBufferGeometry()`
     rejects any descriptor whose byte length disagrees with its declared
     frame/channel/format geometry, reusing the shared `ResolveBufferLayout()`
     resolver so the byte/frame arithmetic has a single source of truth. This is
     exactly the check that must survive an *unstable* `RecordThread` ABI: a
     caller-supplied layout is never trusted blindly.

- **`native/zygisk/tests/audioflinger_format_test.cpp`** — host harness proving
  both properties: the geometry validator accepts self-consistent PCM spans and
  fail-closes on null / empty / bad-geometry / mismatched-length / unsupported
  -format inputs; and the full admission entrypoint refuses even a perfect,
  proven-provenance descriptor while the gate is off (the shipped configuration).

This mirrors the sibling device-gated route's standalone contract scaffold
(`audiohal_contract.h` + `audiohal_contract_test.cpp`): host-tested, and
intentionally **not** wired into the disabled `install()`. The hook manager
(`audioflinger_hook_manager.cpp`) is unchanged in behaviour — it still returns
`false` — and points at this note and the guard for traceability.

Nothing here enables capture. It is the safe, verifiable *interpretation logic* a
companion would need, sitting behind a gate that stays off until the boundary of
§3 is separately proven.
