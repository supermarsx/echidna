#!/usr/bin/env bash
###############################################################################
# Package the Echidna Zygisk engine + DSP libraries into a flashable Magisk
# module zip.
#
# Consumes the per-ABI NDK output from tools/build_native_ndk.sh:
#     build/<abi>/lib/libechidna.so   -> zygisk/<abi>.so   (Zygisk engine)
#     build/<abi>/lib/libech_dsp.so   -> libs/<abi>/...     (staged; placed by customize.sh)
#     build/<abi>/lib/libechidna_preproc.so -> preproc/<abi>/ (next-boot registration source)
#
# Produces a real, flashable module (META-INF installer stub + customize.sh +
# module.prop + the control-service SELinux/socket bootstrap scripts).
#
# Parameters (environment overrides):
#   ECHIDNA_ABIS           space-separated ABI list (default "arm64-v8a armeabi-v7a x86_64")
#   ECHIDNA_VERSION        module version string   (default "0.0.0")
#   ECHIDNA_VERSION_CODE   integer version code    (default: digits of VERSION, else 1)
#   ECHIDNA_BUILD_ROOT     per-ABI build root      (default "<repo>/build")
#   ECHIDNA_OUT_DIR        staging dir             (default "<repo>/out/magisk")
#   ECHIDNA_ZIP_PATH       output zip              (default "<repo>/out/echidna-magisk.zip")
#   ECHIDNA_TRUST_MODE     production (default) or explicit development
#   RELEASE_CERT_SHA256    exact companion release certificate pin (required)
#   ECHIDNA_TRUST_HELPER_JAR prebuilt app_process Dex/JAR helper
###############################################################################
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE_DIR="${ROOT_DIR}/magisk"
MAGISK_SCRIPTS_DIR="${ROOT_DIR}/android/control-service/magisk"

ABIS="${ECHIDNA_ABIS:-arm64-v8a armeabi-v7a x86_64}"
BUILD_ROOT="${ECHIDNA_BUILD_ROOT:-${ROOT_DIR}/build}"
OUT_DIR="${ECHIDNA_OUT_DIR:-${ROOT_DIR}/out/magisk}"
ZIP_PATH="${ECHIDNA_ZIP_PATH:-${ROOT_DIR}/out/echidna-magisk.zip}"
TRUST_MODE="${ECHIDNA_TRUST_MODE:-production}"
TRUST_HELPER_JAR="${ECHIDNA_TRUST_HELPER_JAR:-${ROOT_DIR}/build/trust-helper/echidna-trust-helper.jar}"
RELEASE_CERT_DIGEST_RAW="${ECHIDNA_RELEASE_CERT_SHA256:-${RELEASE_CERT_SHA256:-}}"
KNOWN_DEBUG_CERT="b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b"

VERSION="${ECHIDNA_VERSION:-0.0.0}"
VERSION_CODE="${ECHIDNA_VERSION_CODE:-}"
if [[ -z "${VERSION_CODE}" ]]; then
  VERSION_CODE="$(echo "${VERSION}" | tr -cd '0-9')"
  [[ -z "${VERSION_CODE}" ]] && VERSION_CODE="1"
fi

die() { echo "error: $*" >&2; exit 1; }

canonical_path() {
  realpath -m -- "$1"
}

require_repo_output_path() {
  local label="$1"
  local candidate
  candidate="$(canonical_path "$2")"
  local root
  root="$(canonical_path "${ROOT_DIR}")"
  case "${candidate}" in
    "${root}/out/"*|"${root}/build/"*) ;;
    *)
      die "refusing unsafe ${label} outside repository out/ or build/: ${candidate}"
      ;;
  esac
}

# --- Preconditions ---------------------------------------------------------
require_repo_output_path "staging directory" "${OUT_DIR}"
require_repo_output_path "zip path" "${ZIP_PATH}"
case "${TRUST_MODE}" in
  production|development) ;;
  *) die "ECHIDNA_TRUST_MODE must be production or development" ;;
esac
[[ -n "${RELEASE_CERT_DIGEST_RAW}" ]] \
  || die "RELEASE_CERT_SHA256 is required; use explicit ECHIDNA_TRUST_MODE=development for debug pins"
if printf '%s' "${RELEASE_CERT_DIGEST_RAW}" | grep -q '[^0-9A-Fa-f:[:space:]]'; then
  die "RELEASE_CERT_SHA256 contains forbidden characters or a wildcard"
fi
RELEASE_CERT_DIGEST="$(printf '%s' "${RELEASE_CERT_DIGEST_RAW}" \
  | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
[[ "${RELEASE_CERT_DIGEST}" =~ ^[0-9a-f]{64}$ ]] \
  || die "RELEASE_CERT_SHA256 must normalize to exactly 64 hex digits"
