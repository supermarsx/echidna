# Recovering from a bootloop

If your device stops booting normally after you flash the Echidna Magisk/Zygisk
module, this page walks through recovery options from the **least invasive** to
the **most invasive**. Try the gentlest one that your device supports and that
actually brings the phone back, then stop.

!!! note ":material-shield-alert: This is the situation the setup wizard made you acknowledge"
    The [first-run wizard](getting-started.md#step-4-before-you-install-a-root-module)
    requires you to tick *"I understand how to recover if a root module bootloops
    my device"* before you can proceed — it is the one step you cannot skip. This
    page is that recovery knowledge. If you flashed anyway without a plan, read it
    now, and see [Limitations](limitations.md) for why the failsafes are guardrails,
    not guarantees.

!!! danger "⚠️ These are guardrails, not guarantees"
    Echidna is **experimental** software for rooted-device power users. The
    failsafe markers and boot watchdog below are *intended* to help a prepared
    user boot again, but they cannot promise recovery on every device. Whether
    any of this works depends on things Echidna does not control — an unlocked
    bootloader, whether recovery can decrypt `/data`, whether adb is available,
    and your exact device and ROM. If you did not already know how to disable a
    Magisk/Zygisk module out-of-band **before** you installed Echidna, you are
    now in the situation the install docs warned about. See
    [Limitations → Install-risk failsafes are guardrails, not guarantees](limitations.md#install-risk-failsafes-are-guardrails-not-guarantees).

!!! note "Honest status — recovery is not a promise the effects work"
    Echidna is experimental and **not fully functional**. Recovering from a
    bootloop only gets your phone booting again; it does **not** mean the voice
    changer will work on your device afterwards. Do not re-enable the module
    expecting it to work — re-enable it only if you understand the risk of
    another bootloop (see [step 4](#4-reinstall-magisk-cleanly)).

## Before you start

- **Unlocked bootloader.** The last-resort `fastboot flash` steps require an
  unlocked bootloader. `fastboot flash` is rejected on a locked bootloader, and
  unlocking wipes user data on most devices — you cannot unlock *after* a
  bootloop to save existing data.
- **Know your exact device + ROM.** Flashing the **wrong** `boot.img` can turn a
  soft bootloop into a hard brick. The image must match your exact device model
  **and** the exact ROM/build you are running.
- **`/data` may be encrypted.** A recovery (TWRP/OrangeFox/LineageOS Recovery)
  often cannot decrypt or even mount `/data`. That is why some disable markers
  live on `/cache` and `/metadata` instead — see [step 2](#2-create-an-echidna-disable-marker).
- **Back up first, always.** Make a backup before you flash anything in
  recovery/fastboot. Recovery steps can and do lose data.
- **A/B (slot) devices** flash to a specific slot — note the [A/B guidance](#ab-slot-devices)
  in step 3.

---

## Recovery ladder

### 1. Boot with all modules disabled (gentlest)

If the device still boots far enough to reach Android, you may be able to disable
**every** Magisk module for one boot without touching Echidna's files directly:

- **Android Safe Mode.** Booting Android into Safe Mode makes Magisk disable
  **all** modules for that boot. The key combo varies by device (commonly: hold
  **Power**, then long-press **Power off** → **Reboot to safe mode**; or hold
  **Volume Down** during boot). Once booted, open the Magisk app and toggle
  Echidna off (or [create a disable marker](#2-create-an-echidna-disable-marker)),
  then reboot normally.
- **Magisk app.** If you can reach the launcher at all, open **Magisk → Modules**,
  toggle **Echidna** off, and reboot.

This is the safest option because it changes nothing on disk that you cannot undo
from the Magisk UI. If the device never boots far enough to do this, move to
step 2.

### 2. Create an Echidna disable marker

Echidna's boot scripts check for a set of **disable markers** early in boot. If
any one of them is present, the module *fails closed* for that boot: it removes
its staged native library, tears down its shared runtime regions, and touches
Magisk's own module `disable` file so the module stays off. These markers are
honored by both `post-fs-data.sh` and `service.sh`
(`manual_disable_marker()` in `android/control-service/magisk/`).

Create the **first marker you can actually reach** — from adb (`adb shell`,
`adb root`) if it is available, or from a recovery shell. Pick a marker on a
partition your recovery can write to:

| Marker path | When to use it |
| ----------- | -------------- |
| `/data/adb/modules/echidna/disable` | Magisk's standard per-module disable file. Works when `/data` is mounted (and decrypted). Disables just Echidna. |
| `/data/adb/modules/echidna/remove` | Tells Magisk to **uninstall** the Echidna module on the next boot. Also honored as a disable marker. |
| `/data/adb/echidna/disable` | Echidna's own runtime disable marker; keeps Echidna inactive even if the module files remain. Needs `/data`. |
| `/data/adb/echidna/safe-mode` | Echidna's project safe-mode marker; boots with Echidna disabled so the companion app can report why. Needs `/data`. |
| `/cache/echidna-disable` | Early marker for when **`/data` is encrypted, unavailable, or unsafe** to touch from recovery. |
| `/metadata/echidna-disable` | Same purpose as the `/cache` marker, for devices that expose `/metadata` instead. |

For example, from a recovery shell or `adb shell` where `/data` is mounted:

```sh
# Disable just the Echidna module for the next boot
touch /data/adb/modules/echidna/disable

# ...or, if /data will not mount/decrypt, use an early marker:
touch /cache/echidna-disable
# or
touch /metadata/echidna-disable
```

Then reboot. On the next boot Echidna sees the marker and disables itself.

!!! tip "The automatic boot watchdog may recover it for you"
    Echidna also arms a **boot watchdog** in `post-fs-data.sh`. Each boot that
    never reaches the late-start service (`service.sh`) counts as an unfinished
    boot; after the limit is reached (`ECHIDNA_BOOT_FAIL_LIMIT`, **default 2**,
    minimum 2) the module **self-disables** — exactly the fail-closed path
    above. So a bootloop caused *only* by Echidna will often break on its own
    after a couple of failed boots. The watchdog state and the reason it tripped
    are written under `/data/adb/echidna/failsafe/` (e.g. `reason.txt`). Treat
    the watchdog as a **last-resort guard, not your only plan** — it cannot help
    if another module breaks earlier in boot, if `/data` is unavailable, or if
    the failure never returns control to the module scripts.

If markers cannot be written (recovery cannot mount/decrypt any writable
partition) or do not resolve the loop, move to step 3.

### 3. Flash a stock `boot.img` with fastboot (removes Magisk)

This is the **recovery-of-last-resort** used successfully by the reporter in
[issue #17](https://github.com/supermarsx/echidna/issues/17) (a Poco X3 NFC on
LineageOS 22.2). Re-flashing the stock boot image **removes Magisk entirely** —
and therefore the Echidna module with it.

!!! warning "Requires an unlocked bootloader and the exact matching image"
    This works only with an **unlocked bootloader**, and you must flash the
    `boot.img` that matches your **exact device and exact ROM/build**. The wrong
    image can hard-brick the device.

1. **Reboot into fastboot / bootloader mode.** From a powered-off device the key
   combo is usually **Volume Down + Power** (device-specific); or from adb:

   ```sh
   adb reboot bootloader
   ```

2. **Obtain the correct stock `boot.img`** for your exact device **and** ROM.
   Good sources, in rough order of preference:
   - **Magisk's own backup**, if it exists: Magisk backs the original image up to
     `/data/adb/magisk/stock_boot.img.gz` at patch time. If you can still read
     `/data`, restoring that (or using **Magisk app → Uninstall → Restore
     Images** *before* it broke) gives you the exact pre-root image.
   - The **ROM you are running** — e.g. the LineageOS build or its recovery for
     your device — which contains the matching `boot.img`.
   - The **factory image / OTA** for your device build, from which `boot.img`
     can be extracted.

3. **Flash it:**

   ```sh
   fastboot flash boot boot.img
   fastboot reboot
   ```

   The device should boot again.

#### A/B (slot) devices

Many modern devices are A/B (no dedicated recovery partition; `boot` is slotted).
On those, flash the active slot — or both slots to be safe:

```sh
# Flash the currently active slot only
fastboot flash boot boot.img

# ...or flash both slots explicitly
fastboot flash boot --slot all boot.img
```

If you are unsure which slot is active, `fastboot getvar current-slot` reports it.

### 4. Reinstall Magisk cleanly

Flashing stock `boot.img` in step 3 removed Magisk, so **root is gone**. If you
want root back, reinstall Magisk **properly** for your device (patch the stock
boot image with the Magisk app and flash the patched image, per Magisk's own
documentation).

!!! danger "Remove or disable the Echidna module BEFORE you re-root"
    Re-enabling root with the Echidna module **still present** can cause **another
    bootloop**. Before you reboot into a freshly re-rooted system, make sure
    Echidna will not load again — do **one** of:

    - **Delete the module directory** `/data/adb/modules/echidna` (or drop a
      `.../echidna/remove` marker so Magisk uninstalls it), or
    - **Create a disable marker** from [step 2](#2-create-an-echidna-disable-marker)
      (e.g. `/data/adb/echidna/disable`), or
    - **Restore images via Magisk's own backup** rather than re-patching over a
      state that still contains the module.

    Only after Echidna is removed/disabled should you re-root and reboot.

---

## After recovery

Getting back to a booting phone does **not** mean Echidna will work — it is
experimental and may not function on your device even after a clean reinstall.
If you choose to try Echidna again, re-read
[Build & Install](build-install.md) and the
[Limitations](limitations.md) first, and only re-enable the module if you are
prepared to run this recovery again.

## Credits

The `fastboot flash boot boot.img` recovery method documented in step 3 was
reported by **MrLeyYT** (Poco X3 NFC / LineageOS 22.2) in
[GitHub issue #17](https://github.com/supermarsx/echidna/issues/17). Thanks for
writing up the recovery.
