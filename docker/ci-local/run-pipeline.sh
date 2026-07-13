#!/usr/bin/env bash
# Local reproduction of the CI/release artifact pipeline, driving the three
# helper images in dependency order via docker compose:
#
#   1. native-build     -> build/<abi>/lib/{libech_dsp,libechidna}.so  (per ABI)
#   2. magisk-packager  -> out/echidna-magisk.zip   (consumes step 1 output)
#   3. android-build    -> app-debug.apk            (independent of 1/2)
#
# Requires the host Docker socket (this image ships only the docker + compose
# CLI, not a daemon). Run from the repo root:
#   docker compose -f docker/compose.yaml run --rm ci-local
set -euo pipefail

COMPOSE_FILE="${ECHIDNA_COMPOSE_FILE:-docker/compose.yaml}"
COMPOSE=(docker compose -f "${COMPOSE_FILE}")

echo "== [1/3] native-build: per-ABI NDK cross-compile =="
"${COMPOSE[@]}" run --rm native-build

echo "== [2/3] magisk-packager: flashable module =="
"${COMPOSE[@]}" run --rm magisk-packager

echo "== [3/3] android-build: companion APK =="
"${COMPOSE[@]}" run --rm android-build

echo "== pipeline complete =="
echo "  native libs : build/<abi>/lib/{libech_dsp,libechidna}.so"
echo "  magisk zip  : out/echidna-magisk.zip"
echo "  debug apk   : android/app/app/build/outputs/apk/debug/app-debug.apk"
