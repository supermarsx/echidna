#!/usr/bin/env bash
# Entrypoint for echidna/magisk-packager.
# Lays out the flashable Magisk module and emits the zip by running the repo's
# real packaging script (tools/build_magisk_module.sh, owned by t2-e14). That
# script consumes the per-ABI native libs produced by echidna/native-build
# (build/<abi>/lib/libechidna.so -> zygisk/<abi>.so, build/<abi>/lib/libech_dsp.so
# -> system/lib*), adds META-INF + customize.sh, and zips out/echidna-magisk.zip.
set -euo pipefail

if [ ! -f tools/build_magisk_module.sh ]; then
  echo "ERROR: tools/build_magisk_module.sh not found. Mount the repo root at /workspace." >&2
  echo "       e.g. docker run -v \"\$PWD:/workspace\" echidna/magisk-packager" >&2
  exit 1
fi

# Allow an override command (e.g. an interactive shell).
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

echo "echidna/magisk-packager: building flashable Magisk zip from per-ABI native output"
exec bash tools/build_magisk_module.sh
