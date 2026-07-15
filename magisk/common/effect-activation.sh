#!/system/bin/sh
set -eu

MODDIR="${1:?module directory is required}"
ACTION="${2:-activate}"
RUNTIME_DIR="${ECHIDNA_RUNTIME_DIR:-/data/adb/echidna}"
ANDROID_ROOT_PREFIX="${ECHIDNA_ANDROID_ROOT:-}"
STATUS_DIR="$RUNTIME_DIR/effect-registration"
STATUS_FILE="$STATUS_DIR/activation-status.txt"
STATE_DIR="$MODDIR/registration/next-boot"
METADATA="$STATE_DIR/state-v2"
RESTAGE_REQUIRED="$MODDIR/registration/restage-required"
TYPE_UUID="c83e3db3-d4f5-5f2c-a095-8775c1edfc6d"
IMPLEMENTATION_UUID="3e66a36e-dee9-5d81-a0d6-49fc3b863530"

log() {
    echo "[echidna][effect-activation] $1"
}

write_status() {
    state="$1"
    reason="$2"
    mkdir -p "$STATUS_DIR"
    chmod 0700 "$STATUS_DIR" 2>/dev/null || true
    status_temporary="$STATUS_FILE.tmp.$$"
    umask 077
    {
        echo "state=$state"
        echo "reason=$reason"
        echo "type_uuid=$TYPE_UUID"
        echo "implementation_uuid=$IMPLEMENTATION_UUID"
        echo "auto_apply=false"
    } > "$status_temporary"
    chown root:root "$status_temporary" 2>/dev/null || true
    chmod 0600 "$status_temporary"
    mv -f "$status_temporary" "$STATUS_FILE"
}

cleanup_transient_configs() {
    for candidate in \
        "$MODDIR/system/etc/audio_effects.xml" \
        "$MODDIR/system/etc/audio_effects.conf" \
        "$MODDIR/system/vendor/etc/audio_effects.xml" \
        "$MODDIR/system/vendor/etc/audio_effects.conf" \
        "$MODDIR/system/vendor/etc/audio"/sku_*/audio_effects.xml; do
        if [ -e "$candidate" ] || [ -L "$candidate" ]; then
            rm -f "$candidate" 2>/dev/null || return 1
        fi
    done
}

fail_closed() {
    reason="$1"
    cleanup_transient_configs 2>/dev/null || true
    write_status "not-activated" "$reason"
    log "Stock effect config retained for this boot: $reason"
    return 1
}

metadata_value() {
    key="$1"
    count="$(grep -c "^$key=" "$METADATA" 2>/dev/null || true)"
    [ "$count" = 1 ] || return 1
    grep "^$key=" "$METADATA" | head -n 1 | cut -d= -f2-
}

sha256_file() {
    sha256sum "$1" 2>/dev/null | awk '{print $1}'
}

safe_root_file() {
    path="$1"
    expected_mode="$2"
    [ -f "$path" ] && [ ! -L "$path" ] || return 1
    if [ "${ECHIDNA_TEST_ALLOW_NONROOT:-0}" = 1 ]; then
        return 0
    fi
    owner="$(stat -c '%u' "$path" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$path" 2>/dev/null || true)"
    [ "$owner" = 0 ] && [ "$mode" = "$expected_mode" ]
}

safe_stock_file() {
    path="$1"
    [ -f "$path" ] && [ ! -L "$path" ] || return 1
    if [ "${ECHIDNA_TEST_ALLOW_NONROOT:-0}" = 1 ]; then
        return 0
    fi
    owner="$(stat -c '%u' "$path" 2>/dev/null || true)"
    mode="$(stat -c '%a' "$path" 2>/dev/null || true)"
    [ "$owner" = 0 ] || return 1
    case "$mode" in
        *[2367]?|*?[2367]) return 1 ;;
    esac
}

case "$ACTION" in
    cleanup)
        if cleanup_transient_configs; then
            write_status "backing-cleaned" \
                "transient config backing removed; activation must revalidate"
            log "Transient effect-config backing removed; activation must revalidate"
            exit 0
        fi
        fail_closed "unable to remove transient config backing"
        exit 1
        ;;
    activate) ;;
    *)
        fail_closed "unknown activation action: $ACTION"
        exit 1
        ;;
esac

# Magisk runs module post-fs-data scripts before mounting any module system tree.
# Always remove an interrupted/prior-boot activation before reading current stock state.
if ! cleanup_transient_configs; then
    fail_closed "unable to remove prior transient config"
    exit 1
fi
if [ -e "$RESTAGE_REQUIRED" ]; then
    fail_closed "registration state requires explicit reinstall/restage"
    exit 1
fi
if ! safe_root_file "$METADATA" 444; then
    fail_closed "root-owned mode-0444 v2 metadata is absent or unsafe"
    exit 1
fi

version="$(metadata_value version 2>/dev/null || true)"
partition="$(metadata_value partition 2>/dev/null || true)"
format="$(metadata_value format 2>/dev/null || true)"
source_path="$(metadata_value source_path 2>/dev/null || true)"
inert_config="$(metadata_value inert_config 2>/dev/null || true)"
transient_config="$(metadata_value transient_config 2>/dev/null || true)"
library_output="$(metadata_value library_output 2>/dev/null || true)"
active_key="$(metadata_value active_key 2>/dev/null || true)"
source_hash="$(metadata_value source_sha256 2>/dev/null || true)"
overlay_hash="$(metadata_value overlay_sha256 2>/dev/null || true)"
library_hash="$(metadata_value library_sha256 2>/dev/null || true)"
key_hash="$(metadata_value key_sha256 2>/dev/null || true)"
fingerprint="$(metadata_value fingerprint 2>/dev/null || true)"
type_uuid="$(metadata_value type_uuid 2>/dev/null || true)"
implementation_uuid="$(metadata_value implementation_uuid 2>/dev/null || true)"
auto_apply="$(metadata_value auto_apply 2>/dev/null || true)"

