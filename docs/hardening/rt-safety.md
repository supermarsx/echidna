# Real-time-safety audit — non-AAudio capture routes (§9 / §19)

**Scope:** every non-AAudio capture route's hot path (the audio callback / read
path that runs on a platform audio thread) audited for real-time-safety
violations: heap allocation/free, locks/mutex/condvar, blocking syscalls,
file/socket/Binder IO, logging, `dlopen`/`dlsym`, unbounded spins, and
exceptions escaping. AAudio (callback + read) is owned and audited by task
`t6-e1` (the §3 callback-ownership fix) and is cross-referenced here only.

**Audited at commit base:** `42e4300` (task t6). **Verifier:** host CTest +
allocation-tracker unit tests (see the Evidence column). Items that can only be
proven on a physical device / rooted-emulator-with-working-Zygisk are marked
**device-gated** and are *not* claimed as done.

**RT model.** Each capture route is admitted through a lock-free gate and then
runs the DSP against a bounded, pre-allocated slot. The pattern (shared by the
AAudio, OpenSL, and tinyalsa registries) is: an `admission_usage_` atomic word
with an active bit + an in-flight refcount; `acquireAdmission()` /
`acquireSlot()` are CAS loops that make progress on every iteration and are
capped by the in-flight mask (no unbounded spin, no lock); maintenance
(open/close/publish) takes a spin *gate* but only off the RT path. The DSP call
itself aliases the app-owned capture buffer in place, which is safe for capture
(read-direction) routes — the DSP engine reads all input into scratch before
writing output and rejects non-finite output (`stream_handle_registry`,
audited by `t6-e2`).

---

## Route-by-route table

| Route | Hot-path entry | Hot-path operations | RT-safe? | Evidence / status |
| --- | --- | --- | --- | --- |
| **AAudio callback** | `ForwardCallback` → `AAudioStreamRegistry::dispatchCallback` | pre-faulted per-stream scratch; lock-free admission+slot; DSP writes scratch (never platform input) | Yes (per §3 fix) | Owned by **t6-e1**; `aaudio_stream_registry_test` (platform-buffer-unchanged + zero-alloc). Cross-ref only. |
| **AAudio read** | `ForwardRead` → `AAudioStreamRegistry::process` | lock-free admission+slot; in-place DSP on app buffer | Yes | Owned by **t6-e1**; `aaudio_stream_registry_test`. |
| **OpenSL ES** | `QueueCallbackProxy` → `ProcessBuffer` → `OpenSlStreamRegistry::process` | lock-free callback-token acquire; bounded SPSC FIFO `pop()`; lock-free admission+slot; in-place DSP; lock-free telemetry `recordBlock` | **Yes** | `opensl_stream_registry_test` — zero-alloc under churn, fail-open (bypass/unavailable leave buffer byte-exact), close quiesces in-flight callback. FIFO: `opensl_buffer_fifo_test`. Full vtable-wrap lifecycle: `opensl_lifecycle_test`. Live inline-patch install is **device-gated**. |
| **tinyalsa** | `ForwardReadBytes` / `ForwardReadFrames` → `TinyAlsaStreamRegistry::process` | `ReadDepthGuard` (thread-local, no alloc); lock-free `framesForBytes` + admission+slot; `ProcessingAllowed()` = single atomic load; in-place DSP; lock-free telemetry | **Yes** | `tinyalsa_stream_registry_test` — byte-path + frame-path zero-alloc, fail-open on revoke (buffer byte-exact, never enters DSP), close-race pointer-reuse. Symbol gating: `tinyalsa_contract_test`. Live `pcm_read` interception is **device-gated**. |
| **AudioFlinger** | `install()` returns `false` | route intentionally **disabled** — no hook, no hot path | Yes (by construction) | `capture_route_reachability_test`. No PCM transform ABI is claimed on the audioserver boundary (documented in `audioflinger_hook_manager.cpp`). Any future support is a separate device-proven boundary. |
| **AudioRecord::read (native)** | `ForwardRead` → `RouteCaptureBufferInPlace` | atomic-refcount `acquireAudioProcessing()` permit; lock-free `ScratchLease` (fixed pool); `ProcessPcmBufferInPlace` (caller scratch, no alloc, fail-open); lock-free telemetry | **Yes** | `capture_buffer_router_test` — zero-alloc RT path, scratch-exhaustion fails closed with buffer untouched, non-finite/partial-failure never commits. Live exact-ABI hook install is **device-gated**. |
| **libc `read`** | `ForwardRead` → per-fd verdict cache → `RouteCaptureBufferInPlace` | lock-free per-fd verdict lookup (single atomic load); the `fstat()` + `readlink("/proc/self/fd/N")` classification runs **once per fd off the hot path** on a cache miss (see FINDING-1); then lock-free router path | **Yes (per-fd verdict cache)** | Router core proven by `capture_buffer_router_test`; the per-fd verdict cache (`fd_verdict_cache.h`, `fd_verdict_cache_test.cpp`) keeps the classifying syscalls off the hot read path — hot reads are a single wait-free atomic load, with `close`/`dup`/`dup2`/`dup3` invalidation keeping verdicts correct across fd reuse. Live on-device descriptor-reuse timing remains device-gated. Route is an opt-in developer contract (`ECHIDNA_LIBC_*`). |
| **capture_buffer_router** (shared) | `RouteCaptureBufferInPlace` → `ProcessPcmBufferInPlace` | lock-free `ScratchLease` over a fixed 4-slot pool; decode→process→finite-check→encode against caller memory; no alloc, no lock, no syscall, no log | **Yes** | `capture_buffer_router_test` — zero-alloc assertion, exhaustion fail-closed, guard-page boundary, sentinel bounds, non-finite rejection. Backs both AudioRecord and libc routes. |
| **LSPosed / legacy-preprocessor (Java)** | `AudioRecordHook.*ReadHook.afterHookedMethod` → `AudioReadTransaction.execute` → `NativeBridge.process*` | XposedBridge reflection trampoline; `ReadNestingGuard` thread-local; `RegionBackup` thread-local scratch (array reads amortize to zero alloc; **heap-`ByteBuffer` branch allocates `new byte[length]` per call** — FINDING-2); JNI into native DSP; managed-runtime GC always possible | **No (not hard-RT, by design)** | Audited by inspection only (Java, device-gated). Fail-open is structurally sound: the original `read()` result is always preserved and the exact region is restored on native failure/exception/policy-revocation (`AudioReadTransactionTest`, `ByteBufferProcessorTest`). |

