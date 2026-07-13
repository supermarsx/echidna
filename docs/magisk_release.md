# Magisk Module Packaging & Release

Echidna ships its on-device engine as a **single flashable Magisk/Zygisk module** (module id
`echidna`). `tools/build_magisk_module.sh` consumes the per-ABI NDK output and produces a real,
flashable zip. This aligns with the deployment flow in
[spec.md](https://github.com/supermarsx/echidna/blob/main/spec.md#7-installation--deployment-steps-developer--user-flows).

> **Requirement.** **Magisk 24.0+ with Zygisk enabled**, on Android 8.0 (API 26) or newer. The
> module enforces this: `module.prop` declares `minMagisk=24000`, and `customize.sh` hard-aborts on
> a Magisk version code below 24000 or an unsupported CPU architecture (only 32-bit x86 is
> unsupported — the module ships arm64-v8a, armeabi-v7a, x86_64).

> **Status.** Packaging is host-verified (layout, `bash -n`, script correctness, and Android ELF
> contents). Rooted Android 13/14 emulators prove the native `processBlock` path and one
> `AudioRecord.read` interception slice once the module libraries are reachable. Actual Magisk
> flashing remains unverified here: `magisk --install-module /sdcard/Download/echidna-magisk.zip`
> returned `Incomplete Magisk install` on the emulator images. Treat Magisk Manager install,
> reboot, LSPosed injection, and SELinux/socket bootstrap as release-device validation.

## What changed from the old packaging

- **Single module id.** The previous split — a trivial `id=echidna` module plus a separate
  `id=echidna-control` module under `android/control-service/magisk/` — is unified into one
  `id=echidna` module. The control-service SELinux/socket bootstrap scripts
  (`post-fs-data.sh`, `service.sh`) are reused into that single module; the old
  `echidna-control` `module.prop` is removed.
- **Per-ABI Zygisk payload.** The engine ships as `zygisk/<abi>.so` (Zygisk's required naming),
  one per ABI, instead of a single host-arch `zygisk/libechidna.so`.
- **Real per-ABI Android libraries.** The libs come from the NDK cross-compile, not host ELFs.
- **Flashable installer.** The zip now carries `META-INF/com/google/android/update-binary` +
  `updater-script` and a `customize.sh`, so it installs via Magisk Manager or recovery.

## Inputs

The packager consumes the per-ABI NDK output from `tools/build_native_ndk.sh`:

```
build/<abi>/lib/libechidna.so   → zygisk/<abi>.so      (Zygisk engine, per ABI)
build/<abi>/lib/libech_dsp.so   → libs/<abi>/...        (staged; placed by customize.sh)
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
post-fs-data.sh                 # runtime dir + socket/plugin dir bootstrap
service.sh                      # lib install, socket perms, SELinux relaxations
common/echidna_af_offsets.txt   # optional AudioFlinger offsets, if provided
```

At install, `customize.sh` maps Magisk's `$ARCH` to the build ABI, copies the matching
`libs/<abi>/libech_dsp.so` onto the linker search path (`system/lib64` on 64-bit, `system/lib` on
32-bit, plus the 32-bit companion DSP on 64-bit devices), stages `libechidna.so` into `$MODPATH/lib`
for the in-app control-service JNI (`dlopen` from `/data/adb/modules/echidna/lib`), removes the
`libs/` staging, and sets permissions.

## Runtime bootstrap (SELinux + shared runtime files)

- **`post-fs-data.sh`** creates `/data/adb/echidna/{lib,run}`, mirrors `libechidna.so` to the JNI
  search path, and prepares `/data/local/tmp/echidna` (plugin dir plus shared config/telemetry
  region files — `/dev/shm` does not exist on stock Android, so these live under `/data`). It
  applies `chcon` contexts to the runtime dirs.
- **`service.sh`** installs the engine into `/data/adb/echidna/lib`, stages optional AudioFlinger
  offsets and applies SELinux relaxations via `magiskpolicy --live` (zygote dyntransition / binder).
  If enforcement cannot be adjusted, the module logs and the app falls back to Java-only mode.

Profile-sync itself is owned by the companion service's abstract AF_UNIX socket
`echidna_profiles`, so there is no filesystem socket endpoint for the module scripts to create.
See [developer_readme.md](developer_readme.md#known-limitations).

## Script usage

From the repo root, after the native build:

```sh
tools/build_magisk_module.sh
# → out/echidna-magisk.zip
```

Environment overrides:

- `ECHIDNA_ABIS` (default `arm64-v8a armeabi-v7a x86_64`)
- `ECHIDNA_VERSION` (default `0.0.0`) / `ECHIDNA_VERSION_CODE` (default: digits of version, else 1)
- `ECHIDNA_BUILD_ROOT` (default `<repo>/build`), `ECHIDNA_OUT_DIR`, `ECHIDNA_ZIP_PATH`
- `ECHIDNA_AF_OFFSETS` (optional AudioFlinger offsets file to bundle)

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
| `echidna-native-libs-<tag>.zip` | Raw `libechidna.so` + `libech_dsp.so` for every ABI. |
| `echidna-apks-<tag>.zip` | Companion APK and LSPosed shim APK together. |
| `echidna-complete-<tag>.zip` | All release assets above plus `RELEASE_ARTIFACTS.md`. |
| `SHA256SUMS.txt` | SHA-256 checksums for all published release files. |
| `RELEASE_ARTIFACTS.md` | Short install/asset manifest generated by the release job. |

Manual dispatch behavior:

- Leave `tag` empty to compute the next tag for the current UTC year by scanning existing tags.
- Provide an explicit tag only when it matches `YY.N`; invalid tags such as `v26.1`, `2026.1`,
  `26.0`, or `26.01` are rejected before build jobs start.
- Existing tags can be released manually only when no GitHub Release already exists for that tag.
- If release signing secrets are absent, the APK builds keep the documented debug-signing fallback
  and produce non-distributable release APKs.

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
- **On a rooted device (Magisk 24.0+):** enable Zygisk, flash `out/echidna-magisk.zip`, reboot,
  install the companion app, and validate hooks + SELinux/HAL via the compatibility wizard and
  diagnostics view.
- Avoid scoping the same target app into both Zygisk and LSPosed unless the release test is
  explicitly validating duplicate-hook behavior.
