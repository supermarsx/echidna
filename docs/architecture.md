# Architecture

This page describes the **real, end-to-end architecture** of Echidna as it is
built in this repository — the companion app, the in-app control service, the
JNI bridge, the Zygisk native module, the DSP engine, and the LSPosed
compatibility shim. Where a behaviour can only be exercised on a rooted device
it is marked **device-gated**; those paths are source-complete and build-verified
here, but their on-device runtime behaviour has not been observed in this
environment.

## Component overview

Echidna ships as a **single installable APK** (the companion app) plus a
**flashable Magisk module** that carries the native libraries. There is no
separate `com.echidna.control` package — the control service is hosted *inside*
the companion app process.

```mermaid
flowchart TB
    subgraph APK["Companion APK — com.echidna.app (single installable)"]
        UI["Jetpack Compose UI<br/>Dashboard · Presets · Effects · Diagnostics<br/>Compatibility · Whitelist · Settings · QS Tile"]
        REPO["ControlStateRepository<br/>(StateFlow singleton, persists presets)"]
        CLIENT["ControlServiceClient<br/>binds by ComponentName(this, EchidnaControlService)"]
        SVC["EchidnaControlService<br/>(bound Service, exported=false)"]
        JNI["echidna_control_jni<br/>(:service native lib, packaged in APK)"]
        UI --> REPO --> CLIENT
        CLIENT -->|"AIDL: IEchidnaControlService"| SVC
        SVC --> JNI
    end

    subgraph MAGISK["Magisk module — id: echidna (flashable, per-ABI)"]
        ZY["libechidna.so<br/>Zygisk module"]
        DSP["libech_dsp.so<br/>C++ DSP engine"]
        ZY -->|"dlopen + dlsym"| DSP
    end

    subgraph TARGET["Target / media app processes (Discord, Telegram, …)"]
        HOOKS["Audio hook chain<br/>AAudio · OpenSL · AudioFlinger · AudioRecord<br/>libc read · tinyalsa · Audio HAL"]
        SHIM["LSPosed Java shim<br/>(AudioRecord.read, fail-closed)"]
    end

    JNI -.->|"dlopen /data/adb/modules/echidna/lib/libechidna.so"| ZY
    SVC -->|"ProfileSyncBridge: memfd region + AF_UNIX socket"| SNAP[("Profile/whitelist/control snapshot<br/>{profiles, whitelist, appBindings, control}")]
    SNAP -->|"framed JSON [len][UTF-8]"| ZY
    SNAP -->|"framed JSON [len][UTF-8]"| SHIM
    ZY --> HOOKS
    HOOKS -->|"echidna_process_block()"| DSP
    SHIM -->|"JNI buffer handoff"| DSP
```

### The five runtime pieces

| Component | Artifact | Runs in | Role |
| --- | --- | --- | --- |
| **Companion app + UI** | `app-debug.apk` / `app-release.apk` | its own process (`com.echidna.app`) | Compose UI over a `ControlStateRepository` StateFlow singleton; binds the control service; persists presets. |
| **Control service** | `EchidnaControlService` (in the same APK) | companion app process | Bound `Service` implementing the unified AIDL; owns profile/whitelist/control state; publishes the profile-sync snapshot. |
| **JNI bridge** | `echidna_control_jni` (`.so` in the APK) | companion app process | App-side native glue; `dlopen`s the Magisk-delivered `libechidna.so` for status/control. |
| **Zygisk module** | `libechidna.so` (Magisk `zygisk/<abi>.so`) | every specialized app process | Registered Zygisk module; installs the audio hook chain and routes captured PCM through the DSP. |
| **DSP engine** | `libech_dsp.so` (Magisk `system/lib(64)`) | whichever process loaded the hooks | Real-time C++ effect chain; exposes the `echidna_process_block` C ABI. |

## Control plane: app to service to native

The control plane was **repackaged into a single-APK topology** (t2-e6). The
older design bound to a phantom `com.echidna.control` package that had no
installable host; that has been removed.

- `ControlServiceClient` binds with
  `ComponentName(context, EchidnaControlService::class.java)` — an **in-package**
  bind, so it resolves at runtime. The service is declared `exported="false"`.
- The companion app's Gradle build folds the `:service` module in directly
  (`include(":service")` with a `projectDir` redirect), so the service, the
  **single canonical AIDL**, and the `echidna_control_jni` native library are all
  bundled into the one APK. The duplicate app-side AIDL copy was deleted, so
  there is exactly one `IEchidnaControlService` contract.