---

## Findings

### FINDING-1 — libc `read` route ran `fstat` + `readlink` on the hot path (RESOLVED — per-fd verdict cache, t9)

**Original violation (base `42e4300`).** `libc_read_hook_manager.cpp::ForwardRead`
called `IsRawAudioDevice(fd)` on every intercepted `read()` that returned bytes.
`IsRawAudioDevice` issues **two blocking syscalls per call** — `fstat(fd, …)` and
`readlink("/proc/self/fd/<fd>", …)` (a `/proc` filesystem traversal) — plus an
`snprintf`, to decide whether the descriptor is `/dev/snd/*` or `/dev/audio`.
Because the libc `read` hook intercepts *all* reads in the process, this
classification ran inside the audio capture read path.

- **Why it was a violation:** `readlink` on `/proc/self/fd` is not a bounded,
  wait-free operation; under memory pressure or a slow `/proc` it can block. On
  a hard-RT audio thread this risks an xrun.
- **Resolution (t9):** the classification is now behind a **per-fd verdict cache**
  (`fd_verdict_cache.h` / `.cpp`, host-tested by `fd_verdict_cache_test.cpp`). The
  hot read path does a single lock-free, allocation-free, syscall-free atomic
  lookup; the `fstat` + `readlink` classification runs **once per fd**, off the hot
  path, only on a cache miss (first sighting). The classification *logic* is
  unchanged — only *when* it runs moved. fd-reuse correctness is handled by
  observing fd lifecycle: `invalidate` on `close`, and `alias`/`invalidate` on
  `dup`/`dup2`/`dup3`; if a lifecycle event cannot be observed the cache degrades
  to "miss → reclassify" rather than trusting a stale verdict.
- **Mitigation still present:** the route remains opt-in (guarded by the
  `ECHIDNA_LIBC_SR` / `_CH` / `_FORMAT` developer contract) and is not part of the
  default capture path.
- **Device-gated residual:** live on-device descriptor-reuse timing (real churn on
  a physical audio thread) is still device-gated; the host tests prove the cache's
  logic, lock-freedom, and lifecycle invalidation, not on-device timing.

### FINDING-2 — LSPosed Java path is not hard-RT; heap-`ByteBuffer` branch allocates per call (REPORTED, by design / device-gated)