[[ "${RELEASE_CERT_DIGEST}" != "$(printf '0%.0s' {1..64})" ]] \
  || die "all-zero release certificate digest is forbidden"
if [[ "${TRUST_MODE}" == production && "${RELEASE_CERT_DIGEST}" == "${KNOWN_DEBUG_CERT}" ]]; then
  die "production module refuses the known Android debug certificate"
fi
if [[ "${TRUST_MODE}" == development ]]; then
  echo "WARNING: building explicitly non-production trust mode" >&2
fi
[[ -f "${TRUST_HELPER_JAR}" ]] || die "trust helper missing: ${TRUST_HELPER_JAR}"
command -v unzip >/dev/null 2>&1 || die "unzip is required to validate the trust helper"
[[ "$(unzip -Z1 "${TRUST_HELPER_JAR}" | grep -cx 'classes.dex')" -eq 1 ]] \
  || die "trust helper must contain exactly one classes.dex"
[[ -f "${TEMPLATE_DIR}/module.prop" ]] || die "missing module.prop template at ${TEMPLATE_DIR}"
[[ -f "${TEMPLATE_DIR}/customize.sh" ]] || die "missing customize.sh at ${TEMPLATE_DIR}"
[[ -f "${TEMPLATE_DIR}/META-INF/com/google/android/update-binary" ]] \
  || die "missing META-INF/.../update-binary installer stub at ${TEMPLATE_DIR}"
[[ -f "${MAGISK_SCRIPTS_DIR}/post-fs-data.sh" ]] \
  || die "missing post-fs-data.sh at ${MAGISK_SCRIPTS_DIR}"
[[ -f "${MAGISK_SCRIPTS_DIR}/service.sh" ]] || die "missing service.sh at ${MAGISK_SCRIPTS_DIR}"
[[ -f "${TEMPLATE_DIR}/sepolicy.rule" ]] || die "missing sepolicy.rule at ${TEMPLATE_DIR}"
[[ -f "${TEMPLATE_DIR}/common/zygisk-status.sh" ]] \
  || die "missing Zygisk status helper at ${TEMPLATE_DIR}/common/zygisk-status.sh"
[[ -f "${TEMPLATE_DIR}/common/trust-bootstrap.sh" ]] \
  || die "missing trust bootstrap at ${TEMPLATE_DIR}/common/trust-bootstrap.sh"
[[ -f "${TEMPLATE_DIR}/common/telemetry-key-label.sh" ]] \
  || die "missing effect-trust label helper at ${TEMPLATE_DIR}/common/telemetry-key-label.sh"
[[ -f "${TEMPLATE_DIR}/common/effect-registration.sh" ]] \
  || die "missing effect registration at ${TEMPLATE_DIR}/common/effect-registration.sh"
[[ -f "${TEMPLATE_DIR}/common/effect-activation.sh" ]] \
  || die "missing effect activation at ${TEMPLATE_DIR}/common/effect-activation.sh"
[[ -f "${ROOT_DIR}/license.md" ]] || die "missing repository license at ${ROOT_DIR}/license.md"
[[ -f "${ROOT_DIR}/tools/create_module_zip.py" ]] \
  || die "missing deterministic ZIP builder at ${ROOT_DIR}/tools/create_module_zip.py"
command -v python3 >/dev/null 2>&1 || die "python3 is required to create the module ZIP"

# Fail loudly if any required per-ABI artifact is missing.
for abi in ${ABIS}; do
  zyg="${BUILD_ROOT}/${abi}/lib/libechidna.so"
  dsp="${BUILD_ROOT}/${abi}/lib/libech_dsp.so"
  preproc="${BUILD_ROOT}/${abi}/lib/libechidna_preproc.so"
  [[ -f "${zyg}" ]] || die "missing Zygisk lib for ${abi}: ${zyg}
  build native libs first: ANDROID_NDK=<ndk> bash tools/build_native_ndk.sh"
  [[ -f "${dsp}" ]] || die "missing DSP lib for ${abi}: ${dsp}
  build native libs first: ANDROID_NDK=<ndk> bash tools/build_native_ndk.sh"
  [[ -f "${preproc}" ]] || die "missing legacy preprocessor for ${abi}: ${preproc}
  build native libs first: ANDROID_NDK=<ndk> bash tools/build_native_ndk.sh"
done

# --- Assemble the module tree ---------------------------------------------
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/zygisk" "${OUT_DIR}/common" "${OUT_DIR}/preproc" \
  "${OUT_DIR}/META-INF/com/google/android"

# Installer stub (makes the zip flashable via Magisk Manager / recovery).
cp "${TEMPLATE_DIR}/META-INF/com/google/android/update-binary" \
   "${OUT_DIR}/META-INF/com/google/android/update-binary"
