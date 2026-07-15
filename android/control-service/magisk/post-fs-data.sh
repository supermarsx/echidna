#!/system/bin/sh
set -eu

MODDIR="${0%/*}"
RUNTIME_DIR="/data/adb/echidna"
LIB_DST="$RUNTIME_DIR/lib/libechidna.so"
FAILSAFE_DIR="$RUNTIME_DIR/failsafe"
FAILSAFE_REASON="$FAILSAFE_DIR/reason.txt"
BOOT_MARKER="$FAILSAFE_DIR/boot-in-progress"
BOOT_FAIL_COUNT="$FAILSAFE_DIR/boot-fail-count"
LAST_BOOT_OK="$FAILSAFE_DIR/last-boot-ok"
BOOT_FAIL_LIMIT="${ECHIDNA_BOOT_FAIL_LIMIT:-2}"
# DSP engine plugin dir (native/dsp/src/engine.cpp default) and shared runtime
# region dir. /dev/shm does not exist on stock Android, so the shared regions
# live under /data.
TMP_DIR="/data/local/tmp/echidna"
PLUGIN_DIR="$TMP_DIR/plugins"
# Shared config/telemetry regions read by hooked app processes. They must exist,
# be pre-sized (a hooked app maps them read-only and cannot create/grow them),
# and carry SELinux types that app domains can reach (see magisk/sepolicy.rule).
# 64 KiB comfortably exceeds both packed layouts (~4 KiB each).
CONFIG_BIN="$TMP_DIR/echidna_config.bin"
TELEMETRY_BIN="$TMP_DIR/echidna_telemetry.bin"
REGION_BYTES=65536
ZYGISK_STATUS_HELPER="$MODDIR/common/zygisk-status.sh"

log() {
    echo "[echidna][post-fs] $1"
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

read_boot_fail_count() {
    if [ ! -f "$BOOT_FAIL_COUNT" ]; then
        echo 0
        return
    fi
    count="$(cat "$BOOT_FAIL_COUNT" 2>/dev/null || echo 0)"
    case "$count" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$count" ;;
    esac
}

region_size() {
    if [ ! -f "$1" ]; then
        echo 0
        return
    fi
    bytes="$(wc -c < "$1" 2>/dev/null | tr -d ' ' || echo 0)"
    case "$bytes" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$bytes" ;;
    esac
}

arm_boot_watchdog() {
    ensure_failsafe_dir
    case "$BOOT_FAIL_LIMIT" in
        ''|*[!0-9]*) BOOT_FAIL_LIMIT=2 ;;
    esac
    if [ "$BOOT_FAIL_LIMIT" -lt 2 ]; then
        BOOT_FAIL_LIMIT=2
    fi
    if [ -f "$BOOT_MARKER" ]; then
        current="$(read_boot_fail_count)"
        failures=$((current + 1))
        echo "$failures" > "$BOOT_FAIL_COUNT" 2>/dev/null || true
        log "Previous boot did not reach late-start service ($failures/$BOOT_FAIL_LIMIT)"
        if [ "$failures" -ge "$BOOT_FAIL_LIMIT" ]; then
            engage_failsafe "boot watchdog tripped after $failures unfinished boots"
        fi
    else
        echo 0 > "$BOOT_FAIL_COUNT" 2>/dev/null || true
    fi
    touch "$BOOT_MARKER" 2>/dev/null || true
    rm -f "$LAST_BOOT_OK" 2>/dev/null || true
}

bootstrap() {
    mkdir -p "$RUNTIME_DIR/lib" "$RUNTIME_DIR/run"
    chmod 0755 "$RUNTIME_DIR" "$RUNTIME_DIR/lib"
    chmod 0770 "$RUNTIME_DIR/run"
    # customize.sh placed the device-ABI engine at $MODDIR/lib/libechidna.so;
    # mirror it to the primary JNI search path (echidna_control_jni.cpp).
    if [ ! -f "$LIB_DST" ] && [ -f "$MODDIR/lib/libechidna.so" ]; then
        cp "$MODDIR/lib/libechidna.so" "$LIB_DST"
        chmod 0644 "$LIB_DST"
    fi
}

prepare_tmp() {
    mkdir -p "$PLUGIN_DIR"
    # Hooked apps need traversal, config reads, and telemetry-file writes, but
    # never directory writes. World-writable directories would let an app
    # unlink/replace the root-published regions when SELinux is permissive.
    chmod 0755 "$TMP_DIR"
    chmod 0755 "$PLUGIN_DIR"
}

