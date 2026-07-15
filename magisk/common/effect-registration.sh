#!/system/bin/sh
set -eu

MODDIR="${1:?module directory is required}"
RUNTIME_DIR="${ECHIDNA_RUNTIME_DIR:-/data/adb/echidna}"
STATUS_DIR="$RUNTIME_DIR/effect-registration"
STATUS_FILE="$STATUS_DIR/status.txt"
HELPER="$MODDIR/common/echidna-trust-helper.jar"
APP_PROCESS="${ECHIDNA_APP_PROCESS:-/system/bin/app_process}"
ANDROID_ROOT_PREFIX="${ECHIDNA_ANDROID_ROOT:-}"
PROC_ROOT="${ECHIDNA_PROC_ROOT:-/proc}"
PENDING_KEY="$MODDIR/trust/next-boot/preprocessor_controller_p256.spki"
ACTIVE_KEY="$MODDIR/system/etc/echidna/preprocessor_controller_p256.spki"
STATE_DIR="$MODDIR/registration/next-boot"
METADATA="$STATE_DIR/state-v2"
LEGACY_METADATA="$MODDIR/registration/state-v1"
RESTAGE_REQUIRED="$MODDIR/registration/restage-required"

log() {
    echo "[echidna][effect-registration] $1"
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
        echo "type_uuid=c83e3db3-d4f5-5f2c-a095-8775c1edfc6d"
        echo "implementation_uuid=3e66a36e-dee9-5d81-a0d6-49fc3b863530"
        echo "auto_apply=false"
    } > "$temporary"
    chown root:root "$temporary" 2>/dev/null || true
    chmod 0600 "$temporary"
    mv -f "$temporary" "$STATUS_FILE"
}

fail_closed() {
    reason="$1"
    write_status "ineligible" "$reason" "false"
    log "Registration remains off: $reason"
    return 1
}

require_restage() {
    reason="$1"
    mkdir -p "$MODDIR/registration"
    temporary="$RESTAGE_REQUIRED.tmp.$$"
    umask 077
    printf '%s\n' "$reason" > "$temporary"
    chown root:root "$temporary" 2>/dev/null || true
    chmod 0600 "$temporary"
    mv -f "$temporary" "$RESTAGE_REQUIRED"
    write_status "restage-required" "$reason" "true"
    log "Registration requires explicit reinstall/restage: $reason"
    log "The stock effect config will remain active until verified state is rebuilt."
    return 1
}

collect_factory_evidence() {
    if [ -n "${ECHIDNA_EFFECT_FACTORY_EVIDENCE:-}" ]; then
        cat "$ECHIDNA_EFFECT_FACTORY_EVIDENCE"
        return
    fi
    service list 2>/dev/null || true
    if command -v lshal >/dev/null 2>&1; then
        lshal list -ip 2>/dev/null || lshal -ip 2>/dev/null || true
    fi
}

proc_start_time() {
    pid="$1"
    line="$(tr -d '\r' < "$PROC_ROOT/$pid/stat" 2>/dev/null || true)"
    case "$line" in
        *') '*) ;;
        *) return 1 ;;
    esac
    remainder="${line##*) }"
    start_time="$(printf '%s\n' "$remainder" | awk '{print $20}')"
    case "$start_time" in
        ''|*[!0-9]*) return 1 ;;
    esac
    printf '%s\n' "$start_time"
}

