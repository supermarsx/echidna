#!/system/bin/sh
set -euo pipefail

MODDIR="${0%/*}"
RUNTIME_DIR="/data/adb/echidna"
LIB_DST="$RUNTIME_DIR/lib/libechidna.so"

log() {
    echo "[echidna-control][post-fs] $1"
}

bootstrap() {
    mkdir -p "$RUNTIME_DIR/lib" "$RUNTIME_DIR/run"
    chmod 0755 "$RUNTIME_DIR" "$RUNTIME_DIR/lib"
    chmod 0770 "$RUNTIME_DIR/run"
    if [ ! -f "$LIB_DST" ] && [ -f "$MODDIR/lib/libechidna.so" ]; then
        cp "$MODDIR/lib/libechidna.so" "$LIB_DST"
        chmod 0644 "$LIB_DST"
    fi
}

ensure_permissions() {
    chown -R root:shell "$RUNTIME_DIR" 2>/dev/null || true
    chcon -R u:object_r:system_lib_file:s0 "$RUNTIME_DIR/lib" 2>/dev/null || true
    chcon -R u:object_r:device:s0 "$RUNTIME_DIR/run" 2>/dev/null || true
}

report_status() {
    if command -v magisk >/dev/null 2>&1; then
        magisk --zygisk >/dev/null 2>&1 || log "Zygisk appears disabled"
    fi
    if [ ! -f "$LIB_DST" ]; then
        log "libechidna.so not staged; companion app will show Java-only mode"
    fi
}

bootstrap
ensure_permissions
report_status
