# Echidna dockerized build helpers

Reproducible, pinned container images that build the Echidna artifacts the plain
CI runners struggle to produce (per-ABI native `.so`, a flashable Magisk module,
and a deterministic debug APK). Each image wraps the **real repo build scripts**
— it does not reimplement them — so the container output matches a local build
with the same toolchain.

> All commands below are run from the **repo root** unless noted.

## Images at a glance

| Image | Base (pinned) | Produces | Runs |
|---|---|---|---|
| `echidna/native-build` | `eclipse-temurin:17.0.13_11-jdk-jammy` + NDK r27 (`27.0.12077973`) + CMake 3.30.5 / Ninja 1.13.0 | `build/<abi>/lib/{libech_dsp,libechidna}.so` for arm64-v8a, armeabi-v7a, x86_64 | `tools/build_native_ndk.sh` |
| `echidna/android-build` | `eclipse-temurin:17.0.13_11-jdk-jammy` + SDK platform-34 / build-tools 34.0.0 + Gradle 8.5 | `app-debug.apk` (and AARs on request) | `gradle --project-dir android/app assembleDebug` |
| `echidna/magisk-packager` | `alpine:3.20.3` + bash + zip | `out/echidna-magisk.zip` (flashable) | `tools/build_magisk_module.sh` |
| `echidna/ci-local` | `docker:27.3.1-cli` | orchestrates 1 → 2 → 3 | `docker/ci-local/run-pipeline.sh` |
| `echidna/emulator` *(optional)* | `eclipse-temurin:17.0.13_11-jdk-jammy` + emulator + `system-images;android-34;google_apis;x86_64` | booted AVD for instrumentation/E2E | headless `emulator` |

The **repo is bind-mounted at `/workspace`** at run time (never copied into the
image), so editing source never requires an image rebuild.

## Pinned versions (single source of truth)

Everything is pinned via `ARG`s at the top of each Dockerfile and matches the
values the repo/CI already use:

- **AGP 8.2.2 / Kotlin 1.9.22 / Gradle 8.5** — from `android/app/build.gradle.kts` and `.github/workflows/ci.yml`.
- **JDK 17 (Temurin)** — matches `compileOptions`/`kotlinOptions.jvmTarget = "17"`.
- **compileSdk / targetSdk 34, build-tools 34.0.0, minSdk 26** — from `android/app/app/build.gradle.kts`.
- **NDK r27 = `27.0.12077973`** — the exact revision t2-e10/e24 verified on the host.
- **`ANDROID_PLATFORM=android-26`** — the min-API the native build targets (t2-e10).
- **BoringSSL pin `0.20240913.0`** — resolved by `native/dsp/CMakeLists.txt` FetchContent (Android-only). Nothing is vendored; the tag is overridable via `ECHIDNA_CMAKE_EXTRA_ARGS`.
- **cmdline-tools `11076708`, CMake 3.30.5, Ninja 1.13.0, Alpine 3.20.3, docker-cli 27.3.1.**

### Integrity of downloaded archives

Downloads are checksum-verified in the Dockerfile:

