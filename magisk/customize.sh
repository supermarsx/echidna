#!/system/bin/sh
###############################################################################
# Echidna Magisk/Zygisk module — install-time customization
#
# Sourced by Magisk's install_module (SKIPUNZIP=0): the whole zip is already
# extracted to $MODPATH and the following are provided by util_functions.sh:
#   $MODPATH $ARCH $IS64BIT $API $MAGISK_VER_CODE ui_print abort set_perm*
#
# Layout shipped in the zip:
#   zygisk/<abi>.so            Zygisk engine (libechidna.so), one per ABI — Magisk
#                              loads the ABI matching each hooked process.
#   libs/<abi>/libech_dsp.so   Per-ABI DSP engine, staged; the correct arch is
#                              placed into system/lib(64) below then staging removed.
#   lib/libechidna.so          Populated here with the device-primary-ABI engine so
#                              the in-app control service JNI can dlopen it from
#                              /data/adb/modules/echidna/lib (see echidna_control_jni.cpp).
###############################################################################

SKIPUNZIP=0

ui_print "*******************************"
ui_print "   Echidna Zygisk module"
ui_print "*******************************"
ui_print "! ⚠️  DANGER: experimental root audio module."
ui_print "! ⚠️  Device compatibility is not guaranteed; bootloops or bricks are possible."
ui_print "! ⚠️  You are responsible for recovery. The software is provided as-is."
ui_print "! ⚠️  Do not continue unless you can disable Magisk modules from recovery/adb/safe mode."

WARNINGS=0
ZYGISK_STATUS_HELPER="$MODPATH/common/zygisk-status.sh"
TRUST_BOOTSTRAP_HELPER="$MODPATH/common/trust-bootstrap.sh"
TRUST_DEX_HELPER="$MODPATH/common/echidna-trust-helper.jar"
TRUST_DIGEST="$MODPATH/common/release-cert-sha256"
TRUST_MODE="$MODPATH/common/trust-mode"

compat_warn() {
  WARNINGS=$((WARNINGS + 1))
  ui_print "! Compat warning: $1"
}

compat_info() {
  ui_print "- Compat: $1"
}

prop() {
  getprop "$1" 2>/dev/null || echo ""
}

collect_abis() {
  abis=""
  for key in \
      ro.product.cpu.abilist \
      ro.system.product.cpu.abilist \
      ro.vendor.product.cpu.abilist \
      ro.odm.product.cpu.abilist; do
    value="$(prop "$key")"
    [ -n "$value" ] || continue
    if [ -n "$abis" ]; then
      abis="$abis,$value"
    else
      abis="$value"
    fi
  done
  echo "$abis"
}

lower() {
  echo "$1" | tr '[:upper:]' '[:lower:]'
}

require_payload() {
  if [ ! -f "$1" ]; then
    abort "! Missing required module payload: $1"
  fi
}

optional_payload() {
  if [ ! -f "$1" ]; then
    compat_warn "optional payload missing: $1"
  fi
}

has_system_library() {
  name="$1"
  for dir in /system/lib64 /system/lib /system_ext/lib64 /system_ext/lib \
      /vendor/lib64 /vendor/lib /odm/lib64 /odm/lib /product/lib64 /product/lib; do
    if [ -f "$dir/$name" ]; then
      return 0
    fi
  done
  return 1
}

package_installed() {
  package="$1"
  if command -v pm >/dev/null 2>&1; then
    pm path "$package" >/dev/null 2>&1
    return $?
  fi
  return 1
}

print_bridge_compat_report() {
  if [ -d /data/adb/modules/echidna-control ]; then
    compat_warn "old echidna-control module is installed; remove the stale split module"
  fi
  if [ -d /data/adb/modules/echidna ] && [ ! -f /data/adb/modules/echidna/module.prop ]; then
    compat_warn "existing echidna module directory has no module.prop; reinstall state may be incomplete"
  fi
  if [ -d /data/adb/echidna ]; then
    if [ ! -d /data/adb/echidna/lib ]; then
      compat_warn "runtime directory exists without /data/adb/echidna/lib; previous install may be incomplete"
    fi
    for region in /data/local/tmp/echidna/echidna_config.bin \
        /data/local/tmp/echidna/echidna_telemetry.bin; do
      if [ -f "$region" ] && [ ! -s "$region" ]; then
        compat_warn "stale zero-byte runtime region detected: $region"
      fi
    done
  fi
  if package_installed com.echidna.app; then
    compat_info "companion app com.echidna.app is installed"
  else
    compat_warn "companion app com.echidna.app is not installed; control bridge will be incomplete"
  fi
  if package_installed com.echidna.lsposed; then
    compat_warn "LSPosed shim com.echidna.lsposed is installed; do not scope the same app in Zygisk and LSPosed"
  else
    compat_info "LSPosed shim com.echidna.lsposed is not installed"
  fi
}

