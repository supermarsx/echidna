#!/system/bin/sh
set -eu

MODDIR="${1:?module directory is required}"
RUNTIME_DIR="${ECHIDNA_RUNTIME_DIR:-/data/adb/echidna}"
STATUS_DIR="$RUNTIME_DIR/effect-registration"
STATUS_FILE="$STATUS_DIR/status.txt"
HELPER="$MODDIR/common/echidna-trust-helper.jar"
APP_PROCESS="${ECHIDNA_APP_PROCESS:-/system/bin/app_process}"
ANDROID_ROOT_PREFIX="${ECHIDNA_ANDROID_ROOT:-}"
PENDING_KEY="$MODDIR/trust/next-boot/preprocessor_controller_p256.spki"
ACTIVE_KEY="$MODDIR/system/etc/echidna/preprocessor_controller_p256.spki"
METADATA="$MODDIR/registration/state-v1"

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

disable_stale_overlay() {
    reason="$1"
    write_status "disabled-drift" "$reason" "true"
    touch "$MODDIR/disable" 2>/dev/null || true
    log "Stale registration detected; module disabled for next boot: $reason"
    log "Recovery: inspect $STATUS_FILE, update/reinstall the module, then remove the disable marker."
    return 1
}

collect_factory_evidence() {
    if [ -n "${ECHIDNA_EFFECT_FACTORY_EVIDENCE:-}" ]; then
        cat "$ECHIDNA_EFFECT_FACTORY_EVIDENCE"
        return
    fi
    service list 2>/dev/null || true
    lshal 2>/dev/null || true
    for directory in /odm/etc/vintf /vendor/etc/vintf /system/etc/vintf; do
        [ -d "$directory" ] || continue
        for manifest in "$directory"/*.xml "$directory"/manifest/*.xml; do
            [ -f "$manifest" ] || continue
            if grep -q 'android.hardware.audio.effect' "$manifest" 2>/dev/null; then
                cat "$manifest" 2>/dev/null || true
                if grep -q 'format="hidl"' "$manifest" 2>/dev/null \
                        && grep -q '<name>IEffectsFactory</name>' "$manifest" 2>/dev/null \
                        && grep -q '<instance>default</instance>' "$manifest" 2>/dev/null; then
                    echo 'android.hardware.audio.effect@manifest::IEffectsFactory/default'
                fi
                if grep -q 'format="aidl"' "$manifest" 2>/dev/null \
                        && grep -q 'IFactory/default' "$manifest" 2>/dev/null; then
                    echo 'android.hardware.audio.effect.IFactory/default'
                fi
            fi
        done
    done
}

detect_host_bits() {
    if [ -n "${ECHIDNA_EFFECT_HOST_BITS:-}" ]; then
        echo "$ECHIDNA_EFFECT_HOST_BITS"
        return
    fi
    found=""
    for process in /proc/[0-9]*; do
        [ -r "$process/cmdline" ] && [ -r "$process/exe" ] || continue
        command_line="$(tr '\000' ' ' < "$process/cmdline" 2>/dev/null || true)"
        case "$command_line" in
            *android.hardware.audio.effect@*service*) ;;
            *) continue ;;
        esac
        elf_class="$(od -An -t u1 -j 4 -N 1 "$process/exe" 2>/dev/null | tr -d ' ')"
        case "$elf_class" in
            1) bits=32 ;;
            2) bits=64 ;;
            *) continue ;;
        esac
        if [ -n "$found" ] && [ "$found" != "$bits" ]; then
            echo ambiguous
            return
        fi
        found="$bits"
    done
    echo "$found"
}

detect_abi() {
    bits="$1"
    abilist="$(getprop ro.product.cpu.abilist 2>/dev/null || true)"
    primary="$(getprop ro.product.cpu.abi 2>/dev/null || true)"
    combined="$primary,$abilist"
    if [ "$bits" = 32 ]; then
        case "$combined" in
            *armeabi-v7a*) echo armeabi-v7a ;;
            *) echo unsupported ;;
        esac
        return
    fi
    case "$combined" in
        *arm64-v8a*) echo arm64-v8a ;;
        *x86_64*) echo x86_64 ;;
        *) echo unsupported ;;
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

evidence="$(collect_factory_evidence)"
has_hidl=false
has_aidl=false
if printf '%s\n' "$evidence" \
        | grep -Eq 'android\.hardware\.audio\.effect@([2-7]\.[0-9]+|manifest)::IEffectsFactory/default'; then
    has_hidl=true
fi
if printf '%s\n' "$evidence" \
        | grep -Fq 'android.hardware.audio.effect.IFactory/default'; then
    has_aidl=true
fi
if [ "$has_aidl" = true ] && [ "$has_hidl" = false ]; then
    fail_closed "Stable-AIDL-only effect factory is not compatible with the legacy AELI library"
    exit 1
fi
if [ "$has_aidl" = true ] && [ "$has_hidl" = true ]; then
    fail_closed "both Stable AIDL and legacy HIDL effect factories are visible; active factory is ambiguous"
    exit 1
fi
if [ "$has_hidl" = false ]; then
    fail_closed "no eligible legacy HIDL IEffectsFactory/default was found"
    exit 1
fi

host_bits="$(detect_host_bits)"
case "$host_bits" in
    32|64) ;;
    ambiguous)
        fail_closed "legacy effect factory host bitness is ambiguous"
        exit 1
        ;;
    *)
        fail_closed "legacy effect factory host bitness could not be proven"
        exit 1
        ;;
esac
abi="${ECHIDNA_EFFECT_ABI:-$(detect_abi "$host_bits")}"
case "$abi:$host_bits" in
    arm64-v8a:64|armeabi-v7a:32|x86_64:64) ;;
    *)
        fail_closed "unsupported or mismatched effect host ABI: $abi/$host_bits"
        exit 1
        ;;
esac

selection="$(select_config)"
eligibility="${selection%%|*}"
remainder="${selection#*|}"
partition="${remainder%%|*}"
remainder="${remainder#*|}"
format="${remainder%%|*}"
source_config="${remainder#*|}"
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
config_output="$overlay_base${source_config#/$partition}"
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
        --config-output "$config_output" \
        --library-output "$library_output" \
        --metadata "$METADATA" \
        --pending-key "$PENDING_KEY" \
        --active-key "$ACTIVE_KEY" \
        --fingerprint "$fingerprint" 2>&1)"
result=$?
set -e
if [ "$result" -eq 3 ]; then
    detail="$(printf '%s\n' "$output" | tail -n 1 | tr -d '\r' | cut -c1-240)"
    disable_stale_overlay "$detail"
    exit 1
fi
if [ "$result" -ne 0 ]; then
    detail="$(printf '%s\n' "$output" | tail -n 1 | tr -d '\r' | cut -c1-240)"
    fail_closed "registration helper rejected staging: $detail"
    exit 1
fi
case "$output" in
    *ECHIDNA_EFFECT_REGISTRATION_V1*status=staged-next-boot*|\
    *ECHIDNA_EFFECT_REGISTRATION_V1*status=already-staged*) ;;
    *)
        fail_closed "registration helper returned an unrecognized success response"
        exit 1
        ;;
esac
write_status "staged-next-boot" \
    "eligible HIDL factory; exact registry/library/key overlay verified" "true"
log "Default-off effect registration staged for next boot; no session attachment or auto-apply was added"
exit 0
