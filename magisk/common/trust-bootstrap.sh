#!/system/bin/sh
set -eu

MODDIR="${1:?module directory is required}"
RUNTIME_DIR="${ECHIDNA_RUNTIME_DIR:-/data/adb/echidna}"
STATUS_DIR="$RUNTIME_DIR/trust"
STATUS_FILE="$STATUS_DIR/status.txt"
HELPER="$MODDIR/common/echidna-trust-helper.jar"
EXPECTED_DIGEST="$MODDIR/common/release-cert-sha256"
TRUST_MODE_FILE="$MODDIR/common/trust-mode"
PENDING_DIR="$MODDIR/trust/next-boot"
KEY_NAME="preprocessor_controller_p256.spki"
PENDING_KEY="$PENDING_DIR/$KEY_NAME"
ACTIVE_KEY="$MODDIR/system/etc/echidna/$KEY_NAME"
APP_PROCESS="${ECHIDNA_APP_PROCESS:-/system/bin/app_process}"

log() {
    echo "[echidna][trust] $1"
}

write_status() {
    state="$1"
    reason="$2"
    reboot="$3"
    mkdir -p "$STATUS_DIR"
    chmod 0700 "$STATUS_DIR" 2>/dev/null || true
    temporary="$STATUS_FILE.tmp.$$"
    umask 077
    {
        echo "state=$state"
        echo "reason=$reason"
        echo "reboot_required=$reboot"
        echo "active_path=/system/etc/echidna/$KEY_NAME"
        echo "pending_path=$PENDING_KEY"
    } > "$temporary"
    chown root:root "$temporary" 2>/dev/null || true
    chmod 0600 "$temporary"
    mv -f "$temporary" "$STATUS_FILE"
}

fail_closed() {
    reason="$1"
    write_status "failed" "$reason" "false"
    log "Trust bootstrap failed closed: $reason"
    log "Identity bypass remains active. Inspect $STATUS_FILE."
    log "Recovery: disable the module before removing a pinned key; reinstall and reboot to re-enrol."
    return 1
}

for required in "$HELPER" "$EXPECTED_DIGEST" "$TRUST_MODE_FILE"; do
    if [ ! -f "$required" ] || [ -L "$required" ]; then
        fail_closed "required module trust file missing or unsafe: $required"
        exit 1
    fi
done
if [ ! -x "$APP_PROCESS" ]; then
    fail_closed "app_process is unavailable: $APP_PROCESS"
    exit 1
fi

mode="$(cat "$TRUST_MODE_FILE" 2>/dev/null || true)"
case "$mode" in
    production) ;;
    development)
        log "⚠️  NON-PRODUCTION trust mode: debug certificate pins may be accepted"
        ;;
    *)
        fail_closed "invalid trust mode"
        exit 1
        ;;
esac

mkdir -p "$PENDING_DIR" "$STATUS_DIR"
chown root:root "$MODDIR/trust" "$PENDING_DIR" "$STATUS_DIR" 2>/dev/null || true
chmod 0700 "$MODDIR/trust" "$PENDING_DIR" "$STATUS_DIR"

if output="$(ANDROID_DATA=/data CLASSPATH="$HELPER" "$APP_PROCESS" /system/bin \
        com.echidna.magisk.TrustBootstrapMain \
        --expected-file "$EXPECTED_DIGEST" \
        --mode "$mode" \
        --pending "$PENDING_KEY" \
        --active "$ACTIVE_KEY" 2>&1)"; then
    case "$output" in
        *ECHIDNA_TRUST_V1*) ;;
        *)
            fail_closed "helper returned an unrecognized success response"
            exit 1
            ;;
    esac
    pin_status="$(printf '%s\n' "$output" | grep '^status=' | head -n 1 | cut -d= -f2-)"
    reboot="$(printf '%s\n' "$output" | grep '^reboot_required=' | head -n 1 | cut -d= -f2-)"
    case "$pin_status:$reboot" in
        pinned-next-boot:true|already-pinned:true|active-match:false) ;;
        *)
            fail_closed "helper returned invalid pin state"
            exit 1
            ;;
    esac
    write_status "$pin_status" "verified signer, UID, dataDir, and P-256 SPKI" "$reboot"
    if [ "$reboot" = true ]; then
        log "SPKI verified and pinned inertly for next boot; reboot is required for activation"
    else
        log "Active SPKI matches the verified app key; no rotation performed"
    fi
    exit 0
fi

detail="$(printf '%s\n' "$output" | tail -n 1 | tr -d '\r' | cut -c1-240)"
fail_closed "app_process verification rejected package/key: $detail"
exit 1