case "$version:$partition:$format:$type_uuid:$implementation_uuid:$auto_apply" in
    2:system:xml:"$TYPE_UUID":"$IMPLEMENTATION_UUID":false|\
    2:system:conf:"$TYPE_UUID":"$IMPLEMENTATION_UUID":false|\
    2:vendor:xml:"$TYPE_UUID":"$IMPLEMENTATION_UUID":false|\
    2:vendor:conf:"$TYPE_UUID":"$IMPLEMENTATION_UUID":false) ;;
    *)
        fail_closed "registration metadata contract mismatch"
        exit 1
        ;;
esac
case "$source_hash:$overlay_hash:$library_hash:$key_hash" in
    *[!0-9a-f:]*|*::*|:*|*:)
        fail_closed "registration metadata contains invalid digests"
        exit 1
        ;;
esac
for digest in "$source_hash" "$overlay_hash" "$library_hash" "$key_hash"; do
    if [ "${#digest}" -ne 64 ]; then
        fail_closed "registration metadata contains invalid digest length"
        exit 1
    fi
done

case "$partition:$format" in
    system:xml) expected_source="/system/etc/audio_effects.xml" ;;
    system:conf) expected_source="/system/etc/audio_effects.conf" ;;
    vendor:xml)
        case "$source_path" in
            /vendor/etc/audio_effects.xml|/vendor/etc/audio/sku_*/audio_effects.xml)
                expected_source="$source_path" ;;
            *) expected_source="" ;;
        esac
        ;;
    vendor:conf) expected_source="/vendor/etc/audio_effects.conf" ;;
esac
if [ -z "${expected_source:-}" ] || [ "$source_path" != "$expected_source" ]; then
    fail_closed "registration source path is not supported"
    exit 1
fi
source_suffix="${source_path#/"$partition"}"
if [ "$partition" = system ]; then
    expected_transient="$MODDIR/system$source_suffix"
    expected_library_prefix="$MODDIR/system/lib"
else
    expected_transient="$MODDIR/system/vendor$source_suffix"
    expected_library_prefix="$MODDIR/system/vendor/lib"
fi
expected_inert="$STATE_DIR/config/$partition$source_suffix"
case "$library_output" in
    "$expected_library_prefix"/soundfx/libechidna_preproc.so|\
    "$expected_library_prefix"64/soundfx/libechidna_preproc.so) ;;
    *)
        fail_closed "registration library output path mismatch"
        exit 1
        ;;
esac
if [ "$transient_config" != "$expected_transient" ] \
        || [ "$inert_config" != "$expected_inert" ] \
        || [ "$active_key" != \
            "$MODDIR/system/etc/echidna/preprocessor_controller_p256.spki" ]; then
    fail_closed "registration activation paths mismatch"
    exit 1
fi

current_fingerprint="${ECHIDNA_BUILD_FINGERPRINT:-$(getprop ro.build.fingerprint 2>/dev/null || true)}"
if [ -z "$current_fingerprint" ] || [ "$current_fingerprint" != "$fingerprint" ]; then
    fail_closed "build fingerprint changed; explicit restage required"
    exit 1
fi
stock_source="$ANDROID_ROOT_PREFIX$source_path"
if ! safe_root_file "$inert_config" 644 \
        || ! safe_root_file "$library_output" 644 \
        || ! safe_root_file "$active_key" 444 \
        || ! safe_stock_file "$stock_source"; then
    fail_closed "activation input is missing, symlinked, or has unsafe ownership/mode"
    exit 1
fi
if [ "$(sha256_file "$stock_source")" != "$source_hash" ]; then
    fail_closed "stock effect config hash changed; explicit restage required"
    exit 1
fi
if [ "$(sha256_file "$inert_config")" != "$overlay_hash" ] \
        || [ "$(sha256_file "$library_output")" != "$library_hash" ] \
        || [ "$(sha256_file "$active_key")" != "$key_hash" ]; then
    fail_closed "staged config/library/key hash mismatch"
    exit 1
fi

parent="${transient_config%/*}"
config_temporary="$transient_config.tmp.$$"
activated=false
rollback() {
    rm -f "$config_temporary" 2>/dev/null || true
    if [ "$activated" != true ]; then
        rm -f "$transient_config" 2>/dev/null || true
    fi
}
trap rollback 0
trap 'rollback; exit 1' HUP INT TERM
mkdir -p "$parent"
if [ -L "$parent" ] || ! cp "$inert_config" "$config_temporary"; then
    fail_closed "unable to copy verified config into transient activation path"
    exit 1
fi
chown root:root "$config_temporary" 2>/dev/null || true
chmod 0644 "$config_temporary"
if [ "$(sha256_file "$config_temporary")" != "$overlay_hash" ]; then
    fail_closed "transient config changed during copy"
    exit 1
fi
mv -f "$config_temporary" "$transient_config"
activated=true
trap - 0 HUP INT TERM
write_status "approved-for-magisk-mount" \
    "fingerprint/source/config/library/key verified before module mounts"
log "Verified transient config prepared for Magisk's subsequent mount phase"
exit 0