print_device_compat_report() {
  manufacturer="$(prop ro.product.manufacturer)"
  model="$(prop ro.product.model)"
  platform="$(prop ro.board.platform)"
  hardware="$(prop ro.hardware)"
  abilist="$(collect_abis)"
  primary_prop="$(prop ro.product.cpu.abi)"
  secondary_prop="$(prop ro.product.cpu.abi2)"
  fingerprint="$(prop ro.build.fingerprint)"
  combined="$(lower "$manufacturer $model $platform $hardware $fingerprint")"

  compat_info "device=${manufacturer:-unknown} ${model:-unknown}"
  compat_info "platform=${platform:-unknown} hardware=${hardware:-unknown}"
  compat_info "abis=${abilist:-unknown} primary=${primary_prop:-unknown} secondary=${secondary_prop:-none}"
  compat_info "magisk=${MAGISK_VER_CODE:-unknown} api=${API:-unknown}"

  case "$combined" in
    *ranchu*|*goldfish*|*emulator*) vendor_family="Android Emulator" ;;
    *samsung*exynos*|*samsung*s5e*|*samsung*universal*) vendor_family="Samsung Exynos" ;;
    *samsung*qcom*|*samsung*msm*|*samsung*sm[0-9]*|*samsung*kona*|*samsung*lahaina*|*samsung*taro*|*samsung*kalama*)
      vendor_family="Samsung Qualcomm" ;;
    *qcom*|*msm*|*sm[0-9]*|*kona*|*lahaina*|*taro*|*kalama*|*pineapple*) vendor_family="Qualcomm" ;;
    *mediatek*|*mtk*|*mt[0-9]*) vendor_family="MediaTek" ;;
    *google*gs101*|*google*gs201*|*google*zuma*|*tensor*) vendor_family="Google Tensor" ;;
    *samsung*) vendor_family="Samsung (unclassified SoC)" ;;
    *) vendor_family="Unknown" ;;
  esac
  compat_info "vendor-family=$vendor_family"

  if [ "$vendor_family" = "Unknown" ] || [ "$vendor_family" = "Samsung (unclassified SoC)" ]; then
    compat_warn "vendor audio HAL family is not fully classified; expect device-specific failures"
  fi

  if [ -z "$abilist" ]; then
    compat_warn "device ABI list is unavailable; Magisk ARCH mapping is the only ABI signal"
  else
    case ",$abilist," in
      *,"$PRIMARY_ABI",*) ;;
      *)
        compat_warn "primary ABI $PRIMARY_ABI is not present in collected ABI list: $abilist" ;;
    esac
  fi
  if [ -n "$primary_prop" ] && [ "$primary_prop" != "$PRIMARY_ABI" ]; then
    compat_warn "ro.product.cpu.abi=$primary_prop differs from Magisk primary ABI $PRIMARY_ABI"
  fi

  if [ "$ARCH" = "arm" ]; then
    compat_warn "armeabi-v7a builds load but active native hooks intentionally fail closed"
  fi
  if [ "$IS64BIT" = "true" ] && [ -n "$SECONDARY32_ABI" ]; then
    compat_warn "32-bit target apps may load the module but armv7 native hooks are disabled"
  fi

  if command -v magisk >/dev/null 2>&1; then
    echidna_zygisk_enabled || \
      compat_warn "Zygisk does not appear enabled; enable it in Magisk before rebooting"
  else
    compat_warn "magisk command is unavailable during install; cannot verify Zygisk state"
  fi

  if command -v magiskpolicy >/dev/null 2>&1; then
    compat_info "magiskpolicy available for runtime SELinux compatibility checks"
  else
    compat_warn "magiskpolicy not found; SELinux compatibility cannot be adjusted by scripts"
  fi

  selinux_state="$(getenforce 2>/dev/null || echo unknown)"
  compat_info "selinux=$selinux_state"

  has_system_library libOpenSLES.so || \
    compat_warn "libOpenSLES.so not found in common paths; OpenSL coverage is unlikely"
  has_system_library libaudioclient.so || \
    compat_warn "libaudioclient.so not found in common paths; AudioRecord/client coverage is unlikely"
  has_system_library libtinyalsa.so || \
    compat_warn "libtinyalsa.so not found in common paths; tinyalsa/HAL fallback is unlikely"

  if [ -d /data/adb/modules ]; then
    for module in /data/adb/modules/*; do
      [ -d "$module" ] || continue
      module_id="$(basename "$module")"
      case "$(lower "$module_id")" in
        *audio*|*dsp*|*viper*|*james*|*aml*|*ainur*)
          compat_warn "other audio/root module detected: $module_id; hook conflicts are possible" ;;
      esac
    done
  fi
  print_bridge_compat_report
}

# --- Requirements ----------------------------------------------------------
if [ "$API" -lt 26 ]; then
  abort "! Android 8.0 (API 26) or newer required (device API $API)"
fi
# Zygisk (the loader for zygisk/<abi>.so) requires Magisk 24.0+.
if [ "${MAGISK_VER_CODE:-0}" -lt 24000 ]; then
  abort "! Magisk 24.0+ required for Zygisk (installed ver code ${MAGISK_VER_CODE:-unknown})"
fi
ui_print "- Reminder: enable Zygisk in the Magisk app if not already on."

# Map Magisk's $ARCH to our build ABI names.
#   PRIMARY_ABI     — the device's primary process ABI (used for lib/libechidna.so + system/lib(64) DSP)
#   SECONDARY32_ABI — the 32-bit companion ABI on 64-bit devices (for 32-bit hooked processes), if built
case "$ARCH" in
  arm64) PRIMARY_ABI="arm64-v8a";   SECONDARY32_ABI="armeabi-v7a"; PRIMARY_LIBDIR="system/lib64" ;;
  arm)   PRIMARY_ABI="armeabi-v7a"; SECONDARY32_ABI="";            PRIMARY_LIBDIR="system/lib" ;;
  x64)   PRIMARY_ABI="x86_64";      SECONDARY32_ABI="";            PRIMARY_LIBDIR="system/lib64" ;;
  *)     abort "! Unsupported CPU architecture: $ARCH (module ships arm64-v8a, armeabi-v7a, x86_64)" ;;
esac

ui_print "- Device arch: $ARCH -> $PRIMARY_ABI"

require_payload "$MODPATH/zygisk/$PRIMARY_ABI.so"
require_payload "$MODPATH/libs/$PRIMARY_ABI/libech_dsp.so"
require_payload "$MODPATH/post-fs-data.sh"
require_payload "$MODPATH/service.sh"
require_payload "$ZYGISK_STATUS_HELPER"
require_payload "$TRUST_BOOTSTRAP_HELPER"
require_payload "$TRUST_DEX_HELPER"
require_payload "$TRUST_DIGEST"
require_payload "$TRUST_MODE"
optional_payload "$MODPATH/sepolicy.rule"
if [ "$IS64BIT" = "true" ] && [ -n "$SECONDARY32_ABI" ]; then
  optional_payload "$MODPATH/zygisk/$SECONDARY32_ABI.so"
  optional_payload "$MODPATH/libs/$SECONDARY32_ABI/libech_dsp.so"
fi

. "$ZYGISK_STATUS_HELPER"
print_device_compat_report

# --- Place the DSP engine on the default linker search path ----------------
# libechidna.so bare-dlopens "libech_dsp.so" (native/zygisk/src/api.cpp), so the
# DSP lib must sit on the standard lib path. Magisk magic-mounts system/lib(64).
place_dsp() {
  # $1 = source ABI, $2 = destination lib dir (system/lib or system/lib64)
  src="$MODPATH/libs/$1/libech_dsp.so"
  if [ ! -f "$src" ]; then
    abort "! Missing staged DSP lib for $1 ($src)"
  fi
  mkdir -p "$MODPATH/$2"
  cp "$src" "$MODPATH/$2/libech_dsp.so"
  ui_print "- Installed libech_dsp.so -> /$2 ($1)"
}

place_dsp "$PRIMARY_ABI" "$PRIMARY_LIBDIR"

# On a 64-bit device, 32-bit apps are hooked by zygisk/armeabi-v7a.so and need
# the 32-bit DSP under system/lib. Only if that ABI was built.
if [ "$IS64BIT" = "true" ] && [ -n "$SECONDARY32_ABI" ] && \
   [ -f "$MODPATH/libs/$SECONDARY32_ABI/libech_dsp.so" ]; then
  place_dsp "$SECONDARY32_ABI" "system/lib"
fi

# --- Engine lib for the in-app control service JNI -------------------------
# echidna_control_jni.cpp dlopens libechidna.so by full path from
# /data/adb/modules/echidna/lib — provide the primary-ABI copy there.
mkdir -p "$MODPATH/lib"
if [ -f "$MODPATH/zygisk/$PRIMARY_ABI.so" ]; then
  cp "$MODPATH/zygisk/$PRIMARY_ABI.so" "$MODPATH/lib/libechidna.so"
  ui_print "- Staged libechidna.so for control-service JNI ($PRIMARY_ABI)"
else
  abort "! Missing zygisk/$PRIMARY_ABI.so"
fi

# --- Clean up staging ------------------------------------------------------
rm -rf "$MODPATH/libs"

# --- Permissions -----------------------------------------------------------
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$TRUST_BOOTSTRAP_HELPER" 0 0 0755
set_perm "$TRUST_DEX_HELPER" 0 0 0644
set_perm "$TRUST_DIGEST" 0 0 0444
set_perm "$TRUST_MODE" 0 0 0444

if [ "$WARNINGS" -gt 0 ]; then
  ui_print "! Install completed with $WARNINGS compatibility warning(s)."
  ui_print "! ⚠️  This is experimental. Reboot only if you know how to disable the module."
else
  ui_print "- Compatibility preflight did not find obvious install blockers."
fi
ui_print "- Done. Ensure Zygisk is enabled in Magisk, then reboot."
