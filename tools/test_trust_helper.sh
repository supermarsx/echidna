#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN_DIR="${ROOT_DIR}/android/control-service/trust-helper/src/main/java/com/echidna/magisk"
TEST_SOURCE="${ROOT_DIR}/android/control-service/trust-helper/src/test/java/com/echidna/magisk/TrustPolicyFixtureTest.java"
MERGER_TEST_SOURCE="${ROOT_DIR}/android/control-service/trust-helper/src/test/java/com/echidna/magisk/EffectConfigMergerFixtureTest.java"
OUT_DIR="${ROOT_DIR}/build/trust-helper/host-test"

JAVAC_BIN="${JAVAC:-${JAVA_HOME:+${JAVA_HOME}/bin/javac}}"
JAVA_BIN="${JAVA:-${JAVA_HOME:+${JAVA_HOME}/bin/java}}"
[[ -n "${JAVAC_BIN}" ]] || JAVAC_BIN="$(command -v javac || true)"
[[ -n "${JAVA_BIN}" ]] || JAVA_BIN="$(command -v java || true)"
[[ -x "${JAVAC_BIN}" && -x "${JAVA_BIN}" ]] || {
  echo "error: java/javac not found; set JAVA_HOME, JAVA, and JAVAC" >&2
  exit 1
}

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"
"${JAVAC_BIN}" --release 8 -Xlint:-options -d "${OUT_DIR}" \
  "${MAIN_DIR}/SignerPolicy.java" \
  "${MAIN_DIR}/SpkiPolicy.java" \
  "${MAIN_DIR}/EffectConfigMerger.java" \
  "${MERGER_TEST_SOURCE}" \
  "${TEST_SOURCE}"
"${JAVA_BIN}" -cp "${OUT_DIR}" com.echidna.magisk.TrustPolicyFixtureTest
"${JAVA_BIN}" -cp "${OUT_DIR}" com.echidna.magisk.EffectConfigMergerFixtureTest
