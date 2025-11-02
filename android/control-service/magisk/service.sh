#!/system/bin/sh
set -euo pipefail

MODDIR="${0%/*}"
LIB_SRC="$MODDIR/lib/libechidna.so"
RUNTIME_DIR="/data/adb/echidna"
LIB_DST="$RUNTIME_DIR/lib/libechidna.so"
SOCKET_PATH="/data/local/tmp/echidna_profiles.sock"

log() {
    echo "[echidna-control][service] $1"
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

prepare_socket_endpoint() {
    if [ -S "$SOCKET_PATH" ]; then
        chown root:shell "$SOCKET_PATH" 2>/dev/null || true
        chmod 0666 "$SOCKET_PATH" 2>/dev/null || true
    else
        log "Socket will be created on demand by the Zygisk side"
    fi
    chcon u:object_r:device:s0 "$SOCKET_PATH" 2>/dev/null || true
}

patch_selinux() {
    if [ "$(getenforce 2>/dev/null || echo Enforcing)" != "Enforcing" ]; then
        return
    fi
    if command -v magiskpolicy >/dev/null 2>&1; then
        if magiskpolicy --live "allow zygote zygote process dyntransition" \
            "allow zygote zygote binder call" \
            "allow zygote zygote binder transfer"; then
            log "Applied SELinux relaxations for native engine"
        else
            log "Failed to adjust SELinux policy; Java-only fallback may be required"
        fi
    else
        log "magiskpolicy not available; native engine may require Java fallback"
    fi
}

prepare_runtime_dir
install_library
prepare_socket_endpoint
patch_selinux
