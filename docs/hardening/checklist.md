# Engineering Hardening Checklist — status of record

This page is the definitive, honest map of the user's 24-section *"Engineering
hardening checklist for Echidna"* (assessed against base commit `42e4300`) to
**what task t6 actually changed, what was already in place, and what is still
outstanding.** It exists to prevent the one failure mode the report itself warns
against: **overstating proof.**

> **Prime directive (from the report).** Anything that can be proven only on a
> physical device, a rooted emulator with a working Zygisk, or specific OEM
> hardware is **Device-gated** — documented with a reproducible on-device
> procedure, **never marked "Done."** A green host build is not proof that a
> capture route transforms audio on a phone.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| **Landed-t6** | Changed or added by task t6. Carries a concrete evidence pointer (executor id + test name or file). |
| **Pre-existing** | Already delivered before t6 (prior t2 / t3 / t4 work). Pointer to where. |
| **Device-gated** | Real, but provable only on a physical device / rooted-emulator-with-working-Zygisk / OEM HAL. Carries the on-device procedure (see [Verification](../verification.md)). |
| **Open** | Landable but not yet done; the concrete next step is named. |
| **N/A-here** | Out of scope for a coding session (physical lab hardware, hiring, org process). |

## Tally

| Status | Sections (by primary disposition) |
| --- | --- |
| **Landed-t6** | §3, §9, §10, §18, §19 — the landable subset of this task (5 sections materially advanced). |
| **Pre-existing** | §1, §2 (host half), §4, §5, §6, §8, §11, §12, §13, §14 (config half), §15, §16, §20, §21, §22 |
| **Device-gated** | §2 (E2E half), §7, §17 (operation), §23 (M3/M5), plus the live-proof half of §4/§5/§6/§14 |
| **Open** | §18-F2 (wire schema v3), and named next-steps inside §7, §16, §23 |
| **N/A-here** | §17 (procuring/racking physical hardware), org-process items in §20 |

Most sections are **mixed**: a host-verifiable core that is Pre-existing or
Landed-t6, and a live-on-device half that stays Device-gated. Each section below
gives the headline status and breaks out the sub-items that differ.

**What you might expect to be "Done" but is honestly not** (called out so nobody
is surprised): §2 end-to-end artifact proof, §4/§5/§6 live capture, §7 legacy
preprocessor on-device, §14 enforcing-SELinux propagation to hooked apps, §17
physical-device lab, and §23 milestones M3/M5 are all **Device-gated** — the code
and host tests exist, the on-device proof does not. §18-F2 (richer telemetry on
the wire) is **Open**, deliberately blocked on a security control (see §18).

---

## 1 — Define what "works" means

**Status: Pre-existing (state ladder) + Landed-t6 (telemetry realization of it).**

- The supported-unit tuple (device × Android × ABI × route × app) and the
  "unsupported routes fail closed and must not appear installed/active" rule are
  already codified: `todo.md` capture-status table + `native/zygisk/src/hooks/capture_route_reachability.h`, [spec §4.2]. **Pre-existing** (t2/t3).