apply_sepolicy() {
    # Define the region types + least-privilege allows in the LIVE kernel policy.
    # The module also ships magisk/sepolicy.rule (Magisk applies it at boot on a
    # normal install); applying here as well makes the module self-sufficient on
    # environments where the module-sepolicy pipeline does not run, and lets the
    # subsequent chcon reference types that are guaranteed to exist. Mirrors the
    # existing magiskpolicy usage in service.sh. Keep in sync with sepolicy.rule.
    command -v magiskpolicy >/dev/null 2>&1 || return 0
    magiskpolicy --live "type echidna_config_file" 2>/dev/null || true
    magiskpolicy --live "typeattribute echidna_config_file file_type" 2>/dev/null || true
    magiskpolicy --live "typeattribute echidna_config_file data_file_type" 2>/dev/null || true
    magiskpolicy --live "type echidna_telemetry_file" 2>/dev/null || true
    magiskpolicy --live "typeattribute echidna_telemetry_file file_type" 2>/dev/null || true
    magiskpolicy --live "typeattribute echidna_telemetry_file data_file_type" 2>/dev/null || true
    magiskpolicy --live "allow appdomain shell_data_file dir search" 2>/dev/null || true
    magiskpolicy --live "allow appdomain echidna_config_file dir { search getattr open read }" 2>/dev/null || true
    magiskpolicy --live "allow appdomain echidna_config_file file { getattr open read map }" 2>/dev/null || true
    magiskpolicy --live "allow untrusted_app echidna_config_file dir { search getattr open read }" 2>/dev/null || true
    magiskpolicy --live "allow untrusted_app echidna_config_file file { getattr open read map }" 2>/dev/null || true
    magiskpolicy --live "dontaudit appdomain echidna_config_file file write" 2>/dev/null || true
    magiskpolicy --live "dontaudit untrusted_app echidna_config_file file write" 2>/dev/null || true
    magiskpolicy --live "allow appdomain echidna_telemetry_file dir { search getattr open read }" 2>/dev/null || true
    magiskpolicy --live "allow appdomain echidna_telemetry_file file { getattr open read write append map }" 2>/dev/null || true
    magiskpolicy --live "allow untrusted_app echidna_telemetry_file dir { search getattr open read }" 2>/dev/null || true
    magiskpolicy --live "allow untrusted_app echidna_telemetry_file file { getattr open read write append map }" 2>/dev/null || true
}

prepare_shared_regions() {
    # Create + pre-size the config/telemetry regions if absent (idempotent: never
    # truncate an existing region the controller may already have populated).
    for region in "$CONFIG_BIN" "$TELEMETRY_BIN"; do
        if [ ! -f "$region" ] || [ "$(region_size "$region")" -lt "$REGION_BYTES" ]; then
            dd if=/dev/zero of="$region" bs=1 count=0 seek="$REGION_BYTES" 2>/dev/null || \
                dd if=/dev/zero of="$region" bs=1024 count=64 2>/dev/null
        fi
    done
    chown root:root "$CONFIG_BIN" "$TELEMETRY_BIN" 2>/dev/null || true
    # Config: root writes, apps read-only. Telemetry: apps read-write.
    chmod 0644 "$CONFIG_BIN"
    chmod 0666 "$TELEMETRY_BIN"
    # Label so hooked app domains can reach the regions under enforcing SELinux
    # (types + allows come from magisk/sepolicy.rule). Label the dir with the
    # config type; app domains need only `search` on it (granted to the type).
    chcon u:object_r:echidna_config_file:s0 "$TMP_DIR" 2>/dev/null || true
    chcon u:object_r:echidna_config_file:s0 "$CONFIG_BIN" 2>/dev/null || true
    chcon u:object_r:echidna_telemetry_file:s0 "$TELEMETRY_BIN" 2>/dev/null || true
}

ensure_permissions() {
    chown -R root:shell "$RUNTIME_DIR" 2>/dev/null || true
    chcon -R u:object_r:system_lib_file:s0 "$RUNTIME_DIR/lib" 2>/dev/null || true
    chcon -R u:object_r:device:s0 "$RUNTIME_DIR/run" 2>/dev/null || true
}

report_status() {
    if command -v magisk >/dev/null 2>&1; then
        if [ -r "$ZYGISK_STATUS_HELPER" ]; then
            . "$ZYGISK_STATUS_HELPER"
            echidna_zygisk_enabled || log "Zygisk appears disabled"
        else
            log "Zygisk status helper missing; cannot verify loader state"
        fi
    fi
    if [ ! -f "$LIB_DST" ]; then
        log "libechidna.so not staged; companion app will show Java-only mode"
    fi
}

marker="$(manual_disable_marker 2>/dev/null || true)"
if [ -n "$marker" ]; then
    engage_failsafe "manual disable marker present at $marker"
fi
arm_boot_watchdog
bootstrap
prepare_tmp
apply_sepolicy
prepare_shared_regions
ensure_permissions
report_status
