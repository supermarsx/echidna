#!/system/bin/sh
set -eu

MODDIR="${1:?module directory is required}"
RUNTIME_DIR="${ECHIDNA_RUNTIME_DIR:-/data/adb/echidna}"
STATUS_DIR="$RUNTIME_DIR/trust"
STATUS_FILE="$STATUS_DIR/status.txt"
HELPER="$MODDIR/common/echidna-trust-helper.jar"
EXPECTED_DIGEST="$MODDIR/common/release-cert-sha256"
TRUST_MODE_FILE="$MODDIR/common/trust-mode"
TELEMETRY_KEY_LABEL_HELPER="$MODDIR/common/telemetry-key-label.sh"
PENDING_DIR="$MODDIR/trust/next-boot"
TRUST_STATE_DIR="$MODDIR/trust/state"
KEY_NAME="preprocessor_controller_p256.spki"
TELEMETRY_KEY_NAME="preprocessor_telemetry_hmac.key"
PENDING_KEY="$PENDING_DIR/$KEY_NAME"
ACTIVE_KEY="$MODDIR/system/etc/echidna/$KEY_NAME"
TELEMETRY_ROOT_PIN="$TRUST_STATE_DIR/$TELEMETRY_KEY_NAME"
TELEMETRY_METADATA="$TELEMETRY_ROOT_PIN.meta"
TELEMETRY_EFFECT_KEY="$MODDIR/system/etc/echidna/$TELEMETRY_KEY_NAME"
APP_PROCESS="${ECHIDNA_APP_PROCESS:-/system/bin/app_process}"

log() {
    echo "[echidna][trust] $1"
}

write_status() {
    state="$1"
    reason="$2"
    reboot="$3"
    telemetry_state="${4:-unavailable}"
    telemetry_sha256="${5:-unavailable}"
    telemetry_key_id="${6:-unavailable}"
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
        echo "telemetry_state=$telemetry_state"
        echo "telemetry_key_sha256=$telemetry_sha256"
        echo "telemetry_key_id=$telemetry_key_id"
        echo "telemetry_effect_path=/system/etc/echidna/$TELEMETRY_KEY_NAME"
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
    log "Recovery: restore companion P-256 enrolment, or explicitly reinstall/reprovision trust state."
    log "Never replace the telemetry root pin from an app/effect copy; silent rotation is refused."
    return 1
}

for required in "$HELPER" "$EXPECTED_DIGEST" "$TRUST_MODE_FILE" \
        "$TELEMETRY_KEY_LABEL_HELPER"; do
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

mkdir -p "$PENDING_DIR" "$TRUST_STATE_DIR" "$STATUS_DIR" \
    "$MODDIR/system/etc/echidna"
chown root:root "$MODDIR/trust" "$PENDING_DIR" "$TRUST_STATE_DIR" \
    "$STATUS_DIR" "$MODDIR/system/etc/echidna" 2>/dev/null || true
chmod 0700 "$MODDIR/trust" "$PENDING_DIR" "$TRUST_STATE_DIR" "$STATUS_DIR"
chmod 0755 "$MODDIR/system/etc/echidna"

if output="$(ANDROID_DATA=/data CLASSPATH="$HELPER" "$APP_PROCESS" /system/bin \
        com.echidna.magisk.TrustBootstrapMain \
        --expected-file "$EXPECTED_DIGEST" \
        --mode "$mode" \
        --pending "$PENDING_KEY" \
        --active "$ACTIVE_KEY" \
        --telemetry-root "$TELEMETRY_ROOT_PIN" \
        --telemetry-metadata "$TELEMETRY_METADATA" \
        --telemetry-effect "$TELEMETRY_EFFECT_KEY" 2>&1)"; then
    case "$output" in
        *ECHIDNA_TRUST_V2*) ;;
        *)
            fail_closed "helper returned an unrecognized success response"
            exit 1
            ;;
    esac
    pin_status="$(printf '%s\n' "$output" | grep '^status=' | head -n 1 | cut -d= -f2-)"
    telemetry_status="$(printf '%s\n' "$output" \
        | grep '^telemetry_status=' | head -n 1 | cut -d= -f2-)"
    telemetry_sha256="$(printf '%s\n' "$output" \
        | grep '^telemetry_key_sha256=' | head -n 1 | cut -d= -f2-)"
    telemetry_key_id="$(printf '%s\n' "$output" \
        | grep '^telemetry_key_id=' | head -n 1 | cut -d= -f2-)"
    reboot="$(printf '%s\n' "$output" | grep '^reboot_required=' | head -n 1 | cut -d= -f2-)"
    case "$pin_status:$reboot" in
        pinned-next-boot:true|already-pinned:true|active-match:false) ;;
        *)
            fail_closed "helper returned invalid pin state"
            exit 1
            ;;
    esac
    case "$telemetry_status:$reboot" in
        generated-next-boot:true|staged-effect-next-boot:true|\
        restored-app-and-staged-effect:true|ready:true|ready:false|\
        restored-app:true|restored-app:false) ;;
        *)
            fail_closed "helper returned invalid telemetry proof-key state"
            exit 1
            ;;
    esac
    case "$telemetry_sha256:$telemetry_key_id" in
        *[!0-9a-f:]*|:*|*:)
            fail_closed "helper returned invalid telemetry proof-key metadata"
            exit 1
            ;;
    esac
    if [ "${#telemetry_sha256}" -ne 64 ] \
            || [ "${#telemetry_key_id}" -ne 16 ] \
            || [ "${telemetry_sha256#"$telemetry_key_id"}" = "$telemetry_sha256" ]; then
        fail_closed "helper returned inconsistent telemetry proof-key metadata"
        exit 1
    fi
    # The Java helper writes only the module backing inode. Label and verify it
    # before declaring success; Magisk exposes the copy on the next module mount.
    # shellcheck source=telemetry-key-label.sh
    if ! . "$TELEMETRY_KEY_LABEL_HELPER" \
            || ! echidna_prepare_effect_telemetry_key \
                "$MODDIR" "$telemetry_sha256" required; then
        fail_closed "effect telemetry key SELinux label contract rejected"
        exit 1
    fi
    write_status "$pin_status" \
        "verified signer, UID, dataDir, P-256 SPKI, and telemetry proof key" \
        "$reboot" "$telemetry_status" "$telemetry_sha256" "$telemetry_key_id"
    case "$telemetry_status" in
        generated-next-boot)
            log "Generated and pinned a per-install telemetry proof key for next boot" ;;
        staged-effect-next-boot|restored-app-and-staged-effect)
            log "Restored telemetry copies from the root pin; effect-host update is next-boot only" ;;
        restored-app)
            log "Restored the app-private telemetry copy from the root pin" ;;
        ready)
            log "Telemetry proof-key copies match the immutable root pin" ;;
    esac
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