verify_factory_process() {
    pid="$1"
    case "$pid" in
        ''|*[!0-9]*|0|1) return 1 ;;
    esac
    process="$PROC_ROOT/$pid"
    [ -r "$process/exe" ] && [ -r "$process/maps" ] && [ -r "$process/stat" ] || return 1
    before="$(proc_start_time "$pid")" || return 1
    if [ "${ECHIDNA_TEST_ALLOW_PROC_EXE:-0}" != 1 ] && [ ! -L "$process/exe" ]; then
        return 1
    fi
    resolved_exe="$(readlink -f "$process/exe" 2>/dev/null || true)"
    [ -n "$resolved_exe" ] || return 1
    if [ "${ECHIDNA_TEST_ALLOW_PROC_EXE:-0}" != 1 ]; then
        case "$resolved_exe" in
            /system/*|/vendor/*|/odm/*|/apex/*) ;;
            *) return 1 ;;
        esac
    fi
    grep -F "$resolved_exe" "$process/maps" >/dev/null 2>&1 || return 1
    magic="$(od -An -t x1 -N 4 "$process/exe" 2>/dev/null | tr -d ' \r\n')"
    [ "$magic" = 7f454c46 ] || return 1
    elf_class="$(od -An -t u1 -j 4 -N 1 "$process/exe" 2>/dev/null | tr -d ' ')"
    byte_order="$(od -An -t u1 -j 5 -N 1 "$process/exe" 2>/dev/null | tr -d ' ')"
    [ "$byte_order" = 1 ] || return 1
    machine_bytes="$(od -An -t u1 -j 18 -N 2 "$process/exe" 2>/dev/null)"
    machine_low="$(printf '%s\n' "$machine_bytes" | awk '{print $1}')"
    machine_high="$(printf '%s\n' "$machine_bytes" | awk '{print $2}')"
    case "$machine_low:$machine_high" in
        *[!0-9:]*|:*|*:) return 1 ;;
    esac
    machine=$((machine_low + (machine_high * 256)))
    case "$elf_class" in
        1) bits=32 ;;
        2) bits=64 ;;
        *) return 1 ;;
    esac
    resolved_after="$(readlink -f "$process/exe" 2>/dev/null || true)"
    [ "$resolved_after" = "$resolved_exe" ] || return 1
    grep -F "$resolved_exe" "$process/maps" >/dev/null 2>&1 || return 1
    after="$(proc_start_time "$pid")" || return 1
    [ "$before" = "$after" ] || return 1
    printf '%s|%s\n' "$bits" "$machine"
}

device_supports_abi() {
    abi="$1"
    abilist="$(getprop ro.product.cpu.abilist 2>/dev/null || true)"
    primary="$(getprop ro.product.cpu.abi 2>/dev/null || true)"
    case ",$primary,$abilist," in
        *,"$abi",*) return 0 ;;
        *) return 1 ;;
    esac
}

select_config() {
    sdk="$(getprop ro.build.version.sdk 2>/dev/null || true)"
    sku="$(getprop ro.boot.product.vendor.sku 2>/dev/null || true)"
    case "$sku" in
        *[!A-Za-z0-9_.-]*) sku="" ;;
    esac
    for format in xml conf; do
        if [ "$format" = xml ]; then
            filename=audio_effects.xml
        else
            filename=audio_effects.conf
        fi
        if [ -r "$ANDROID_ROOT_PREFIX/odm/etc/$filename" ]; then
            echo "unsupported|odm|$format|/odm/etc/$filename"
            return
        fi
        if [ "$format" = xml ] && [ -n "$sku" ] && [ "${sdk:-0}" -ge 30 ] 2>/dev/null \
                && [ -r "$ANDROID_ROOT_PREFIX/vendor/etc/audio/sku_$sku/$filename" ]; then
            echo "supported|vendor|$format|/vendor/etc/audio/sku_$sku/$filename"
            return
        fi
        if [ -r "$ANDROID_ROOT_PREFIX/vendor/etc/$filename" ]; then
            echo "supported|vendor|$format|/vendor/etc/$filename"
            return
        fi
        if [ -r "$ANDROID_ROOT_PREFIX/system/etc/$filename" ]; then
            echo "supported|system|$format|/system/etc/$filename"
            return
        fi
    done
    echo "missing|||"
}

parse_config_selection() {
    candidate="$1"
    eligibility="${candidate%%\|*}"
    [ "$eligibility" != "$candidate" ] || return 1
    remainder="${candidate#*\|}"
    partition="${remainder%%\|*}"
    [ "$partition" != "$remainder" ] || return 1
    remainder="${remainder#*\|}"
    format="${remainder%%\|*}"
    [ "$format" != "$remainder" ] || return 1
    source_config="${remainder#*\|}"
    [ "$source_config" != "$remainder" ] || return 1
    case "$source_config" in
        *'|'*) return 1 ;;
    esac
    return 0
}

for required in "$HELPER" "$PENDING_KEY"; do
    if [ ! -f "$required" ] || [ -L "$required" ]; then
        fail_closed "required verified payload is missing or unsafe: $required"
        exit 1
    fi
done
if [ ! -x "$APP_PROCESS" ]; then
    fail_closed "app_process is unavailable: $APP_PROCESS"
    exit 1
fi
if [ -e "$RESTAGE_REQUIRED" ]; then
    fail_closed "registration state is marked for explicit reinstall/restage"
    exit 1
fi
if [ -e "$LEGACY_METADATA" ]; then
    require_restage "legacy v1 registration state cannot be activated safely"
    exit 1
fi

evidence="$(collect_factory_evidence | tr -d '\r')"
has_aidl=false
if printf '%s\n' "$evidence" \
        | grep -Fq 'android.hardware.audio.effect.IFactory/default'; then
    has_aidl=true
fi
factory_pids="$(printf '%s\n' "$evidence" | awk '
    $1 ~ /^android[.]hardware[.]audio[.]effect@[2-7][.][0-9]+::IEffectsFactory\/default$/ \
            && $2 ~ /^[0-9]+$/ && !seen[$2]++ { print $2 }
')"
factory_count="$(printf '%s\n' "$factory_pids" | awk 'NF { count++ } END { print count + 0 }')"
if [ "$has_aidl" = true ] && [ "$factory_count" -gt 0 ]; then
    fail_closed "both Stable AIDL and legacy HIDL effect factories are visible; active factory is ambiguous"
    exit 1
fi
if [ "$factory_count" -eq 0 ]; then
    if [ "$has_aidl" = true ]; then
        fail_closed "Stable-AIDL-only effect factory is not compatible with the legacy AELI library"
    else
        fail_closed "no registered legacy HIDL IEffectsFactory/default PID was found"
    fi
    exit 1
fi
if [ "$factory_count" -ne 1 ]; then
    fail_closed "multiple registered legacy HIDL factory PIDs are visible; active host is ambiguous"
    exit 1
fi
factory_pid="$(printf '%s\n' "$factory_pids" | awk 'NF { print; exit }')"

host_identity="$(verify_factory_process "$factory_pid" || true)"
case "$host_identity" in
    64\|183) host_bits=64; abi=arm64-v8a ;;
    32\|40) host_bits=32; abi=armeabi-v7a ;;
    64\|62) host_bits=64; abi=x86_64 ;;
    *)
        fail_closed "registered legacy effect factory PID identity/ELF ABI could not be proven"
        exit 1
        ;;
esac
if ! device_supports_abi "$abi"; then
    fail_closed "registered effect host ABI is absent from device ABI properties: $abi"
    exit 1
fi

selection="$(select_config)"
if [ "${ECHIDNA_TEST_CONFIG_SELECTION+x}" = x ] \
        && [ "${ECHIDNA_TEST_ALLOW_PROC_EXE:-0}" = 1 ]; then
    selection="$ECHIDNA_TEST_CONFIG_SELECTION"
fi
if ! parse_config_selection "$selection"; then
    fail_closed "effect config selection is malformed"
    exit 1
fi
case "$eligibility" in
    supported) ;;
    unsupported)
        fail_closed "active effect config is on unsupported partition: $source_config"
        exit 1
        ;;
    *)
        fail_closed "no readable audio_effects.xml or legacy audio_effects.conf was found"
        exit 1
        ;;
esac

fingerprint="${ECHIDNA_BUILD_FINGERPRINT:-$(getprop ro.build.fingerprint 2>/dev/null || true)}"
if [ -z "$fingerprint" ]; then
    fail_closed "build fingerprint is unavailable; OTA drift cannot be guarded"
    exit 1
fi
library_source="$MODDIR/preproc/$abi/libechidna_preproc.so"
if [ ! -f "$library_source" ] || [ -L "$library_source" ]; then
    fail_closed "preprocessor payload is missing for active host ABI: $abi"
    exit 1
fi
overlay_base="$MODDIR/system"
if [ "$partition" = vendor ]; then
    overlay_base="$MODDIR/system/vendor"
fi
inert_config="$STATE_DIR/config/$partition${source_config#/"$partition"}"
transient_config="$overlay_base${source_config#/"$partition"}"
lib_suffix=""
if [ "$host_bits" = 64 ]; then
    lib_suffix=64
fi
library_output="$overlay_base/lib$lib_suffix/soundfx/libechidna_preproc.so"

set +e
output="$(ANDROID_DATA=/data CLASSPATH="$HELPER" "$APP_PROCESS" /system/bin \
        com.echidna.magisk.EffectRegistrationMain \
        --source "$source_config" \
        --format "$format" \
        --partition "$partition" \
        --bits "$host_bits" \
        --abi "$abi" \
        --library-source "$library_source" \
        --inert-config "$inert_config" \
        --transient-config "$transient_config" \
        --library-output "$library_output" \
        --metadata "$METADATA" \
        --pending-key "$PENDING_KEY" \
        --active-key "$ACTIVE_KEY" \
        --fingerprint "$fingerprint" 2>&1)"
result=$?
set -e
if [ "$result" -eq 3 ]; then
    detail="$(printf '%s\n' "$output" | tail -n 1 | tr -d '\r' | cut -c1-240)"
    require_restage "$detail"
    exit 1
fi
if [ "$result" -ne 0 ]; then
    detail="$(printf '%s\n' "$output" | tail -n 1 | tr -d '\r' | cut -c1-240)"
    fail_closed "registration helper rejected staging: $detail"
    exit 1
fi
case "$output" in
    *ECHIDNA_EFFECT_REGISTRATION_V2*status=staged-next-boot*|\
    *ECHIDNA_EFFECT_REGISTRATION_V2*status=already-staged*) ;;
    *)
        fail_closed "registration helper returned an unrecognized success response"
        exit 1
        ;;
esac
write_status "staged-next-boot" \
    "registered HIDL factory PID and inert registry/library/key verified" "true"
log "Default-off effect registration staged for next boot; no session attachment or auto-apply was added"
exit 0