cp "${TEMPLATE_DIR}/META-INF/com/google/android/updater-script" \
   "${OUT_DIR}/META-INF/com/google/android/updater-script"

# customize.sh + runtime bootstrap scripts.
cp "${TEMPLATE_DIR}/customize.sh" "${OUT_DIR}/customize.sh"
cp "${MAGISK_SCRIPTS_DIR}/post-fs-data.sh" "${OUT_DIR}/post-fs-data.sh"
cp "${MAGISK_SCRIPTS_DIR}/service.sh" "${OUT_DIR}/service.sh"
cp "${TEMPLATE_DIR}/common/zygisk-status.sh" "${OUT_DIR}/common/zygisk-status.sh"
cp "${TEMPLATE_DIR}/common/trust-bootstrap.sh" "${OUT_DIR}/common/trust-bootstrap.sh"
cp "${TEMPLATE_DIR}/common/telemetry-key-label.sh" \
  "${OUT_DIR}/common/telemetry-key-label.sh"
cp "${TEMPLATE_DIR}/common/effect-registration.sh" \
  "${OUT_DIR}/common/effect-registration.sh"
cp "${TEMPLATE_DIR}/common/effect-activation.sh" \
  "${OUT_DIR}/common/effect-activation.sh"
cp "${TRUST_HELPER_JAR}" "${OUT_DIR}/common/echidna-trust-helper.jar"
printf '%s\n' "${RELEASE_CERT_DIGEST}" > "${OUT_DIR}/common/release-cert-sha256"
printf '%s\n' "${TRUST_MODE}" > "${OUT_DIR}/common/trust-mode"
cp "${ROOT_DIR}/license.md" "${OUT_DIR}/LICENSE.md"
chmod 0755 "${OUT_DIR}/customize.sh" "${OUT_DIR}/post-fs-data.sh" \
  "${OUT_DIR}/service.sh" "${OUT_DIR}/META-INF/com/google/android/update-binary"
chmod 0644 "${OUT_DIR}/LICENSE.md"
chmod 0644 "${OUT_DIR}/common/zygisk-status.sh"
chmod 0644 "${OUT_DIR}/common/telemetry-key-label.sh"
chmod 0755 "${OUT_DIR}/common/trust-bootstrap.sh" \
  "${OUT_DIR}/common/effect-registration.sh" \
  "${OUT_DIR}/common/effect-activation.sh"
chmod 0444 "${OUT_DIR}/common/echidna-trust-helper.jar" \
  "${OUT_DIR}/common/release-cert-sha256" "${OUT_DIR}/common/trust-mode"

# SELinux policy: makes the config/telemetry region readable by hooked app
# domains under enforcing SELinux (Magisk applies sepolicy.rule at boot).
if [[ -f "${TEMPLATE_DIR}/sepolicy.rule" ]]; then
  cp "${TEMPLATE_DIR}/sepolicy.rule" "${OUT_DIR}/sepolicy.rule"
  chmod 0644 "${OUT_DIR}/sepolicy.rule"
fi

# module.prop with version substituted from the canonical template.
sed -e "s/@ECHIDNA_VERSION@/${VERSION}/g" \
    -e "s/@ECHIDNA_VERSION_CODE@/${VERSION_CODE}/g" \
    "${TEMPLATE_DIR}/module.prop" > "${OUT_DIR}/module.prop"

# Per-ABI payload: engine, DSP, and default-off legacy effect registration source.
for abi in ${ABIS}; do
  cp "${BUILD_ROOT}/${abi}/lib/libechidna.so" "${OUT_DIR}/zygisk/${abi}.so"
  mkdir -p "${OUT_DIR}/libs/${abi}"
  cp "${BUILD_ROOT}/${abi}/lib/libech_dsp.so" "${OUT_DIR}/libs/${abi}/libech_dsp.so"
  mkdir -p "${OUT_DIR}/preproc/${abi}"
  cp "${BUILD_ROOT}/${abi}/lib/libechidna_preproc.so" \
    "${OUT_DIR}/preproc/${abi}/libechidna_preproc.so"
done

# --- Zip -------------------------------------------------------------------
python3 "${ROOT_DIR}/tools/create_module_zip.py" \
  --source "${OUT_DIR}" \
  --output "${ZIP_PATH}" \
  --executable customize.sh \
  --executable post-fs-data.sh \
  --executable service.sh \
  --executable META-INF/com/google/android/update-binary \
  --executable common/trust-bootstrap.sh \
  --executable common/effect-registration.sh \
  --executable common/effect-activation.sh \
  --read-only common/echidna-trust-helper.jar \
  --read-only common/release-cert-sha256 \
  --read-only common/trust-mode

echo "Magisk module packaged at ${ZIP_PATH}"
echo "  id=echidna version=${VERSION} (${VERSION_CODE})  ABIs: ${ABIS}"
