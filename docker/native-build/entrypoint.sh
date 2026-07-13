#!/usr/bin/env bash
# Entrypoint for echidna/native-build.
# Cross-compiles the per-ABI native libraries by invoking the REAL repo build
# script (tools/build_native_ndk.sh, owned by t2-e10) with the NDK baked into
# this image. Output lands in the bind-mounted repo at:
#   build/<abi>/lib/libech_dsp.so
#   build/<abi>/lib/libechidna.so
# for each of: arm64-v8a (primary), armeabi-v7a, x86_64.
set -euo pipefail

: "${ANDROID_NDK:?ANDROID_NDK must point at the NDK root (baked into the image)}"
export ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-26}"

if [ ! -f tools/build_native_ndk.sh ]; then
  echo "ERROR: tools/build_native_ndk.sh not found. Mount the repo root at /workspace." >&2
  echo "       e.g. docker run -v \"\$PWD:/workspace\" echidna/native-build" >&2
  exit 1
fi

# Allow an override command (e.g. an interactive shell or a single-ABI build).
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

echo "echidna/native-build: NDK=${ANDROID_NDK} PLATFORM=${ANDROID_PLATFORM}"
echo "ABIs: ${ECHIDNA_ABIS:-arm64-v8a armeabi-v7a x86_64}"
exec bash tools/build_native_ndk.sh
