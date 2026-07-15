#!/system/bin/sh
# Shared Magisk/Zygisk status probe for installer and boot scripts.
#
# `magisk --zygisk` is not a valid Magisk applet. Zygisk's authoritative state
# is stored in Magisk's settings table (value=1 means enabled), exposed through
# the stable `magisk --sqlite` interface on releases that support Zygisk.

echidna_zygisk_enabled() {
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
