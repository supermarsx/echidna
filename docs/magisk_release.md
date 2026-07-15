# Magisk Module Packaging & Release

!!! danger "⚠️ Manual recovery knowledge required"
    Echidna is experimental root software and may be incompatible with the
    device you are using. Do not flash the Echidna Magisk/Zygisk module unless
    you already know how to disable a module manually from recovery, adb, safe
    mode, or another out-of-band rescue path if the phone bootloops. If you
    cannot recover from a bad module without the normal Android UI, do not
    install this module.

Echidna ships its on-device engine as a **single flashable Magisk/Zygisk module** (module id
`echidna`). `tools/build_magisk_module.sh` consumes the per-ABI NDK output and produces a real,
flashable zip. This aligns with the deployment flow in
[spec.md](https://github.com/supermarsx/echidna/blob/main/spec.md#7-installation--deployment-steps-developer--user-flows).

> **Requirement.** **Magisk 24.0+ with Zygisk enabled**, on Android 8.0 (API 26) or newer. The
> module enforces this: `module.prop` declares `minMagisk=24000`, and `customize.sh` hard-aborts on
> a Magisk version code below 24000 or an unsupported CPU architecture (only 32-bit x86 is
> unsupported — the module ships arm64-v8a, armeabi-v7a, x86_64).

> **Status.** Packaging is host-verified (layout, `bash -n`, script correctness, and Android ELF
> contents). Rooted Android 13/14 emulators prove the native `processBlock` path. A native
> `AudioRecord.read` interception slice passed before the current explicit-contract route redesign
> and is historical evidence only. Actual Magisk
> flashing remains unverified here: `magisk --install-module /sdcard/Download/echidna-magisk.zip`
> returned `Incomplete Magisk install` on the emulator images. Treat Magisk Manager install,
> reboot, LSPosed injection, current capture routes, and SELinux behavior as device validation.

## Failsafe and recovery contract

⚠️ Echidna's module must be treated as boot-sensitive. A release should never assume the normal
Android UI will remain available after flashing. Users and release testers must know how to
disable the module before installing it.

Intended disable paths:

- **Magisk module disable file:** create Magisk's disable marker for the `echidna` module,
  normally `/data/adb/modules/echidna/disable`, from recovery or adb when `/data` is mounted.
- **Echidna runtime disable marker:** create `/data/adb/echidna/disable` to keep Echidna inactive
  even if the module files remain installed.
- **Safe-mode path:** use the project's safe-mode path when available so Echidna starts disabled
  and the companion app can report the reason instead of activating hooks.
- **Early recovery markers:** create `/cache/echidna-disable` or `/metadata/echidna-disable` when
  `/data` is unavailable, encrypted, or unsafe to modify from recovery.
- **Automatic boot watchdog:** the watchdog is intended to disable Echidna after repeated boots
  that do not reach the late-start service. It is a last-resort guard, not a replacement for
  manual recovery knowledge.
- **Trust re-enrolment:** inspect `/data/adb/echidna/trust/status.txt`. For an intentional companion
  signer or capability-key replacement, disable the module first, remove the pending/active pin only
  while disabled, reinstall the trusted app/module pair, then reboot. Never replace a pin under a
  live audio service.

The install path should be loud about compatibility before reboot. Hard requirements should abort
the install where they are known locally, while device-compatibility signals should alert without
pretending to prove success. The checks to surface include:

- Android API and Magisk version.
- Zygisk availability and whether the user has enabled it.
- Primary CPU ABI, supported ABI list, unsupported `x86`, and native-hook support for the
  selected ABI.
- Presence of the matching Zygisk payload and `libech_dsp.so` for the selected ABI.
- Incomplete install or bridge state, including missing `/data/adb/echidna` runtime directories,
  missing JNI search-path libraries, or stale shared telemetry/config files.
- SELinux enforcing state and whether the narrow config/telemetry policy steps succeeded.
- Vendor audio family and common native audio libraries (`libOpenSLES.so`, `libaudioclient.so`,
  `libtinyalsa.so`) used only as compatibility signals. Their presence does not prove capture.
- Existing audio/root modules that are known or suspected to conflict.
- Duplicate Zygisk plus LSPosed scope for the same target app.

These checks are alerts and guards. They do not make Echidna safe for ordinary Android users, and
they do not remove the user's responsibility for having a bootloop recovery path.

## What changed from the old packaging

- **Single module id.** The previous split — a trivial `id=echidna` module plus a separate
  `id=echidna-control` module under `android/control-service/magisk/` — is unified into one
  `id=echidna` module. The control-service runtime-policy/watchdog scripts
  (`post-fs-data.sh`, `service.sh`) are reused into that single module; the old
  `echidna-control` `module.prop` is removed.
- **Per-ABI Zygisk payload.** The engine ships as `zygisk/<abi>.so` (Zygisk's required naming),
  one per ABI, instead of a single host-arch `zygisk/libechidna.so`.
- **Real per-ABI Android libraries.** The libs come from the NDK cross-compile, not host ELFs.
- **Flashable installer.** The zip now carries `META-INF/com/google/android/update-binary` +
  `updater-script` and a `customize.sh`, so it installs via Magisk Manager or recovery.

## Inputs

The native superbuild produces four libraries for each of three ABIs. The Magisk packager consumes
engine/DSP pairs plus inert preprocessor staging, while `libechidna_shim_jni.so` is reserved for the
LSPosed APK. Preprocessor registration remains default-off and device-generated:

```
build/<abi>/lib/libechidna.so   → zygisk/<abi>.so      (Zygisk engine, per ABI)
build/<abi>/lib/libech_dsp.so   → libs/<abi>/...        (staged; placed by customize.sh)
build/<abi>/lib/libechidna_shim_jni.so                 (LSPosed APK input; not in module)
build/<abi>/lib/libechidna_preproc.so → preproc/<abi>/  (inert next-boot source)
```

Build those first (see [developer_readme.md](developer_readme.md#native-per-abi-build-ndk)):

```sh
ANDROID_NDK=/path/to/android-ndk ANDROID_PLATFORM=android-26 \
  bash tools/build_native_ndk.sh
```

The script fails loudly if any required per-ABI `libechidna.so` / `libech_dsp.so` is missing.

## Zip layout

```
zygisk/arm64-v8a.so             # libechidna.so per ABI (Magisk loads the one matching each process)
zygisk/armeabi-v7a.so
zygisk/x86_64.so
libs/<abi>/libech_dsp.so        # staged DSP libs; customize.sh places the right arch into system/lib(64)
lib/libechidna.so               # (populated at install) primary-ABI engine for the in-app JNI
META-INF/com/google/android/update-binary
META-INF/com/google/android/updater-script
customize.sh                    # install-time ABI mapping + placement
module.prop                     # id=echidna, version templated at package time
post-fs-data.sh                 # watchdog + region creation, labels, and narrow policy
service.sh                      # watchdog clear + runtime library staging
common/zygisk-status.sh         # shared, read-only Zygisk state probe
common/echidna-trust-helper.jar # module-owned app_process Dex helper (classes.dex)
common/release-cert-sha256      # normalized exact companion release signer pin
common/trust-mode               # production, or an explicit non-production development marker
common/trust-bootstrap.sh       # late signer/UID/dataDir/SPKI verifier and next-boot pinning
sepolicy.rule                   # narrow config/telemetry region types and grants
```

At install, `customize.sh` maps Magisk's `$ARCH` to the build ABI, copies the matching
`libs/<abi>/libech_dsp.so` onto the linker search path (`system/lib64` on 64-bit, `system/lib` on
32-bit, plus the 32-bit companion DSP on 64-bit devices), stages `libechidna.so` into `$MODPATH/lib`
for the in-app control-service JNI (`dlopen` from `/data/adb/modules/echidna/lib`), removes the
`libs/` staging, and sets permissions.

## Runtime bootstrap (narrow policy + shared runtime files)

- **`post-fs-data.sh`** arms the boot watchdog, creates `/data/adb/echidna/{lib,run}`, mirrors
  `libechidna.so` to the JNI search path, and prepares `/data/local/tmp/echidna` (plugin directory
  plus pre-sized config/telemetry region files). It applies dedicated region types and narrow app
  read/write grants matching `sepolicy.rule`. It does not grant zygote transitions or binder access.
- **`service.sh`** marks late-start as reached, clears the watchdog, removes transient effect-config
  backing left after Magisk's mount phase, recreates safe runtime permissions, stages
  `libechidna.so`, and invokes trust then inert effect staging nonfatally. Failure leaves the legacy
  preprocessor unregistered or identity-bypassed; it never blocks boot, widens SELinux, replaces a
  live key, or restarts audioserver.

The helper supports API 26–33 only. It verifies the current PackageManager signer, user-0
UID/dataDir, and app-owned P-256 SPKI before staging a root-owned read-only copy under
`trust/next-boot/`. After effect eligibility and the registry merge succeed, the registration helper
copies the same verified key to the module overlay for
`/system/etc/echidna/preprocessor_controller_p256.spki`; both registry and key are next-boot inputs.

## Default-off legacy effect registration

`common/effect-registration.sh` does not use the SDK level as an eligibility shortcut. It requires
registered-service PID evidence from `lshal list -ip` for a legacy HIDL
`IEffectsFactory/default`. It rejects Stable-AIDL-only factories, HIDL+AIDL discovery, and multiple
registered factory PIDs. The selected PID is tied to a stable `/proc/<pid>/stat` start time, a
verified `/proc/<pid>/exe` target and executable map, and matching ELF class/machine. This supports
generic and vendor-cohosted audio-service names without trusting process command-line text. Android
14 is eligible when it still registers that legacy HIDL factory. A Stable-AIDL-only Android 14
device is not.

The config search follows the platform order: ODM, API 30+ vendor-SKU, vendor, then system XML;
legacy vendor/system `.conf` is used only when no XML is readable. An active ODM config is rejected
without falling through because Magisk documents vendor overlays through `system/vendor` but no
equivalent supported ODM module path. The selected system/vendor source is merged semantically:

- the exact `echidna_preproc` library and implementation UUID are inserted once;
- the type UUID remains library-descriptor evidence, as required by the legacy effect ABI;
- every existing vendor registry/processing entry is retained;
- malformed, duplicate, conflicting, or ambiguous input is rejected;
- processing instructions, CDATA, entity references, and other unsupported DOM nodes are rejected
  instead of being silently dropped;
- no XML `preprocess` or legacy `pre_processing` application is created.

The module-owned Dex helper validates root ownership/no-follow reads, strict UTF-8, ELF class and
machine, the AELI marker, and P-256 SPKI. It atomically stages the generated registry outside the
auto-mounted tree under `registration/next-boot/config/`, plus the matching `lib(64)/soundfx`
library and exact system key, before committing `state-v2` metadata. Metadata records
source/overlay/library/key SHA-256 values, exact inert/transient paths, source path, format,
partition, ABI/bitness, build fingerprint, both UUIDs, and `auto_apply=false`. Repeated staging must
match every tracked output.

Magisk documents module `post-fs-data.sh` as blocking and running before module files are mounted.
Echidna uses that ordering as the activation boundary: it first removes all stale or interrupted
config backing, then validates current fingerprint, stock source hash, metadata ownership/mode, and
the config/library/key paths and hashes. Only a fully verified registry is copied to the exact
transient `system`/`system/vendor` module path for Magisk's subsequent mount. Any mismatch or copy
failure removes both partial and transient files, so the stock config remains active for that boot.
The late service removes the transient backing after the mount phase and may prepare only inert
state for another boot. A mounted library or key is inert without registry registration. Fingerprint,
source, or tracked-output drift creates `registration/restage-required`; it does not disable the
whole module, and early activation remains off until an explicit reinstall/restage. Legacy v1 state
also requires restaging.

The release ZIP itself contains no generated active or inert `audio_effects.xml`/
`audio_effects.conf`, or controller SPKI. `tools/verify_magisk_module.py` checks all three
preprocessor ELFs for ABI, SONAME, AELI export, exact DT_NEEDED set, archive modes/layout, early
activation ordering, exact registration constants, and the absence of command-line host discovery,
auto-apply, or hot audioserver restart tokens.

This is still device-gated. The repository has not yet proved a real device's active HIDL host and
config, post-fs/mount ordering, magic-mount file labels/linker namespace, factory descriptor,
process maps, or AVC-free load. Host fixtures cover activation rollback and first-OTA refusal.
Registration alone never attaches or enables the effect; only the separate default-off LSPosed
session manager can request authorized attachment, and no device audio transformation is proved.

Native Zygisk policy is owned by the companion service's authenticated abstract AF_UNIX socket
`echidna_profiles`; LSPosed uses the companion's read-only Binder provider. There is no filesystem
policy socket endpoint for module scripts to create.
See [developer_readme.md](developer_readme.md#known-limitations).

## Script usage

From the repo root, after the native build, build the Dex helper and provide the normalized signer
pin:

```sh
bash tools/build_trust_helper.sh
RELEASE_CERT_SHA256=<64-hex-release-cert-digest> tools/build_magisk_module.sh
# → out/echidna-magisk.zip
```

Environment overrides:

- `ECHIDNA_ABIS` (default `arm64-v8a armeabi-v7a x86_64`)
- `ECHIDNA_VERSION` (default `0.0.0`) / `ECHIDNA_VERSION_CODE` (default: digits of version, else 1)
- `ECHIDNA_BUILD_ROOT` (default `<repo>/build`), `ECHIDNA_OUT_DIR`, `ECHIDNA_ZIP_PATH`
- `RELEASE_CERT_SHA256` (required exact companion signer pin)
- `ECHIDNA_TRUST_HELPER_JAR` (default `build/trust-helper/echidna-trust-helper.jar`)
- `ECHIDNA_TRUST_MODE` (`production` by default; explicit `development` is not releasable)

The `echidna/magisk-packager` docker image runs this step in a pinned environment (see
[developer_readme.md](developer_readme.md#docker-helper-images)).

## GitHub release workflow

`.github/workflows/ci.yml` calls `.github/workflows/release.yml` after the normal CI gates pass on
each push to `main`. The release workflow scans existing release tags, creates the next `YY.N` tag
on the pushed commit, builds the release assets, and publishes a GitHub Release. Pushed release tags
and manual `workflow_dispatch` releases remain supported. Release tags use `YY.N` format, such as
`26.1` and `26.2` in 2026.

Each GitHub Release now gets a part-by-part asset set:

| Asset | Contents |
| ----- | -------- |
| `echidna-companion-<tag>.apk` | Companion app plus in-process control service. |
| `echidna-lsposed-shim-<tag>.apk` | Installable LSPosed/Xposed Java fallback shim. |
| `echidna-magisk-<tag>.zip` | Flashable Magisk/Zygisk module. |
| `echidna-native-libs-<tag>.zip` | Raw engine, DSP, and dedicated shim JNI library for every ABI. |
| `echidna-apks-<tag>.zip` | Companion APK and LSPosed shim APK together. |
| `echidna-complete-<tag>.zip` | All release assets above plus `RELEASE_ARTIFACTS.md`. |
| `SHA256SUMS.txt` | SHA-256 checksums for all published release files. |
| `RELEASE_ARTIFACTS.md` | Short install/asset manifest generated by the release job. |

Use the newest GitHub Release for normal installs. Do not flash an earlier release unless you are
intentionally rolling back or already understand the recovery procedure for a broken Magisk/Zygisk
module. Older release zips may contain boot or module bugs that newer releases fixed, so recovery
can be harder than with the current build.

Manual dispatch behavior:

- Leave `tag` empty to compute the next tag for the current UTC year by scanning existing tags.
- Provide an explicit tag only when it matches `YY.N`; invalid tags such as `v26.1`, `2026.1`,
  `26.0`, or `26.01` are rejected before build jobs start.
- Existing tags can be released manually only when no GitHub Release already exists for that tag.
- Every release entry point requires a complete keystore, private-key alias/password, and expected
  certificate SHA-256. Missing, partial, malformed, or mismatched signing inputs fail before
  publication; hosted releases never publish Gradle's local debug-signing fallback.

Automatic push behavior:

- Every accepted push to `main` becomes a release candidate and publishes only after the normal CI
  gates plus the native, Magisk, and Android release jobs complete.
- The auto tag is created from the latest existing tag in the current UTC year. For example, if
  `26.3` is the latest 2026 release tag, the next `main` push publishes `26.4`.
- Auto-tagging is serialized by workflow concurrency so consecutive pushes compute tags in order.

## Release checklist

- Cross-compile the per-ABI native libs (`tools/build_native_ndk.sh`) for the full ABI list.
- Run the native/DSP tests and hook smoke checks.
- Package the module with `tools/build_magisk_module.sh` (set `ECHIDNA_VERSION`).
- Build the release-signed companion and LSPosed APKs (`assembleRelease` with a real keystore — see
  [signing.md](signing.md)).
- Verify signer continuity. A previously debug-signed APK cannot be upgraded in place to a release
  certificate; back up needed data and perform a one-time uninstall when migrating signers.
- **On a rooted device (Magisk 24.0+):** enable Zygisk, flash `out/echidna-magisk.zip`, reboot,
  install the companion app, and validate supported capture candidates plus SELinux behavior via
  the compatibility wizard and diagnostics view. Audio HAL/AudioFlinger remain unsupported.
- Avoid scoping the same target app into both Zygisk and LSPosed unless the release test is
  explicitly validating duplicate-hook behavior.
