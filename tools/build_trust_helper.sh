#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${ROOT_DIR}/android/control-service/trust-helper/src/main/java"
BUILD_DIR="${ECHIDNA_TRUST_HELPER_BUILD_DIR:-${ROOT_DIR}/build/trust-helper}"
OUTPUT="${ECHIDNA_TRUST_HELPER_OUT:-${BUILD_DIR}/echidna-trust-helper.jar}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
PLATFORM_VERSION="${ECHIDNA_ANDROID_PLATFORM_VERSION:-34}"
BUILD_TOOLS_VERSION="${ECHIDNA_ANDROID_BUILD_TOOLS_VERSION:-34.0.0}"

die() { echo "error: $*" >&2; exit 1; }

[[ -n "${SDK_ROOT}" ]] || die "ANDROID_SDK_ROOT or ANDROID_HOME is required"
ANDROID_JAR="${SDK_ROOT}/platforms/android-${PLATFORM_VERSION}/android.jar"
[[ -f "${ANDROID_JAR}" ]] || die "Android platform jar missing: ${ANDROID_JAR}"

JAVAC_BIN="${JAVAC:-${JAVA_HOME:+${JAVA_HOME}/bin/javac}}"
if [[ -z "${JAVAC_BIN}" ]]; then
  JAVAC_BIN="$(command -v javac || true)"
fi
[[ -n "${JAVAC_BIN}" && -x "${JAVAC_BIN}" ]] || die "javac not found; set JAVA_HOME or JAVAC"

D8_BIN="${D8:-${SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}/d8}"
if [[ ! -x "${D8_BIN}" && -f "${D8_BIN}.bat" ]]; then
  D8_BIN="${D8_BIN}.bat"
fi
[[ -f "${D8_BIN}" || -x "${D8_BIN}" ]] || die "D8 missing: ${D8_BIN}"

case "${BUILD_DIR}" in
  "${ROOT_DIR}/build/"*|"${ROOT_DIR}/out/"*) ;;
  *) die "refusing trust-helper build directory outside repository build/ or out/: ${BUILD_DIR}" ;;
esac
case "${OUTPUT}" in
  "${BUILD_DIR}/"*) ;;
  *) die "trust-helper output must remain inside its build directory: ${OUTPUT}" ;;
esac

CLASSES_DIR="${BUILD_DIR}/classes"
rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}" "$(dirname "${OUTPUT}")"
rm -f "${OUTPUT}"

SOURCES=("${SOURCE_DIR}/com/echidna/magisk/"*.java)
[[ "${#SOURCES[@]}" -gt 0 && -f "${SOURCES[0]}" ]] \
  || die "no trust-helper Java sources found"
"${JAVAC_BIN}" -source 8 -target 8 -Xlint:-options \
  -classpath "${ANDROID_JAR}" -d "${CLASSES_DIR}" "${SOURCES[@]}"

CLASSES=("${CLASSES_DIR}/com/echidna/magisk/"*.class)
[[ "${#CLASSES[@]}" -gt 0 && -f "${CLASSES[0]}" ]] \
  || die "javac produced no trust-helper classes"
"${D8_BIN}" --min-api 26 --release --output "${OUTPUT}" "${CLASSES[@]}"

if command -v unzip >/dev/null 2>&1; then
  [[ "$(unzip -Z1 "${OUTPUT}" | grep -cx 'classes.dex')" -eq 1 ]] \
    || die "D8 output does not contain exactly one classes.dex"
fi
echo "Trust helper built: ${OUTPUT}"