- **Gradle 8.5 `-bin`** — `GRADLE_SHA256` (published at <https://gradle.org/release-checksums/>).
- **NDK, SDK platform/build-tools** — fetched via `sdkmanager`, which validates every package against Google's signed repository manifest (no manual checksum needed).
- **Android cmdline-tools bootstrap zip** — `CMDLINE_TOOLS_SHA256`. **Confirm this against Google's published checksum for `commandlinetools-linux-11076708_latest.zip`** and override if Google republishes the archive:
  ```
  docker build --build-arg CMDLINE_TOOLS_SHA256=<verified> -f docker/native-build/Dockerfile docker
  ```
- **CMake / Ninja** — installed from PyPI wheels, hash-verified by `pip` against PyPI.

For maximum reproducibility, pin the base images by **digest** (`image@sha256:…`)
instead of tag once you have pulled them in your environment.

## Build the images

```bash
docker compose -f docker/compose.yaml build                 # native, android, magisk
docker compose -f docker/compose.yaml --profile ci build    # + ci-local
docker compose -f docker/compose.yaml --profile emulator build  # + emulator
```

## Run the pipeline

### 1. Native per-ABI cross-compile
```bash
docker compose -f docker/compose.yaml run --rm native-build
# -> build/arm64-v8a/lib/{libech_dsp,libechidna}.so  (+ armeabi-v7a, x86_64)
```
Override the ABI set / platform with env, e.g.
`-e ECHIDNA_ABIS="arm64-v8a" -e ANDROID_PLATFORM=android-26`.

### 2. Flashable Magisk module (consumes step 1)
```bash
docker compose -f docker/compose.yaml run --rm magisk-packager
# -> out/echidna-magisk.zip
```
`native-build` output must exist first; `magisk-packager` declares
`depends_on: native-build (service_completed_successfully)`.

### 3. Companion APK (independent of 1/2)
```bash
docker compose -f docker/compose.yaml run --rm android-build
# -> android/app/app/build/outputs/apk/debug/app-debug.apk
```
A build a specific target instead:
`docker compose -f docker/compose.yaml run --rm android-build assembleRelease`.
The named `gradle-cache` volume persists dependencies so re-runs are offline.

### Whole pipeline at once (ci-local)
```bash
# ECHIDNA_REPO MUST be the host absolute repo path so the sibling containers
# ci-local spawns bind the same directory the host daemon sees.
ECHIDNA_REPO="$PWD" docker compose -f docker/compose.yaml --profile ci run --rm ci-local
```

### Optional: emulator (requires KVM)
```bash
docker compose -f docker/compose.yaml --profile emulator run --rm emulator
# needs /dev/kvm on the host; boots headless system-images;android-34;google_apis;x86_64
```

## Running without compose

```bash
docker run --rm -v "$PWD:/workspace" echidna/native-build
docker run --rm -v "$PWD:/workspace" -v echidna_gradle-cache:/opt/gradle-cache echidna/android-build
docker run --rm -v "$PWD:/workspace" echidna/magisk-packager
```
On Linux add `--user "$(id -u):$(id -g)"` so bind-mounted output is host-owned
(the SDK/NDK are installed world-readable, so a passed-in UID still works).

## How each image maps to a real repo command

- `native-build` → `bash tools/build_native_ndk.sh` with `ANDROID_NDK` baked in
  (the exact command from t2-e10: `ANDROID_NDK=… ANDROID_PLATFORM=android-26 bash tools/build_native_ndk.sh`).
- `android-build` → writes `local.properties` (as CI does), regenerates the
  Gradle wrapper jar if absent, then `gradle --project-dir android/app assembleDebug`.
- `magisk-packager` → `bash tools/build_magisk_module.sh` (the t2-e14 script),
  consuming `build/<abi>/lib/*` from `native-build`.

## Known limitations

- **Docker daemon required to actually build/run.** These images were authored
  and statically reviewed on a Windows host with **no Docker daemon**; image
  builds and container runs are **unverified** here. Compose schema is validated
  (`docker compose config` exits 0); entrypoints pass `bash -n`.
- **First `android-build` run needs network** to fetch Gradle dependencies from
  Google/Maven Central. Subsequent runs reuse the `gradle-cache` volume offline.
- **BoringSSL builds its own test suite.** `tools/build_native_ndk.sh` builds the
  `all` target, so the FetchContent BoringSSL sub-build compiles ~670 of its own
  test targets per ABI (it does not honor the standard `BUILD_TESTING=OFF`),
  roughly tripling native build time. It succeeds; the fix (target-scoping the
  build or a BoringSSL-specific flag) belongs to `tools/build_native_ndk.sh`
  (t2-e10) / `release.yml` (t2-e15), not this image.
- **`ci-local` sibling-container paths.** Because it drives the host daemon via
  the mounted socket, the bind-mount path must match host and container — hence
  `ECHIDNA_REPO="$PWD"`.
- **`emulator` requires KVM** and is not part of the default artifact pipeline.
