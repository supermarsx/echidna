# Design Rationale

This page explains **why** the load-bearing engineering choices were made. Each
decision is tied to the constraint that forced it, so the design reads as a chain
of "we had to do X because Y", not a list of preferences. For *what* was built,
see [Architecture](architecture.md); for the platform obstacles that shape all of
this, see [Why It's Hard](why-hard.md).

## Why Zygisk + LSPosed (root injection, not an official API)

**Constraint:** Android exposes **no supported API** to intercept or transform
another app's microphone capture. AAudio, OpenSL ES, `AudioRecord`, AudioFlinger,
tinyalsa, and the vendor HAL are all *inside* the target app's process (or a
system service), and no public mechanism lets one app rewrite another app's audio
buffers.

**Decision:** run **inside** the target process. Zygisk (a Magisk feature) loads
a native module into every specialized app process at fork time, which is exactly
where the capture callbacks live — so the DSP can process a block in-place with
no cross-process copy. LSPosed provides the parallel Java-side path for apps that
capture through `AudioRecord`, and lets policy be resolved per process. Both
require root because there is no other way to place code in a foreign process.
The trade-off is explicit: Echidna needs Magisk + Zygisk and cannot function as
an unrooted app for the native path. That cost buys the only viable interception
point.

## Why a native C++ DSP engine

**Constraint:** the DSP runs **synchronously inside the audio capture callback**.
Capture callbacks are real-time: they must return within a block period (single-
digit to low-tens of milliseconds) or the audio stack under-runs (xruns, glitches,
dropped frames). Per-sample pitch/formant/auto-tune work is CPU-heavy and cannot
tolerate a garbage-collector pause or JNI marshalling on the hot path.

**Decision:** implement the engine as native C++ (`libech_dsp.so`) with a
synchronous in-callback path and an optional hybrid worker path for heavier,
higher-quality transforms. Native code gives deterministic latency, direct
in-place buffer processing, and room for NEON/AVX per-ABI optimization. The C ABI
(`echidna_process_block(in, out, frames, sr, channels)`) is a single-block
synchronous call so it drops straight into any capture callback the hook chain
intercepts. A managed-language engine on the callback thread would risk
non-deterministic pauses precisely where determinism matters most.

## Why the in-app service topology (single APK)

**Constraint:** the original design bound the companion app to a **phantom
`com.echidna.control` package** — a control service packaged as an Android
*library* (`.aar`), which is not independently installable, with **no app module
hosting `applicationId com.echidna.control`**. At runtime `bindService()`
resolved to nothing, `bound` stayed false, and every control/telemetry call was a
silently-caught no-op. The whole app→service→native control plane was inert.

**Decision:** host `EchidnaControlService` **inside the companion app** and ship
one installable APK. The app's Gradle build folds the `:service` module in
directly (`include(":service")` with a `projectDir` redirect), bundling the
service, the single canonical AIDL, and the JNI library into the one APK. The
bind target became an **in-package** `ComponentName(context, EchidnaControlService::class.java)`,
and the service is `exported="false"`. This removes the unresolvable cross-package
bind entirely, collapses two divergent AIDL copies into one contract (killing
drift risk), and means a user installs a single APK rather than hunting for a
second package that never existed. A `signature`-level permission is unnecessary
because the caller and callee are now the same app.

## Why memfd over `shm_open`

