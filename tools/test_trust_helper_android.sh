#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_SOURCE="${ROOT_DIR}/android/control-service/trust-helper/src/main/java/com/echidna/magisk/EffectConfigMerger.java"
TEST_SOURCE="${ROOT_DIR}/android/control-service/trust-helper/src/test/java/com/echidna/magisk/EffectConfigMergerFixtureTest.java"
BUILD_DIR="${ECHIDNA_TRUST_HELPER_ANDROID_TEST_DIR:-${ROOT_DIR}/build/trust-helper/android-test}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
PLATFORM_VERSION="${ECHIDNA_ANDROID_PLATFORM_VERSION:-34}"
BUILD_TOOLS_VERSION="${ECHIDNA_ANDROID_BUILD_TOOLS_VERSION:-34.0.0}"

die() { echo "error: $*" >&2; exit 1; }

[[ -n "${SDK_ROOT}" ]] || die "ANDROID_SDK_ROOT or ANDROID_HOME is required"
ANDROID_JAR="${SDK_ROOT}/platforms/android-${PLATFORM_VERSION}/android.jar"
[[ -f "${ANDROID_JAR}" ]] || die "Android platform jar missing: ${ANDROID_JAR}"

JAVAC_BIN="${JAVAC:-${JAVA_HOME:+${JAVA_HOME}/bin/javac}}"
[[ -n "${JAVAC_BIN}" ]] || JAVAC_BIN="$(command -v javac || true)"
[[ -n "${JAVAC_BIN}" && -x "${JAVAC_BIN}" ]] || die "javac not found; set JAVA_HOME or JAVAC"

D8_BIN="${D8:-${SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}/d8}"
if [[ ! -x "${D8_BIN}" && -f "${D8_BIN}.bat" ]]; then
  D8_BIN="${D8_BIN}.bat"
fi
[[ -f "${D8_BIN}" || -x "${D8_BIN}" ]] || die "D8 missing: ${D8_BIN}"

ADB_BIN="${ADB:-}"
if [[ -z "${ADB_BIN}" ]]; then
  for candidate in "${SDK_ROOT}/platform-tools/adb" "${SDK_ROOT}/platform-tools/adb.exe"; do
    if [[ -f "${candidate}" || -x "${candidate}" ]]; then
      ADB_BIN="${candidate}"
      break
    fi
  done
fi
[[ -n "${ADB_BIN}" ]] || ADB_BIN="$(command -v adb || true)"
[[ -n "${ADB_BIN}" && ( -f "${ADB_BIN}" || -x "${ADB_BIN}" ) ]] \
  || die "adb not found; set ADB or ANDROID_SDK_ROOT"

case "${BUILD_DIR}" in
  "${ROOT_DIR}/build/"*|"${ROOT_DIR}/out/"*) ;;
  *) die "refusing Android test output outside repository build/ or out/: ${BUILD_DIR}" ;;
esac

ADB_ARGUMENTS=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB_ARGUMENTS=(-s "${ANDROID_SERIAL}")
fi
[[ "$("${ADB_BIN}" "${ADB_ARGUMENTS[@]}" get-state 2>/dev/null)" == "device" ]] \
  || die "selected Android device is not online"

SDK="$("${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "${SDK}" =~ ^[0-9]+$ && "${SDK}" -ge 26 ]] \
  || die "Android API 26 or newer is required (reported: ${SDK})"

CLASSES_DIR="${BUILD_DIR}/classes"
TEST_JAR="${BUILD_DIR}/effect-config-merger-android-test.jar"
rm -rf "${BUILD_DIR}"
mkdir -p "${CLASSES_DIR}"
"${JAVAC_BIN}" -source 8 -target 8 -Xlint:-options \
  -classpath "${ANDROID_JAR}" -d "${CLASSES_DIR}" "${MAIN_SOURCE}" "${TEST_SOURCE}"

CLASSES=("${CLASSES_DIR}/com/echidna/magisk/"*.class)
[[ "${#CLASSES[@]}" -gt 0 && -f "${CLASSES[0]}" ]] \
  || die "javac produced no Android fixture classes"
"${D8_BIN}" --min-api 26 --release --output "${TEST_JAR}" "${CLASSES[@]}"

DEVICE_JAR="/data/local/tmp/echidna-effect-config-merger-test-$$.jar"
ADB_TEST_JAR="${TEST_JAR}"
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*)
    ADB_TEST_JAR="$(cygpath -w "${TEST_JAR}")"
    export MSYS2_ARG_CONV_EXCL='*'
    ;;
esac
cleanup() {
  "${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell rm -f "${DEVICE_JAR}" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

"${ADB_BIN}" "${ADB_ARGUMENTS[@]}" push "${ADB_TEST_JAR}" "${DEVICE_JAR}" >/dev/null
"${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell chmod 0644 "${DEVICE_JAR}"
"${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell \
  "CLASSPATH=${DEVICE_JAR}" app_process /system/bin \
  com.echidna.magisk.EffectConfigMergerFixtureTest

ABI="$("${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
ENFORCEMENT="$("${ADB_BIN}" "${ADB_ARGUMENTS[@]}" shell getenforce 2>/dev/null | tr -d '\r')"
echo "trust-helper Android fixtures: API${SDK}/${ABI}/${ENFORCEMENT} PASS"
