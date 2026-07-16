#!/system/bin/sh

# Shared fail-closed lifecycle for the effect-host telemetry HMAC key. The
# authoritative root pin never leaves trust/state; only its derived effect copy
# receives this narrowly readable SELinux type.
ECHIDNA_TELEMETRY_KEY_CONTEXT="u:object_r:echidna_telemetry_key_file:s0"
ECHIDNA_TELEMETRY_KEY_BYTES=32
ECHIDNA_TELEMETRY_KEY_OWNER_MODE="0:1005:440"
ECHIDNA_TELEMETRY_ROOT_OWNER_MODE="0:0:400"

_echidna_key_log() {
    echo "[echidna][telemetry-key] $1" >&2
}

_echidna_key_sha256() {
    key_path="$1"
    digest="$(sha256sum "$key_path" 2>/dev/null | awk 'NR == 1 { print $1 }')"
    case "$digest" in
        ''|*[!0-9a-f]*) return 1 ;;
    esac
    [ "${#digest}" -eq 64 ] || return 1
    printf '%s\n' "$digest"
}

_echidna_key_stat() {
    stat -c '%u:%g:%a:%d:%i' "$1" 2>/dev/null
}

_echidna_key_owner_mode() {
    full_stat="$(_echidna_key_stat "$1")" || return 1
    printf '%s\n' "${full_stat%:*:*}"
}

_echidna_key_size() {
    bytes="$(wc -c < "$1" 2>/dev/null | tr -d ' ')"
    case "$bytes" in
        ''|*[!0-9]*) return 1 ;;
        *) printf '%s\n' "$bytes" ;;
    esac
}

_echidna_key_context() {
    context_line="$(ls -Zd "$1" 2>/dev/null)" || return 1
    printf '%s\n' "${context_line%% *}"
}

_echidna_cleanup_key_temporaries() {
    key_path="$1"
    for temporary in "$key_path".tmp.*; do
        if [ "$temporary" = "$key_path.tmp.*" ]; then
            break
        fi
        if [ -L "$temporary" ] || [ -f "$temporary" ]; then
            rm -f "$temporary" || return 1
        elif [ -e "$temporary" ]; then
            return 1
        fi
    done
}

_echidna_reject_derived_key() {
    key_path="$1"
    reason="$2"
    _echidna_cleanup_key_temporaries "$key_path" 2>/dev/null || true
    if [ -L "$key_path" ] || [ -f "$key_path" ]; then
        rm -f "$key_path" 2>/dev/null || true
    fi
    _echidna_key_log "$reason; derived effect key removed"
    return 1
}

# Usage: echidna_prepare_effect_telemetry_key MODULE_DIR EXPECTED_SHA256 MODE
# MODE is "required" after trust provisioning or "optional" during post-fs.
echidna_prepare_effect_telemetry_key() (
    if [ "$#" -ne 3 ]; then
        _echidna_key_log "invalid key-label helper arguments"
        return 1
    fi
    module_dir="$1"
    expected_sha256="$2"
    presence="$3"
    case "$module_dir:$presence" in
        /*:required|/*:optional) ;;
        *)
            _echidna_key_log "module path or presence contract is invalid"
            return 1
            ;;
    esac

    root_pin="$module_dir/trust/state/preprocessor_telemetry_hmac.key"
    effect_key="$module_dir/system/etc/echidna/preprocessor_telemetry_hmac.key"
    if ! _echidna_cleanup_key_temporaries "$effect_key"; then
        _echidna_reject_derived_key "$effect_key" "unsafe temporary key residue"
        return 1
    fi
    root_present=false
    effect_present=false
    if [ -e "$root_pin" ] || [ -L "$root_pin" ]; then
        root_present=true
    fi
    if [ -e "$effect_key" ] || [ -L "$effect_key" ]; then
        effect_present=true
    fi
    if [ "$effect_present" = false ]; then
        if [ "$presence" = optional ] && [ "$root_present" = false ]; then
            return 0
        fi
        if [ "$root_present" = true ]; then
            _echidna_key_log "telemetry root pin exists without its derived effect key"
        else
            _echidna_key_log "required derived effect key is missing"
        fi
        return 1
    fi
    if [ -L "$effect_key" ] || [ ! -f "$effect_key" ]; then
        _echidna_reject_derived_key "$effect_key" "effect key is not a no-symlink regular file"
        return 1
    fi
    if [ "$root_present" = false ] || [ -L "$root_pin" ] || [ ! -f "$root_pin" ]; then
        _echidna_reject_derived_key "$effect_key" "authoritative telemetry root pin is unsafe"
        return 1
    fi
    if [ "$(_echidna_key_size "$root_pin")" != "$ECHIDNA_TELEMETRY_KEY_BYTES" ] \
            || [ "$(_echidna_key_size "$effect_key")" != "$ECHIDNA_TELEMETRY_KEY_BYTES" ]; then
        _echidna_reject_derived_key "$effect_key" "telemetry key size contract failed"
        return 1
    fi
    if [ "$(_echidna_key_owner_mode "$root_pin")" != "$ECHIDNA_TELEMETRY_ROOT_OWNER_MODE" ] \
            || [ "$(_echidna_key_owner_mode "$effect_key")" \
                != "$ECHIDNA_TELEMETRY_KEY_OWNER_MODE" ]; then
        _echidna_reject_derived_key "$effect_key" "telemetry key owner or mode drifted"
        return 1
    fi

    root_sha256="$(_echidna_key_sha256 "$root_pin")" || {
        _echidna_reject_derived_key "$effect_key" "telemetry root-pin hash is unavailable"
        return 1
    }
    effect_sha256="$(_echidna_key_sha256 "$effect_key")" || {
        _echidna_reject_derived_key "$effect_key" "effect-key hash is unavailable"
        return 1
    }
    if [ "$root_sha256" != "$effect_sha256" ]; then
        _echidna_reject_derived_key "$effect_key" "effect key does not match the root pin"
        return 1
    fi
    if [ -n "$expected_sha256" ] && [ "$root_sha256" != "$expected_sha256" ]; then
        _echidna_reject_derived_key "$effect_key" "trust-helper hash does not match the root pin"
        return 1
    fi

    before_stat="$(_echidna_key_stat "$effect_key")" || {
        _echidna_reject_derived_key "$effect_key" "effect-key inode is unavailable"
        return 1
    }
    if ! chcon "$ECHIDNA_TELEMETRY_KEY_CONTEXT" "$effect_key" 2>/dev/null; then
        _echidna_reject_derived_key "$effect_key" "effect-key SELinux label could not be applied"
        return 1
    fi
    if [ "$(_echidna_key_context "$effect_key")" != "$ECHIDNA_TELEMETRY_KEY_CONTEXT" ]; then
        _echidna_reject_derived_key "$effect_key" "effect-key SELinux label verification failed"
        return 1
    fi
    after_stat="$(_echidna_key_stat "$effect_key")" || {
        _echidna_reject_derived_key "$effect_key" "effect-key inode changed after labeling"
        return 1
    }
    after_sha256="$(_echidna_key_sha256 "$effect_key")" || {
        _echidna_reject_derived_key "$effect_key" "effect-key hash changed after labeling"
        return 1
    }
    if [ "$before_stat" != "$after_stat" ] || [ "$after_sha256" != "$root_sha256" ] \
            || [ -L "$effect_key" ] || [ ! -f "$effect_key" ]; then
        _echidna_reject_derived_key "$effect_key" "effect-key relabel drift was detected"
        return 1
    fi
    return 0
)
