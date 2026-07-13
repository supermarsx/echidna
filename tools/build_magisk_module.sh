#!/usr/bin/env bash
###############################################################################
# Package the Echidna Zygisk engine + DSP libraries into a flashable Magisk
# module zip.
#
# Consumes the per-ABI NDK output from tools/build_native_ndk.sh:
#     build/<abi>/lib/libechidna.so   -> zygisk/<abi>.so   (Zygisk engine)
#     build/<abi>/lib/libech_dsp.so   -> libs/<abi>/...     (staged; placed by customize.sh)
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
#   ECHIDNA_AF_OFFSETS     optional AudioFlinger offsets file to bundle
###############################################################################
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE_DIR="${ROOT_DIR}/magisk"
MAGISK_SCRIPTS_DIR="${ROOT_DIR}/android/control-service/magisk"

ABIS="${ECHIDNA_ABIS:-arm64-v8a armeabi-v7a x86_64}"
BUILD_ROOT="${ECHIDNA_BUILD_ROOT:-${ROOT_DIR}/build}"
OUT_DIR="${ECHIDNA_OUT_DIR:-${ROOT_DIR}/out/magisk}"
ZIP_PATH="${ECHIDNA_ZIP_PATH:-${ROOT_DIR}/out/echidna-magisk.zip}"

VERSION="${ECHIDNA_VERSION:-0.0.0}"
VERSION_CODE="${ECHIDNA_VERSION_CODE:-}"
if [[ -z "${VERSION_CODE}" ]]; then
  VERSION_CODE="$(echo "${VERSION}" | tr -cd '0-9')"
  [[ -z "${VERSION_CODE}" ]] && VERSION_CODE="1"
fi
OFFSETS_FILE="${ECHIDNA_AF_OFFSETS:-${ROOT_DIR}/out/echidna_af_offsets.txt}"

die() { echo "error: $*" >&2; exit 1; }

# --- Preconditions ---------------------------------------------------------
[[ -f "${TEMPLATE_DIR}/module.prop" ]] || die "missing module.prop template at ${TEMPLATE_DIR}"
[[ -f "${TEMPLATE_DIR}/customize.sh" ]] || die "missing customize.sh at ${TEMPLATE_DIR}"
[[ -f "${TEMPLATE_DIR}/META-INF/com/google/android/update-binary" ]] \
  || die "missing META-INF/.../update-binary installer stub at ${TEMPLATE_DIR}"
[[ -f "${MAGISK_SCRIPTS_DIR}/post-fs-data.sh" ]] \
  || die "missing post-fs-data.sh at ${MAGISK_SCRIPTS_DIR}"
[[ -f "${MAGISK_SCRIPTS_DIR}/service.sh" ]] || die "missing service.sh at ${MAGISK_SCRIPTS_DIR}"
[[ -f "${TEMPLATE_DIR}/sepolicy.rule" ]] || die "missing sepolicy.rule at ${TEMPLATE_DIR}"
[[ -f "${ROOT_DIR}/license.md" ]] || die "missing repository license at ${ROOT_DIR}/license.md"

# Fail loudly if any required per-ABI artifact is missing.
for abi in ${ABIS}; do
  zyg="${BUILD_ROOT}/${abi}/lib/libechidna.so"
  dsp="${BUILD_ROOT}/${abi}/lib/libech_dsp.so"
  [[ -f "${zyg}" ]] || die "missing Zygisk lib for ${abi}: ${zyg}
  build native libs first: ANDROID_NDK=<ndk> bash tools/build_native_ndk.sh"
  [[ -f "${dsp}" ]] || die "missing DSP lib for ${abi}: ${dsp}
  build native libs first: ANDROID_NDK=<ndk> bash tools/build_native_ndk.sh"
done

# --- Assemble the module tree ---------------------------------------------
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/zygisk" "${OUT_DIR}/META-INF/com/google/android"

# Installer stub (makes the zip flashable via Magisk Manager / recovery).
cp "${TEMPLATE_DIR}/META-INF/com/google/android/update-binary" \
   "${OUT_DIR}/META-INF/com/google/android/update-binary"
cp "${TEMPLATE_DIR}/META-INF/com/google/android/updater-script" \
   "${OUT_DIR}/META-INF/com/google/android/updater-script"

# customize.sh + runtime bootstrap scripts.
cp "${TEMPLATE_DIR}/customize.sh" "${OUT_DIR}/customize.sh"
cp "${MAGISK_SCRIPTS_DIR}/post-fs-data.sh" "${OUT_DIR}/post-fs-data.sh"
cp "${MAGISK_SCRIPTS_DIR}/service.sh" "${OUT_DIR}/service.sh"
cp "${ROOT_DIR}/license.md" "${OUT_DIR}/LICENSE.md"
chmod 0755 "${OUT_DIR}/customize.sh" "${OUT_DIR}/post-fs-data.sh" \
  "${OUT_DIR}/service.sh" "${OUT_DIR}/META-INF/com/google/android/update-binary"
chmod 0644 "${OUT_DIR}/LICENSE.md"

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

# Per-ABI payload: engine -> zygisk/<abi>.so, DSP -> libs/<abi>/ (staged).
for abi in ${ABIS}; do
  cp "${BUILD_ROOT}/${abi}/lib/libechidna.so" "${OUT_DIR}/zygisk/${abi}.so"
  mkdir -p "${OUT_DIR}/libs/${abi}"
  cp "${BUILD_ROOT}/${abi}/lib/libech_dsp.so" "${OUT_DIR}/libs/${abi}/libech_dsp.so"
done

# Optional AudioFlinger offsets, staged by service.sh at boot.
if [[ -f "${OFFSETS_FILE}" ]]; then
  mkdir -p "${OUT_DIR}/common"
  cp "${OFFSETS_FILE}" "${OUT_DIR}/common/echidna_af_offsets.txt"
fi

# --- Zip -------------------------------------------------------------------
rm -f "${ZIP_PATH}"
mkdir -p "$(dirname "${ZIP_PATH}")"
(
  cd "${OUT_DIR}"
  zip -r -X "${ZIP_PATH}" . -x '.*'
)

echo "Magisk module packaged at ${ZIP_PATH}"
echo "  id=echidna version=${VERSION} (${VERSION_CODE})  ABIs: ${ABIS}"
