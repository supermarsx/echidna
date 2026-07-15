#!/system/bin/sh
set -eu

MODDIR="${0%/*}"
LIB_SRC="$MODDIR/lib/libechidna.so"
RUNTIME_DIR="/data/adb/echidna"
LIB_DST="$RUNTIME_DIR/lib/libechidna.so"
FAILSAFE_DIR="$RUNTIME_DIR/failsafe"
FAILSAFE_REASON="$FAILSAFE_DIR/reason.txt"
BOOT_MARKER="$FAILSAFE_DIR/boot-in-progress"
BOOT_FAIL_COUNT="$FAILSAFE_DIR/boot-fail-count"
LAST_BOOT_OK="$FAILSAFE_DIR/last-boot-ok"
TMP_DIR="/data/local/tmp/echidna"
CONFIG_BIN="$TMP_DIR/echidna_config.bin"
TELEMETRY_BIN="$TMP_DIR/echidna_telemetry.bin"

log() {
    echo "[echidna][service] $1"
}

ensure_failsafe_dir() {
    mkdir -p "$RUNTIME_DIR" "$FAILSAFE_DIR"
    chmod 0755 "$RUNTIME_DIR" "$FAILSAFE_DIR" 2>/dev/null || true
}

write_failsafe_reason() {
    timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    {
        echo "time=$timestamp"
        echo "reason=$1"
    } > "$FAILSAFE_REASON" 2>/dev/null || true
}

engage_failsafe() {
    reason="$1"
    ensure_failsafe_dir
    log "Failsafe engaged: $reason"
    write_failsafe_reason "$reason"
    touch "$MODDIR/disable" 2>/dev/null || true
    if [ -d "$MODDIR/zygisk" ]; then
        touch "$MODDIR/zygisk/unloaded" 2>/dev/null || true
    fi
    rm -f "$LIB_DST" "$CONFIG_BIN" "$TELEMETRY_BIN" 2>/dev/null || true
    exit 0
}

manual_disable_marker() {
    for marker in \
        "$MODDIR/disable" \
        "$MODDIR/remove" \
        "$RUNTIME_DIR/disable" \
        "$RUNTIME_DIR/safe-mode" \
        /cache/echidna-disable \
        /metadata/echidna-disable; do
        if [ -e "$marker" ]; then
            echo "$marker"
            return 0
        fi
    done
    return 1
}

clear_boot_watchdog() {
    ensure_failsafe_dir
    rm -f "$BOOT_MARKER" "$BOOT_FAIL_COUNT" 2>/dev/null || true
    timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    {
        echo "time=$timestamp"
        echo "status=late-start-service-reached"
    } > "$LAST_BOOT_OK" 2>/dev/null || true
    log "Boot watchdog cleared"
}

prepare_runtime_dir() {
    mkdir -p "$RUNTIME_DIR/lib" "$RUNTIME_DIR/run"
    chmod 0755 "$RUNTIME_DIR" "$RUNTIME_DIR/lib"
    chmod 0770 "$RUNTIME_DIR/run"
}

install_library() {
    if [ -f "$LIB_SRC" ]; then
        cp "$LIB_SRC" "$LIB_DST"
        chmod 0644 "$LIB_DST"
        chcon u:object_r:system_lib_file:s0 "$LIB_DST" 2>/dev/null || true
        log "Installed libechidna.so into $LIB_DST"
    else
        log "libechidna.so missing from module payload"
    fi
}

marker="$(manual_disable_marker 2>/dev/null || true)"
if [ -n "$marker" ]; then
    engage_failsafe "manual disable marker present at $marker"
fi
clear_boot_watchdog
prepare_runtime_dir
install_library