- The `installed → loaded → policy-connected → admitted → hook-installed →
  stream-discovered → processing → mutating → verified` ladder is now written
  down and each rung labelled host-observable vs device-gated:
  [`evidence-state-model.md` §4](evidence-state-model.md#4-mapping-to-the-report-1-state-ladder). **Landed-t6 (t6-e4).**
- Which rungs can be *witnessed* host-side vs only on-device is explicit in that
  same table. The "verified" rung (measured transform) has **no single flag** and
  is **Device-gated** by construction.

## 2 — Non-negotiable release gate (exact-artifact E2E proof + evidence bundle)

**Status: Pre-existing (host/artifact gate) + Device-gated (on-device E2E).**

- **Host + artifact gate — Pre-existing (t2-e20 / t3):** signed release APK
  verified against a pinned non-debug cert, exact per-ABI `.so` payload checks,
  deterministic Magisk-zip layout verification, full host CTest, format gate.
  See [Verification §1](../verification.md#1-verified-on-this-build-host-emulator-real-reproducible).
- **Exact-artifact end-to-end proof on a device (build the shipping zip →
  flash → capture live audio → measure transform) — Device-gated.** The closest
  real evidence is t4's on-device −7-semitone measurement, but that used an NDK
  harness calling the same `echidna_process_block` the hook uses, **not** a live
  app's AudioRecord (t4-e5/e7 logs; see [Verification §2](../verification.md#2-still-not-verified-device-gated)). Procedure: [Verification §3](../verification.md#3-reproduce-on-a-real-device-the-device-gated-procedure).
- **Evidence bundle** (sanitized diagnostics export) exists in-app
  (Diagnostics → Export diagnostic internals). **Pre-existing.**

## 3 — Fix the AAudio callback route  *(report P0 anchor)*

**Status: Landed-t6 (t6-e1).**

The report's headline P0: the AAudio **callback** route passed the
platform-owned input buffer as both DSP input *and* output, so the DSP wrote
memory the AAudio contract forbids mutating in place.

- Fix: `AAudioStreamRegistry::dispatchCallback` now runs the DSP into a
  **pre-faulted per-stream scratch buffer**, never the platform input; the app
  callback receives the scratch pointer on `kProcessed`, or the untouched
  platform input on any non-process outcome (**fail-open**); the slot's in-flight
  ref is **held across the app callback** so scratch cannot be reclaimed;
  `close()` frees scratch only after the in-flight drain. `ForwardCallback`
  rewired to `dispatchCallback` (read route still uses `ProcessPcmBuffer`).
- Evidence: `native/zygisk/tests/aaudio_stream_registry_test.cpp` —
  `TestCallbackDispatchOwnershipTransparencyAndFailOpen` (platform input
  byte-for-byte unchanged; app callback gets a *different*, mutated pointer;
  return value preserved; fail-open hands back untouched input),
  `TestCallbackDispatchHoldsScratchAcrossAppCallback`,
  `TestCallbackDispatchNoRealtimeAllocation` (zero-alloc on the dispatch RT path
  via the file's `operator new` tracker). GATE-1 (t6-e5) built + ran green.
- **Live proof on hardware** that a real AAudio input callback fires and the
  transform is audible remains **Device-gated** ([Verification](../verification.md), route table).

## 4 — Harden OpenSL ES

**Status: Pre-existing (host lifecycle coverage) + Landed-t6 (RT/fail-open locks) + Device-gated (live capture).**

- PCM descriptor, queue FIFO, rollback, callback, vtable-wrap, and destroy
  lifecycle have host coverage. **Pre-existing** (`opensl_lifecycle_test`,
  `opensl_buffer_fifo_test`).
- t6 strengthened the hot-path guarantees: zero-alloc under churn, fail-open
  leaves the buffer byte-exact (`kUnavailable`/`kBypassed`), `close()` quiesces
  the in-flight callback. **Landed-t6 (t6-e3):**
  `native/zygisk/tests/opensl_stream_registry_test.cpp`
  (`TestFailOpenPreservesWholeBufferUnchanged`, `TestCloseQuiescesInFlightCallback`);
  audit row in [`rt-safety.md`](rt-safety.md#route-by-route-table) (RT-clean).
- **Live inline-patch install + real recorder capture — Device-gated.**

## 5 — Harden tinyalsa

**Status: Pre-existing (host lifecycle) + Landed-t6 (RT/fail-open locks) + Device-gated (live route).**

- `pcm_open` config, read lifetimes, symbol gating have host coverage.
  **Pre-existing** (`tinyalsa_contract_test`).
- t6 added byte-path + frame-path zero-alloc and fail-open-on-revoke tests
  (buffer byte-exact, never enters DSP). **Landed-t6 (t6-e3):**
  `native/zygisk/tests/tinyalsa_stream_registry_test.cpp`
  (`TestByteReadPathNoAllocationAndFailOpen`); [`rt-safety.md`](rt-safety.md#route-by-route-table) (RT-clean).
- **Live `pcm_read` interception on a real vendor route — Device-gated.**

## 6 — Harden LSPosed Java AudioRecord

**Status: Pre-existing (transactional read + JNI) + Landed-t6 (RT audit-by-inspection) + Device-gated (live injection).**

- Dedicated shim JNI + DSP packaging and transactional Java reads exist.
  **Pre-existing** (t2; `AudioReadTransactionTest`, `ByteBufferProcessorTest`).
- t6 audited the Java hot path by inspection (device-gated, cannot run host-side)
  and recorded it honestly: **not hard-RT by design** (XposedBridge reflection +
  ART GC); array reads amortize to zero alloc via a thread-local `RegionBackup`;
  the heap-`ByteBuffer` branch allocates `new byte[length]` per call (FINDING-2);
  fail-open is structurally sound. **Landed-t6 (t6-e3):**
  [`rt-safety.md` FINDING-2](rt-safety.md#finding-2-lsposed-java-path-is-not-hard-rt-heap-bytebuffer-branch-allocates-per-call-reported-by-design-device-gated).
- **Live LSPosed injection + authenticated Binder policy + transform under
  enforcing SELinux — Device-gated** ([Verification §2/§3](../verification.md#2-still-not-verified-device-gated)).

## 7 — Complete the legacy input-preprocessor path

**Status: Pre-existing (packaging + next-boot registration) + Device-gated (session-attach + on-device proof) + Open (attach/enable).**

- `libechidna_preproc.so` is packaged per ABI; conditional next-boot HIDL
  registration is staged **without auto-apply**; host fixtures prove first-OTA
  refusal and bounded activation rollback. **Pre-existing** (t2; `todo.md` line
  "Package `libechidna_preproc.so` …"; [Verification route table](../verification.md#current-capture-route-status)).
- **Session-attach + enable, then prove device audio / latency / SELinux —
  Open → Device-gated.** The attach/enable manager (default-off, signed
  short-lived capability) is the concrete next code step (**Open**); the proof of
  real factory discovery, post-fs/mount ordering, magic-mount label/linker
  namespace, and device audio mutation is **Device-gated**. This is report
  Milestone M1 (legacy-preprocessor proof) and is **not** claimed done.

## 8 — Audit the inline-hooking backend

**Status: Pre-existing.**

- Multi-ABI inline hook: aarch64 primary; x86_64 relocating trampoline (14-byte
  abs `jmp[rip]`, allow-listed length decoder, fails closed on anything
  unrecognized) with a host decoder harness (23/23) + end-to-end hook harness;
  armeabi-v7a intentionally **disabled** (`hook_unsupported_abi`) until a proven
  Thumb-2/IT-block relocator exists. **Pre-existing** (t2-e11 log;
  `native/zygisk/src/hooks/inline_hook.cpp`).
- Zygisk load-blocking backend bugs were found and fixed on-device by t4:
  `extern "C"` on `REGISTER_ZYGISK_MODULE` (commit `0817ef2`) and the Zygisk API
  v3 downgrade so Magisk accepts the module (commit `f47be79`). **Pre-existing
  (t4).**
- **x86_64 under real injection + a safe armv7 relocator — Device-gated / Open**
  (armv7 is an explicit non-goal unless 32-bit becomes a release target).

## 9 — Enforce real-time safety in the data plane

**Status: Landed-t6 (audit + host locks) with one honest Device-gated residual.**

- Every owned non-AAudio route (OpenSL, tinyalsa, AudioFlinger [disabled],
  AudioRecord native, capture_buffer_router) audited **RT-clean**: lock-free
  bounded admission, pre-allocated slots, no alloc/lock/blocking-IO/log/`dlopen`
  in the callback/read path; DSP aliases the app-owned buffer safely. No source
  fix was required on owned routes; host tests now lock zero-alloc + fail-open +
  close-quiesce. AAudio RT-safety is covered by the §3 fix.
  **Landed-t6 (t6-e1 for AAudio, t6-e3 for the rest):** full route table in
  [`rt-safety.md`](rt-safety.md#route-by-route-table).
- **Residual (honest): libc `read` route runs `fstat()` + `readlink()` per read**
  — a real RT violation. **Device-gated fix** (per-fd verdict cache needs
  fd-lifecycle correctness, provable only on-device with descriptor reuse); route
  is opt-in (`ECHIDNA_LIBC_*`). [`rt-safety.md` FINDING-1](rt-safety.md#finding-1-libc-read-route-runs-fstat-readlink-on-the-hot-path-reported-device-gated-fix).
- **On-device xrun / callback-timing measurement — Device-gated.**

## 10 — Harden DSP correctness & audio quality

**Status: Landed-t6 (t6-e2).**

- New/extended host tests lock: NaN/inf input rejection with the original block
  preserved; non-finite output guard (mid-block poison → no partial mutation);
  processing-failure → original preserved (not silence); neutral-preset float
  bit-exactness (mono/stereo/−0.0); denormal handling stays finite; PCM16 neutral
  bit-exact and near-neutral ≤ 2 LSB; bypass bit-exact with **no stateful-EQ
  advance**; boundary/canary/guard-page + 64 random block sizes.
  **Landed-t6 (t6-e2):** `native/dsp/tests/dsp_quality_test.cpp` (registered in
  `native/dsp/tests/CMakeLists.txt`) + `stream_handle_registry_test.cpp` (+620
  lines). GATE-1 green.
- **Honest design note:** the pure DSP engine is **GIGO** (garbage-in →
  garbage-out) by design; the zygisk `stream_handle_registry` is the sole
  sanitizer boundary — now test-locked. Real acoustic/latency quality on hardware
  remains **Device-gated** (see §2).

## 11 — Formalize lifecycle & concurrency state machines

**Status: Pre-existing + Landed-t6 (documentation + host locks).**

- The admission / in-flight-refcount / slot state machines are implemented and
  lock-free: `state/shared_state.{h,cpp}` (`AudioProcessingPermit`,
  `audio_processing_usage_`), the per-route registries' `admission_usage_` CAS
  loops, and the latched `InternalStatus` enum. **Pre-existing** (t2).
- t6 documented the three-layer model (lifecycle status vs admission permit vs
  per-route telemetry) and the non-conflation guarantees, and locked
  close-quiesce / drain behavior in host tests.
  **Landed-t6 (t6-e3/e4):** [`evidence-state-model.md` §1](evidence-state-model.md#1-where-evidence-lives-three-layers);
  close-quiesce tests in the route registries.
- **Concurrency correctness under real multi-app Zygisk specialization —
  Device-gated.**

## 12 — Harden policy / identity / capture-ownership

**Status: Pre-existing.**

- Strict policy v2: profiles, explicit default, bindings, whitelist,
  `captureOwners`, full control state, monotonic service generation; admission
  defaults to **deny** and requires master/bypass/panic + whitelist +
  default/binding + matching `zygisk`/`lsposed` owner gates; malformed /
  incomplete / rollback / conflicting policy fail closed. Authenticated
  UID-scoped Zygisk `AF_UNIX` publisher + read-only authenticated LSPosed Binder
  provider. **Pre-existing** (t2; `todo.md` "Control plane and policy").
- **Simultaneous injected-app delivery + capture-owner enforcement under
  enforcing SELinux — Device-gated** ([Verification §2](../verification.md#2-still-not-verified-device-gated)).

## 13 — Formal threat model

**Status: Pre-existing (controls) + Open (a single written threat-model doc).**

- The security controls a threat model demands are implemented and were treated
  as hardening features: authenticated UID-scoped transports, fail-closed
  admission, the **strict exact-key-set telemetry validator**
  (`AuthenticatedTelemetry.kt`), per-install trust keys (controller SPKI,
  telemetry HMAC) with narrow SELinux labels, all-zero fail-closed plugin key.
  **Pre-existing** (t2). Rationale captured in `docs/design-rationale.md` /
  `docs/limitations.md`.
- A **single consolidated formal threat-model document** (assets, adversaries,
  trust boundaries, STRIDE-style enumeration) is not yet written as one artifact.
  **Open** (documentation task; controls already exist to describe).

## 14 — Tighten SELinux

**Status: Pre-existing (narrow policy in the module) + Device-gated (enforcing propagation).**

- The flashable Magisk package ships a **narrow `sepolicy.rule`** with dedicated
  types for config/telemetry and the controller-SPKI / telemetry-HMAC files, no
  private-service offset staging; the module verifier checks the read-only
  trust-input label lifecycle. **Pre-existing** (t2-e14; [Verification §1](../verification.md#1-verified-on-this-build-host-emulator-real-reproducible)).
- **Config propagation to hooked apps under *enforcing* SELinux is a known
  device-gated gap:** `/data/local/tmp/echidna` is `shell_data_file`, so hooked
  apps can't read it; t4 fell back to process-local memfd. Making whitelist/preset
  actually reach hooked apps under enforcing SELinux — **Device-gated** (t4-e8/e10
  logs; [Verification "Historical enforcing-emulator blocker signal"](../verification.md#2-still-not-verified-device-gated)).

## 15 — Make installation & recovery boring

**Status: Pre-existing.**

- Single module id `echidna`, per-ABI Zygisk/DSP payloads, recovery markers, boot
  watchdog, narrow SELinux types, `customize.sh` ABI placement; deterministic zip
  with a verified exact-file layout; signed release with fail-closed signing
  preflight and a debug fallback for local builds. **Pre-existing** (t2-e13/e14;
  `docs/magisk_release.md`, [Verification §1](../verification.md#1-verified-on-this-build-host-emulator-real-reproducible)).
- **Magisk-Manager flash + reboot + watchdog/recovery-marker behavior on a real
  device — Device-gated** (`magisk --install-module` returned *Incomplete Magisk
  install* on the rooted emulator; [Verification §2](../verification.md#2-still-not-verified-device-gated)).

## 16 — Strengthen CI & automated testing

**Status: Pre-existing + Open (device lanes).**

- CI runs the real DSP/hook smoke harness ungated, headless unit tests, format
  gate (clang-format 18), blocking native cppcheck with line-scoped suppressions;
  release pipeline is tag/dispatch-gated with per-ABI build + signed
  assembleRelease + Magisk zip. **Pre-existing** (t2-e15/e17). t6 added the
  hardening test suites now run by that CI (§3/§9/§10/§18/§19 tests).
- **Rooted-emulator-with-working-Zygisk CI lanes and a device matrix — Open /
  Device-gated** (needs the §17 lab; the non-blocking emulator job exists but
  cannot exercise Zygisk specialization reliably — t4 showed Magisk×API version
  coupling).

## 17 — Build a real physical-device lab

**Status: N/A-here (procurement/racking) + Device-gated (its purpose).**

- Standing up physical hardware (OEM device matrix, rooted rigs, audio loopback)
  is **N/A-here** — it is hardware procurement and lab ops, not a coding-session
  deliverable. Everything the lab would *prove* (OEM HAL variance, live capture,
  enforcing-SELinux behavior) is the **Device-gated** column throughout this
  checklist. The reproducible on-device procedure the lab would run is written:
  [Verification §3](../verification.md#3-reproduce-on-a-real-device-the-device-gated-procedure).

## 18 — Observability without damaging the audio path

**Status: Landed-t6 (state model + F1 counter) + Open (F2 wire schema).**

- Telemetry is realtime-safe and cannot retain PCM: lock-free relaxed-atomic
  `recordBlock`/`recordInstall`, fixed `64·kCount`-byte footprint, frame *counts*
  only (never sample pointers). The model provably distinguishes
  installed / loaded / hooked / processing / mutating and enforces *hooked ≠
  mutated*, *block-count ≠ mutation-count*, *bypass ≠ failure*, *unchanged ≠
  mutated*. **Landed-t6 (t6-e4):**
  [`evidence-state-model.md`](evidence-state-model.md) + `telemetry_accumulator_test.cpp`
  Sections A–G.
- **F1 (install-failure vs block-failure conflation) — Landed-t6 (t6-e8):** new
  `install_failures` counter; `recordInstall(false)` no longer inflates the block
  `failures` counter; `StateFor` still surfaces install failure as `"error"` so
  wire `state` semantics are preserved; `sizeof(Accumulator)` unchanged.
  Evidence: `native/zygisk/src/utils/telemetry_accumulator.{h,cpp}`,
  `telemetry_accumulator_test.cpp` Section D + exporter test asserting no new wire
  keys.
- **F2 (exporter should emit `bypasses` / `installed` / `install_events`) —
  Open, deliberately.** The `type:"telemetry"` v2 frame is validated by a
  **strict exact-key-set validator** in `AuthenticatedTelemetry.kt` (a §13
  hardening control, `schemaVersion` pinned `2..2`); appending keys makes it
  reject *every* frame. Correct fix is a **coordinated schema-v3 evolution**
  (native `EncodeTelemetryV2` emits + the Kotlin validator accepts v3 + thread
  fields through frame/store/baseJson; app-side `TelemetryParser.kt` is already
  lenient). **Do not weaken the strict validator.** (t6-e8 finding;
  [`evidence-state-model.md` §7-F2](evidence-state-model.md#7-findings-reported-not-applied-in-this-track)).

## 19 — Define failure behavior precisely

**Status: Landed-t6.**

- Fail-**closed** on admission (unlisted/ revoked → never processed) and
  fail-**open** on app audio (any DSP failure/decline → original buffer
  preserved, never silence, never a partial mutation) are now regression-locked
  across AAudio (t6-e1), the non-AAudio routes (t6-e3), and the DSP boundary
  (t6-e2 "failure → original preserved"). **Landed-t6:** the ownership/fail-open
  tests in `aaudio_stream_registry_test.cpp`, the fail-open tests in
  `opensl_/tinyalsa_/capture_buffer_router_test.cpp`, and the non-finite/partial
  guards in `dsp_quality_test.cpp` + `stream_handle_registry_test.cpp`.
- **Panic / watchdog / auto-bypass behavior on a real device — Device-gated**
  (host logic exists — `todo.md` "Callback watchdog, xrun telemetry, auto-bypass,
  master bypass, timed panic"; the hardware panic-button combo listener is
  **Open**, `todo.md`).

## 20 — Product / operator UX

**Status: Pre-existing.**

- Companion app: dashboard with engine-status card + live meters, Preset Manager,
  Effects Editor (honest fixed-order chain), Diagnostics (per-API hook status,
  latency histogram, CPU heatmap, audio-pipeline visualization, compat probes
  with progress), Whitelist Editor (display names + search), Settings, QS tile,
  Compatibility Wizard. Honest "module not active" / idle states throughout; no
  faked telemetry. **Pre-existing** (t2 + the t5 UI batch: t5-e1..e13).
- Operator-facing **org process / support playbooks** beyond the in-app
  diagnostics export — **N/A-here** (process, not code).

## 21 — Release engineering & supply chain

**Status: Pre-existing + Open (a couple of named follow-ups).**

- Reproducible per-ABI native superbuild, deterministic Magisk zip bound
  byte-for-byte to trusted NDK outputs, fail-closed signing preflight + pinned
  non-debug cert verification + temporary-keystore cleanup, tag/dispatch-gated
  hosted release, docker CI images (native-build / android-build /
  magisk-packager / ci-local / emulator) with pinned tool versions.
  **Pre-existing** (t2-e13/e14/e15/e16/e20).
- **Open (named):** provision a real trusted Ed25519 plugin public key (the
  shipped all-zero placeholder correctly rejects every third-party plugin);
  enable R8/resource shrinking only after keep-rules are proven. Both are in
  `todo.md` "Non-release follow-ups".

## 22 — Maintainability & reviewability

**Status: Pre-existing + Landed-t6 (hardening docs).**

- Per-concern commit history, single canonical AIDL, unified module topology,
  format gate, MkDocs site (architecture, design-rationale, why-hard,
  build-install, verification, limitations, comparison). **Pre-existing**
  (t2/t3).
- t6 added three reviewable hardening records that tie tests to guarantees:
  this checklist, [`rt-safety.md`](rt-safety.md), and
  [`evidence-state-model.md`](evidence-state-model.md). **Landed-t6.**

## 23 — Concrete milestone sequence (M0–M5)

**Status: mixed — M0–M2 substantially done, M3–M5 Device-gated.**

| Milestone | Disposition | Basis |
| --- | --- | --- |
| **M0** green build, signed APK, per-ABI `.so`, flashable zip | **Pre-existing** | t2-e20 release-readiness PASS |
| **M1** legacy-preprocessor **proof** | **Device-gated** (packaging Pre-existing; on-device proof gated) | §7 above |
| **M2** safe AAudio callback | **Landed-t6** | §3 above (t6-e1) — the anchor deliverable |
| **M3** one evidenced ARM64 **on-device** success | **Device-gated** | Verification route table; t4 measured on x86_64 via harness, not a live arm64 app |
| **M4** broadened route coverage on device | **Device-gated** | §4/§5/§6 live halves |
| **M5** OEM hardware matrix (Qualcomm/MediaTek/Samsung HAL variance) | **Device-gated** (+ §17 N/A-here for the lab) | Verification §2 |

M3/M5 are explicitly **not** marked done — that is the report's central caution.

## 24 — Final "solid engineering" definition of done

**Status: partially met (host/artifact axis) — the on-device axis is honestly open.**

Against the report's own bar, t6 + prior work delivers: a green, reproducible,
signed, per-ABI, deterministically-packaged build; a fail-closed/fail-open data
plane with the P0 AAudio ownership bug fixed and RT-safety audited; DSP
correctness and telemetry-honesty test-locked; and a documented, non-overstated
evidence trail. What "done" still requires and this checklist refuses to fake:
**live capture-route transforms measured on real hardware across an OEM matrix
under enforcing SELinux** (M3–M5, §2, §4–§7, §14, §17). Those are Device-gated
with a written reproduction procedure, not claimed complete.

---

## Sources

Prior work: `.orchestration/state.md` (t2/t3/t4 outcomes), t4 on-device logs
(`.orchestration/logs/t4-e5.md`, `t4-e7.md`, `t4-e8.md`, `t4-e10.md`),
[Verification](../verification.md). t6 work: executor logs
`.orchestration/logs/t6-e{1,2,3,4,8}.md`; companion docs
[`rt-safety.md`](rt-safety.md) (t6-e3) and
[`evidence-state-model.md`](evidence-state-model.md) (t6-e4). No claim on this
page asserts on-device verification that was not actually performed.
