#!/usr/bin/env bash
set -euo pipefail

# Cross-compile the Echidna native libraries for each Android ABI using the NDK.
#
# Output layout (consumed by tools/build_magisk_module.sh [t2-e14] and
# .github/workflows/release.yml [t2-e15]):
#
#   build/<abi>/lib/libech_dsp.so     (DSP engine)
#   build/<abi>/lib/libechidna.so     (Zygisk module -> packaged as zygisk/<abi>.so)
#
# for each <abi> in: arm64-v8a (primary), armeabi-v7a, x86_64.
#
# Requirements: a real Android NDK (r26/r27), CMake >= 3.22, Ninja. This CANNOT
# run on the Windows dev host (no NDK) — it is designed for the docker
# `echidna/native-build` image (t2-e16) or any host with the NDK installed.
#
# Environment:
#   ANDROID_NDK            NDK root (falls back to ANDROID_NDK_HOME / ANDROID_NDK_ROOT)
#   ANDROID_PLATFORM       min API level        (default: android-26)
#   ECHIDNA_ABIS           space-separated ABIs (default: arm64-v8a armeabi-v7a x86_64)
#   ECHIDNA_BUILD_TYPE     CMake build type     (default: Release)
#   ECHIDNA_CMAKE_GENERATOR CMake generator     (default: Ninja)
#   ECHIDNA_CMAKE_EXTRA_ARGS  extra -D args passed to configure (e.g. BoringSSL pin)
#   CMAKE                  cmake binary         (default: cmake)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${ANDROID_NDK:=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "${ANDROID_NDK}" ]]; then
  echo "ERROR: set ANDROID_NDK (or ANDROID_NDK_HOME / ANDROID_NDK_ROOT) to the NDK path." >&2
  exit 1
fi

TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
if [[ ! -f "${TOOLCHAIN_FILE}" ]]; then
  echo "ERROR: NDK toolchain file not found at ${TOOLCHAIN_FILE}" >&2
  echo "       Check that ANDROID_NDK points at an NDK root (r26/r27)." >&2
  exit 1
fi

ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-26}"
ABIS="${ECHIDNA_ABIS:-arm64-v8a armeabi-v7a x86_64}"
BUILD_TYPE="${ECHIDNA_BUILD_TYPE:-Release}"
GENERATOR="${ECHIDNA_CMAKE_GENERATOR:-Ninja}"
CMAKE_BIN="${CMAKE:-cmake}"

echo "NDK:       ${ANDROID_NDK}"
echo "Platform:  ${ANDROID_PLATFORM}"
echo "ABIs:      ${ABIS}"
echo "BuildType: ${BUILD_TYPE}"
echo "Generator: ${GENERATOR}"

for ABI in ${ABIS}; do
  BUILD_DIR="${ROOT_DIR}/build/${ABI}"
  echo ""
  echo "==> [${ABI}] configure"
  "${CMAKE_BIN}" -S "${ROOT_DIR}/native" -B "${BUILD_DIR}" -G "${GENERATOR}" \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
    -DBUILD_TESTING=OFF \
    ${ECHIDNA_CMAKE_EXTRA_ARGS:-}

  echo "==> [${ABI}] build"
  "${CMAKE_BIN}" --build "${BUILD_DIR}" --config "${BUILD_TYPE}"

  DSP_SO="${BUILD_DIR}/lib/libech_dsp.so"
  ZYG_SO="${BUILD_DIR}/lib/libechidna.so"
  if [[ ! -f "${DSP_SO}" ]]; then
    echo "ERROR: expected ${DSP_SO} was not produced for ${ABI}" >&2
    exit 1
  fi
  if [[ ! -f "${ZYG_SO}" ]]; then
    echo "ERROR: expected ${ZYG_SO} was not produced for ${ABI}" >&2
    exit 1
  fi
  echo "==> [${ABI}] OK -> build/${ABI}/lib/{libech_dsp.so,libechidna.so}"
done

echo ""
echo "Native NDK build complete for: ${ABIS}"
