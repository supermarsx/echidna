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

ui_print "- Done. Ensure Zygisk is enabled in Magisk, then reboot."