The LSPosed shim hooks `AudioRecord.read(…)` in the managed runtime. This path
is fundamentally **not hard-real-time**: it runs through the XposedBridge
reflection trampoline and is subject to ART garbage-collection pauses. It is
acceptable *by design* because Java `AudioRecord.read` is a blocking call on the
app's own recording thread rather than a hard-RT data callback (unlike AAudio).

Two allocation notes from inspection:

- `AudioReadTransaction.RegionBackup` reuses a **thread-local** scratch buffer
  and only reallocates when it must grow, so `byte[]`/`short[]`/`float[]` array
  reads amortize to **zero allocation** after warmup.
- The heap-`ByteBuffer` copy path (`ByteBufferProcessor.processHeapRegion`)
  allocates `new byte[length]` on **every** call. AudioRecord's direct-buffer
  reads (the common path) do not hit this; heap `ByteBuffer` is the uncommon
  case.

**Fail-open is structurally correct** on this route: `AudioReadTransaction`
always returns the original `read()` result (including partial reads), backs up
the exact returned region before native work, and restores it on native
decline, thrown exception, or a concurrent policy revocation — never silence,
never a partially-mutated block.

### No source fixes applied to owned routes

The owned non-AAudio hook sources (`opensl_hook_manager.cpp`,
`tinyalsa_hook_manager.cpp`, `audioflinger_hook_manager.cpp`,
`capture_buffer_router.*`, and the OpenSL/tinyalsa registries) were already
real-time-clean on their hot paths — lock-free admission, bounded pre-allocated
slots, no alloc/lock/blocking-IO/logging in the callback/read path. No RT fix
was required or applied to them; the work here strengthened the host tests that
lock those properties in (below).

---

## Tests added / strengthened (host-verifiable)

All run green under the host CTest build (`cmake -S native -B <dir>`;
`ctest -R '<route>_test'`). clang-format 18 clean.

- **`capture_buffer_router_test.cpp`** — added an `operator new` allocation
  tracker (previously absent) and:
  - `TestRouterRealtimePathAllocatesNothing`: 512 int16 + 512 float in-place
    routes with **zero allocation** on the RT path.
  - `TestScratchExhaustionFailsClosedUnchanged`: with all 4 scratch slots pinned
    by concurrent leases, a further capture **fails closed** and leaves the app
    buffer byte-for-byte unchanged (fail-open audio).
- **`opensl_stream_registry_test.cpp`** — added an in-flight process gate to the
  fake DSP and:
  - `TestFailOpenPreservesWholeBufferUnchanged`: unmatched recorder identity →
    `kUnavailable`, revoked admission → `kBypassed`, each leaving a multi-sample
    buffer byte-exact (`memcmp`).
  - `TestCloseQuiescesInFlightCallback`: `close()` blocks until an in-flight
    callback returns before destroying the DSP handle; closed identity is stale.
- **`tinyalsa_stream_registry_test.cpp`** —
  - `TestByteReadPathNoAllocationAndFailOpen`: byte-oriented (`pcm_read`) frame
    resolution + processing over 256 iterations with **zero allocation**, and a
    revoked admission bypasses with the capture buffer byte-for-byte unchanged
    and never entering the DSP.

Pre-existing coverage retained: zero-alloc churn, transactional profile
publish/revoke, close-race pointer reuse, oversized/misaligned fail-closed,
guard-page boundary, non-finite/partial-failure rejection.

---

## Residual violations (honest summary)

| Route | Residual | Severity | Disposition |
| --- | --- | --- | --- |
| libc `read` | classifying `fstat` + `readlink` now off the hot path (FINDING-1) | Resolved on host | Fixed in t9 via a per-fd verdict cache (`fd_verdict_cache.h`); hot path is a lock-free atomic lookup. On-device descriptor-reuse timing device-gated. Opt-in developer route. |
| LSPosed (Java) | not hard-RT; heap-`ByteBuffer` per-call alloc (FINDING-2) | Non-hard-RT by design | Reported; inherent to managed-runtime hook. Fail-open verified by inspection + JVM unit tests. |
| All routes | live inline-patch install, real audio-thread timing, xrun measurement | n/a | **Device-gated** — host tests prove logic/allocation/fail-open, not on-device timing. |

Telemetry `recordBlock` on every hot path is a lock-free atomic `fetch_add`
(`telemetry_accumulator.h`), and the SharedState RT accessors
(`audioProcessingAllowed()` = one atomic load; `acquireAudioProcessing()` =
atomic CAS refcount) are lock-free; full telemetry-state proof is owned by
`t6-e4`.