- The AIDL surface (`IEchidnaControlService`) carries the full control API:
  status/refresh (`getModuleStatus`, `refreshStatus():String`), whitelist and
  binding queries (`getWhitelistBindings`), global controls
  (`setMasterEnabled`, `setBypass`, `triggerPanic`, `setSidetone`,
  `getControlState`), plus profile push, telemetry streaming
  (`RemoteCallbackList`), and `processBlock`.
- `getModuleStatus`/`refreshStatus` return a **combined status JSON** assembled
  from the real module status, a human-readable SELinux state, and a live
  `AudioStackProbe` (manufacturer, `ro.board.platform`, AAudio / low-latency /
  pro-audio features, output sample rate and frames-per-buffer). This replaced
  the previously hard-coded "Qualcomm QSSI / Enforcing" placeholder data.

## Data plane: audio capture to DSP

When the Zygisk module attaches inside a target process it installs a **layered
hook chain**. Each manager, when it captures a PCM block, calls
`echidna_process_block(...)`, which lazily `dlopen`s `libech_dsp.so`, resolves the
four DSP entrypoints, and calls `dsp.process(...)`, then writes the processed
PCM back in place. All hooking is gated on `hooksEnabled()` **and**
`isProcessWhitelisted()` — the module never hooks unconditionally.

**Hook install order** (from the orchestrator, highest priority first):

```mermaid
flowchart LR
    A["AAudio<br/>(API ≥ 26)"] --> B["OpenSL ES"] --> C["AudioFlinger"] --> D["AudioRecord<br/>native bridge"] --> E["libc read<br/>(/dev/snd fallback)"] --> F["tinyalsa"] --> G["Audio HAL"]
    G --> P["echidna_process_block()"]
    P --> H["dlopen libech_dsp.so → dsp.process()"]
```

The first manager that installs successfully flips internal status to `kHooked`,
which the control service surfaces via `getModuleStatus`. The chain is a
priority list, not mutually exclusive — several managers can be active if an app
uses multiple audio paths.

### Zygisk module lifecycle

`libechidna.so` is a genuine Zygisk module (t2-e9). It registers via
`REGISTER_ZYGISK_MODULE(EchidnaModule)` against the Zygisk API v4 header
(`native/zygisk/include/zygisk.hpp`):

```mermaid
sequenceDiagram
    participant Z as Zygisk loader (Magisk)
    participant M as EchidnaModule
    participant O as AudioHookOrchestrator
    participant D as libech_dsp.so
    Z->>M: onLoad(Api*, JNIEnv*)
    Note over M: stash handles; do NOT set<br/>DLCLOSE_MODULE_LIBRARY (stay mapped)
    Z->>M: preAppSpecialize(args)
    Note over M: no-op (still zygote identity)
    Z->>M: postAppSpecialize(args)
    M->>M: echidna_module_attach()
    M->>O: create ProfileSyncServer + orchestrator (once)
    O->>O: installHooks() — gated on hooksEnabled() && whitelisted
    Note over O,D: on captured PCM → echidna_process_block → dsp.process()
```

`postAppSpecialize` is the attach point because by then `/proc/self/cmdline`
reflects the **target app's** process name, which the whitelist check reads. The
module deliberately keeps itself mapped so the installed inline/PLT hooks persist
for the process lifetime. **Device-gated:** the actual Zygisk load, lifecycle
firing, and hook installation require a rooted device with Magisk + Zygisk; they
are source-complete and the `.so` links for all ABIs, but on-device execution has
not been observed here.

### Multi-ABI hooking

The native libraries are cross-compiled **per ABI** (t2-e10) — `arm64-v8a`
(primary), `armeabi-v7a`, `x86_64` — into `build/<abi>/lib/`. The inline-hook
trampoline support differs by ABI (t2-e11):

- **arm64-v8a** — full trampoline (LDR X16 / BR X16 with relocation fixups); the
  primary, most-tested path.
- **x86_64** — full trampoline implemented (14-byte absolute `jmp [rip]` patch
  with an allow-listed length decoder that relocates RIP-relative and rel32
  operands, failing closed on anything unrecognized). Verified with a host
  decoder + end-to-end hook harness; **on-emulator confirmation is device-gated.**
- **armeabi-v7a** — **graceful degrade**: it builds and loads, but `install()`
  returns `false` and emits a `hook_unsupported_abi` log signal, because Thumb-2 /
  IT-block relocation is unsafe and untested. armv7 hooking is intentionally
  non-functional rather than silently wrong.

## Profile / telemetry sync: the shared region + socket

The control service publishes profile, whitelist, and control state to the
native side (and to the LSPosed shim) through **`ProfileSyncBridge`**: a
memfd-backed shared region plus an `AF_UNIX` socket.

