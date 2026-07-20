#!/system/bin/sh
# Shared Magisk/Zygisk status probe for installer and boot scripts.
#
# `magisk --zygisk` is not a valid Magisk applet. Zygisk's authoritative state
# is stored in Magisk's settings table (value=1 means enabled), exposed through
# the stable `magisk --sqlite` interface on releases that support Zygisk.
#
# That settings row only describes Magisk's *built-in* Zygisk. Standalone
# implementations (ReZygisk, Zygisk Next) ship as ordinary Magisk modules and
# their own install documentation tells Magisk users to turn the built-in
# Zygisk off because the two conflict. So on a correctly configured standalone
# device the sqlite row reads 0 while Zygisk is in fact provided — querying
# sqlite alone made this helper report "disabled" there and made the installer
# advise a change that those projects say breaks them. The module tree is
# therefore scanned as a second source of truth.
#
# The identifiers below are the same ones STANDALONE_ZYGISK_PROBE uses in
# android/control-service/service/src/main/kotlin/com/echidna/control/service/PrivilegedController.kt.
# They deliberately duplicate that list because the app cannot source shell
# helpers out of the module tree; tools/tests/test_magisk_contracts.py asserts
# the two stay identical so shell and app cannot silently disagree again.
#
# echidna_zygisk_enabled keeps its return-code contract:
#   0 = a Zygisk implementation is active
#   1 = none found
#   2 = state cannot be determined (no `magisk` binary or the query failed) and
#       no standalone module was found to answer the question anyway
# It also sets ECHIDNA_ZYGISK_IMPL to `magisk-builtin`, `standalone` or the
# empty string so callers can word their advice for what is actually present.

# Production location of the Magisk module tree. The override exists only so
# the tests can aim the scan at a fixture tree; nothing shipped in the module
# sets it, so devices always get the hardcoded default.
ECHIDNA_ZYGISK_MODULES_DIR="${ECHIDNA_ZYGISK_MODULES_DIR:-/data/adb/modules}"

echidna_zygisk_builtin_enabled() {
    command -v magisk >/dev/null 2>&1 || return 2
    zygisk_rows="$(
        magisk --sqlite "SELECT value FROM settings WHERE key='zygisk'" 2>/dev/null
    )" || return 2

    # Match a complete output row. Wrapping with newlines prevents values such
    # as 10 from being accepted as enabled.
    case "
${zygisk_rows}
" in
        *'
value=1
'*) return 0 ;;
        *) return 1 ;;
    esac
}

# Looks for an installed standalone Zygisk implementation. A module directory
# carrying Magisk's `disable` marker stays on disk but is not loaded at boot,
# so it provides no Zygisk and must not count as a detection.
echidna_zygisk_standalone_present() {
    for zygisk_prop in "$ECHIDNA_ZYGISK_MODULES_DIR"/*/module.prop; do
        [ -f "$zygisk_prop" ] || continue
        zygisk_module_dir="${zygisk_prop%/module.prop}"
        if [ -e "$zygisk_module_dir/disable" ]; then
            continue
        fi
        if grep -Eiq '^id=(zygisksu|rezygisk)' "$zygisk_prop"; then
            return 0
        fi
        if grep -Eiq '^name=.*(Zygisk Next|ReZygisk)' "$zygisk_prop"; then
            return 0
        fi
        if grep -Eiq '^description=.*Standalone implementation of Zygisk' "$zygisk_prop"; then
            return 0
        fi
    done
    return 1
}

echidna_zygisk_enabled() {
    ECHIDNA_ZYGISK_IMPL=""

    zygisk_builtin_status=0
    echidna_zygisk_builtin_enabled || zygisk_builtin_status=$?
    if [ "$zygisk_builtin_status" -eq 0 ]; then
        ECHIDNA_ZYGISK_IMPL="magisk-builtin"
        return 0
    fi

    if echidna_zygisk_standalone_present; then
        ECHIDNA_ZYGISK_IMPL="standalone"
        return 0
    fi

    # Nothing standalone either, so an unreadable built-in state stays
    # "cannot determine" instead of being downgraded to a confident "no".
    if [ "$zygisk_builtin_status" -eq 2 ]; then
        return 2
    fi
    return 1
}