**Constraint:** the profile/telemetry shared regions originally used POSIX
`shm_open` / `shm_unlink`. **bionic (Android's libc) does not provide these** —
they simply do not link. The obvious Android replacement, `ASharedMemory_create`,
lives in **`libandroid`**, but the Zygisk target links **only `liblog`** (adding
`libandroid` is undesirable inside an injected module and caused an undefined-
symbol link failure).

**Decision:** back every shared region with an **anonymous `memfd`**
(`memfd_create` via the raw `syscall(__NR_memfd_create, …)`, then `ftruncate` +
`mmap`). `memfd_create` is a plain libc syscall — no extra library to link, works
from the injected module — and yields exactly what the design needs: a real fd of
fixed size, `mmap`'d locally, whose fd can be handed to another process over the
`AF_UNIX` socket via `SCM_RIGHTS`. A header-only, name-keyed, refcounted registry
preserves the old `shm_open("/name")` sharing semantic so writer and reader map
the same region. The Kotlin-side `SharedMemory` fd hand-off contract was left
untouched, so the wire protocol did not change.

## Why per-ABI NDK builds

**Constraint:** the code originally built **host** libraries — an x86_64 Linux
ELF from `cmake` on the CI runner — and dropped them into the Magisk zip. Those
cannot load on an arm64 Android device. Android ships native code per **ABI**, and
Zygisk selects a per-ABI `zygisk/<abi>.so` per process. The spec targets
arm64-v8a, armeabi-v7a, and x86_64; zero were being produced correctly.

**Decision:** cross-compile **per ABI** with the NDK toolchain
(`tools/build_native_ndk.sh` loops the ABI set into `build/<abi>/lib/`). This
produces the six real Android `.so` (DSP + Zygisk × three ABIs) that the Magisk
module actually needs: `libechidna.so` → `zygisk/<abi>.so`, `libech_dsp.so` →
`system/lib(64)`. Per-ABI builds also enable ABI-specific SIMD and are the only
way the trampoline code (which is inherently architecture-specific — see
[Architecture](architecture.md#multi-abi-hooking)) can be compiled and shipped for
each target. arm64-v8a is the locked primary; x86_64 is fully supported; armv7
degrades gracefully rather than shipping unsafe relocation.

## Why fail-closed security

The security posture is **default-deny at every layer**, because the failure mode
of a voice-interception tool is severe: a hook that runs when it shouldn't
silently rewrites a user's microphone in an app they didn't authorize.

- **Whitelist default-deny.** The default profile snapshot is `empty()` — empty
  whitelist, global off. Hooking is enabled **only** when the engine is globally
  enabled *and* the specific package/process is explicitly whitelisted `true`.
  Absent or `false` entries mean *not hooked*. **Constraint:** before the first
  snapshot push, on an unreadable/unparseable snapshot, or if the receiver never
  binds, the system must not hook — so "no information" has to resolve to "deny",
  not "allow".
- **Buffer zeroing when not allowed.** In the LSPosed path, when a capture is not
  allowed, `AudioRecordHook` zeroes the returned buffer and forces `read()` to
  return 0 rather than passing raw audio through. **Constraint:** a disallowed
  hook must fail *safe* (no audio leaks through the partially-installed path), not
  merely fail to transform.
- **Ed25519 plugin signing.** Third-party DSP plugins are verified with Ed25519
  (BoringSSL/OpenSSL raw-key verify, `.sig` sidecar) and verification is
  **fail-closed**: when BoringSSL is not linked, `VerifySignature` returns
  `false`, so an unverifiable plugin never loads. The trusted key is build-
  provisioned (`ECHIDNA_TRUSTED_PLUGIN_PUBKEY` compile-def) with an all-zero
  fail-closed placeholder. **Constraint:** plugins load into a real-time audio
  engine running in a foreign process; an unsigned or unverifiable plugin is a
  code-execution risk, so the safe default when signing is unavailable is to
  refuse to load anything.

Taken together, every ambiguous state — no snapshot, no signature, no verified
key, no whitelist entry — resolves to the non-acting, non-leaking outcome. That is
the deliberate stance for a tool with this much reach into other apps' audio.

## Why BoringSSL (and why it is layered)

**Constraint:** Ed25519 verification needs a crypto library, but the NDK sysroot
ships no `libcrypto`, and vendoring a large binary into git is undesirable.

**Decision:** a layered, first-hit-wins strategy — a prebuilt install
(`-DECHIDNA_BORINGSSL_ROOT`), then a system `find_library(crypto)`, then
**FetchContent BoringSSL** (Android-only, pinned tag, fetched into the build tree,
nothing vendored). If none resolve, the build emits a loud warning and plugin
verification stays fail-closed. This keeps the repository free of large binary
blobs while still enabling on-device plugin signing when a crypto backend is
available, and lets a reproducible/offline Docker build pin or mirror the tag.