- The snapshot JSON is `{profiles, whitelist, appBindings, control}`. `control`
  is additive (`masterEnabled`, `bypass`, `panicUntilEpochMs`, `sidetone`);
  readers that predate it still work.
- Wire framing is `[4-byte big-endian length][UTF-8 JSON]`. The receiver can
  also read the framed snapshot from a shared **fd handed over the socket via
  `SCM_RIGHTS`** (ancillary data) as a fallback.
- The socket path is `/data/local/tmp/echidna_profiles.sock`, bound
  unlink-then-bind ("last writer wins").

### Why memfd (not `shm_open` or `ASharedMemory`)

The shared regions are backed by an **anonymous `memfd`**
(`memfd_create` via the raw `syscall(__NR_memfd_create, …)`) plus `ftruncate` +
`mmap` (t2-e23). Two constraints forced this:

1. **bionic has no `shm_open` / `shm_unlink`** — the POSIX SysV/shm API the code
   originally used simply does not exist on Android.
2. **The Zygisk target links only `liblog`, not `libandroid`** — so
   `ASharedMemory_create` (the usual Android replacement) fails at *link* time
   with an undefined symbol. `memfd_create` is a plain libc syscall, needs no
   extra link, and yields the same thing: a real fd + fixed size that is `mmap`'d
   locally and whose fd can be passed over the socket.

A header-only, process-global, **name-keyed refcounted registry**
(`android_shared_memory.h`) preserves the old `shm_open("/name")` semantic so
that the writer (`ProfileSyncServer::handlePayload`) and reader (`SharedState`)
map the *same* region.

## LSPosed shim path (Java-API apps)

For apps that capture through Java `AudioRecord`, the LSPosed shim provides a
fallback that does **not** depend on the Zygisk module being active. It resolves
per-app policy by reading the **same ProfileSyncBridge snapshot** (t2-e8):

- `ProfileSyncReceiver` binds the identical `AF_UNIX` socket and reads the
  identical framing (and the `SCM_RIGHTS` fd fallback) — it mirrors the native
  receiver rather than inventing a new wire format.
- `ProfileSnapshot` parses `{profiles, whitelist, appBindings, control}` and
  exposes `isProcessAllowed(pkg, proc)`, `resolveProfile(pkg)`,
  `isGloballyEnabled()`, and `engineMode()`.
- **Fail-closed by construction:** the default snapshot is `empty()` (empty
  whitelist, global off). Before any push, or on any unreadable/unparseable
  snapshot, resolution denies hooking. Hooking is enabled **only** when globally
  enabled *and* the package/process is explicitly whitelisted `true`. When a
  buffer is not allowed, `AudioRecordHook` zeroes the returned buffer and forces
  `read()` to return 0.

### Known limitation — single-holder socket

The profile-sync socket is **single-holder** (inherited native contract:
unlink-then-bind, started per app process). Consequently: (a) the Zygisk module
and the LSPosed shim should **not** run on the same device simultaneously — they
contend for the socket; and (b) with multiple hooked apps, only the *last* binder
receives pushes, so others stay fail-closed until the next mutation. A proper fix
(serve-last-snapshot to connecting readers, or a world-readable published
snapshot) is a native/service redesign and is out of scope for the current
implementation. See [Limitations](limitations.md).

## Threading and latency

- The DSP runs **synchronously inside the capture callback** by default
  (in-place processing), which is the low-latency path. A **hybrid** mode copies
  into a lock-free ring buffer and lets a worker apply heavier transforms, with
  an overrun watchdog and xrun counting; this trades latency for quality. Latency
  modes are exposed per preset (Low-Latency / Balanced / High-Quality). See
  [DSP & Effects](dsp-effects.md).
- The snapshot is pushed only on mutation and at service startup
  (`loadFromDisk`), so a process that binds after the last push stays fail-closed
  until the next mutation (freshness caveat).

## What is verified vs device-gated

- **Verified in this environment:** the single-APK topology and AIDL unification
  build and the debug/release APKs assemble; all six per-ABI `.so` cross-compile
  and link (arm64-v8a / armeabi-v7a / x86_64, DSP + Zygisk); host DSP tests pass;
  the x86_64 trampoline passes a host end-to-end hook harness; the app installs,
  launches crash-free, and navigates on an unrooted emulator.
- **Device-gated (source-complete, not observed here):** live Zygisk load and
  lifecycle firing, real hook installation inside target processes, the LSPosed
  injected-process path, Audio HAL / SELinux interaction, and the on-device
  socket/`SCM_RIGHTS` fd hand-off. See [Verification](verification.md) for the
  full matrix and a reproduce-on-device procedure.
