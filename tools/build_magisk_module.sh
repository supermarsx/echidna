#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/out/magisk"
ZIP_PATH="${ROOT_DIR}/out/echidna-magisk.zip"

ZYGISK_LIB="${ROOT_DIR}/build/zygisk/lib/libechidna.so"
DSP_LIB="${ROOT_DIR}/build/dsp/lib/libech_dsp.so"

if [[ ! -f "${ZYGISK_LIB}" ]]; then
  echo "Missing Zygisk library at ${ZYGISK_LIB}. Build native first." >&2
  exit 1
fi
if [[ ! -f "${DSP_LIB}" ]]; then
  echo "Missing DSP library at ${DSP_LIB}. Build DSP first." >&2
  exit 1
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/zygisk" "${OUT_DIR}/system/lib64" "${OUT_DIR}/common"

cp "${ZYGISK_LIB}" "${OUT_DIR}/zygisk/libechidna.so"
cp "${DSP_LIB}" "${OUT_DIR}/system/lib64/libech_dsp.so"

cat > "${OUT_DIR}/module.prop" <<'EOF'
id=echidna
name=Echidna Native Hooks
version=0.0.0
versionCode=1
author=supermarsx
description=Native-first AAudio/OpenSL/AudioRecord hooks with DSP pipeline.
minMagisk=26000
zygisk=true
EOF

cat > "${OUT_DIR}/service.sh" <<'EOF'
#!/system/bin/sh
set -euo pipefail
MODDIR=${0%/*}

mkdir -p /data/local/tmp/echidna/lib
cp "$MODDIR/system/lib64/libech_dsp.so" \
  /data/local/tmp/echidna/lib/libech_dsp.so
chmod 0644 /data/local/tmp/echidna/lib/libech_dsp.so
EOF
chmod +x "${OUT_DIR}/service.sh"

rm -f "${ZIP_PATH}"
(
  cd "${OUT_DIR}"
  zip -r "${ZIP_PATH}" .
)

echo "Magisk module packaged at ${ZIP_PATH}"
