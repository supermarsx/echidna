# Screenshots

This gallery documents the companion app state used for emulator validation and
UI reference.

- `01-dashboard.png` through `06-whitelist.png`, plus `08-qstile.png`, are real
  captures from an **unrooted Android 14 (API 34) x86_64 emulator** using
  `adb exec-out screencap` at 1080 x 2400.
- `07-settings.png` is a **generated UI documentation screenshot**, not a
  device capture. It reflects the current settings state/view-model surface:
  profiles, startup behavior, engine mode, safety, diagnostics, notification,
  Quick Settings, widget controls, and the compatibility/whitelist actions.

The gallery supports the UI scope in [spec section 16][spec-16] and the QA
split in [spec section 20][spec-20].

!!! warning "Honest state - what the emulator can and cannot show"
    The real captures come from a **stock, unrooted** emulator, so the native
    engine is **not installed** and SELinux is enforcing with no policy tool. The
    app therefore runs its **Java-only fallback**. Diagnostics report **Engine Not
    Installed** with empty telemetry, the Compatibility Wizard reports **Magisk
    not installed / Zygisk disabled / `su` permission denied**, the Quick Settings
    tile shows **Unavailable**, and the audio meters rest at **-120 dBFS**.
    Rooted-emulator runtime proof lives in [Verification](verification.md): native
    `processBlock` and one `AudioRecord.read` interception slice pass there. These
    screenshots remain UI/state captures, not evidence of Magisk flashing or LSPosed
    injection.

Sample state was seeded through in-app interaction only: the **Darth Vader**
preset is activated, and the whitelist has Camera and Chrome enabled with a
Helium preset bound to Chrome.

## Main Controls

### Dashboard

![Echidna Dashboard with the Darth Vader preset active](assets/screenshots/01-dashboard.png)

The home screen: Master **ON**, active preset **Darth Vader** (FX / low-latency
tags), Latency Mode set to **Low-Latency**, and Sidetone at -24 dB. The selected
latency segment renders in a darker tonal style while unselected segments are
filled purple; that is the app's segmented-control styling.

*Honest state:* the Engine Status card reads **Native Not Installed / SELinux
Enforcing (Java-only fallback) / Latency 0 ms**, and the meters sit at -120 dBFS
because there is no live audio path on an unrooted emulator.

## Presets And Effects

### Preset Manager

![Preset Manager showing the Darth Vader card active](assets/screenshots/02-presets.png)

The **Darth Vader** card is active: low pitch, dark EQ, Dry/Wet 75%, and the full
module chain (gate, EQ, compressor, pitch, formant, reverb, mix). New Preset /
Import / Export and the Active / Rename / Duplicate / Set Default controls are
all present; **Studio Warm** sits below.

### Effects Editor

![Effects Editor showing the Noise Gate and Equalizer stages](assets/screenshots/03-effects.png)

Top of the effect chain: **Noise Gate** (Threshold -45, Attack 5, Release 80,
Hysteresis 3) and **Equalizer** (5-band, Band 1 at 3500 Hz). The non-default
values are sourced from the active Darth Vader preset.

### Effects Editor - Pitch And Formant

![Effects Editor pitch stage](assets/screenshots/03b-effects-pitch.png)

Scrolled to the signature stages: Compressor (Attack 8 / Release 160 / Makeup 4)
and **Pitch enabled at -7 semitones** with Preserve Formants and the Formant
header following.

## Diagnostics And Compatibility

### Diagnostics

![Diagnostics screen reporting Engine Not Installed](assets/screenshots/04-diagnostics.png)

*Honest state, by design:* **Engine Not Installed**, SELinux Enforcing
(Java-only fallback), Latency 0 ms, XRuns 0, metrics at -120, CPU 0%, and
**"No latency data yet" / "No CPU samples"**, tuner detected at 0.0 Hz.
Telemetry cannot accumulate with no native engine on an unrooted device; this is
the expected state, not a fault.

### Compatibility Wizard

![Compatibility Wizard probing the emulator audio stack](assets/screenshots/05-compatibility.png)

A real on-device probe reached via **Settings -> Run Compatibility Wizard**:
SELinux Enforcing, AAudio **Supported**, low-latency/pro-audio Unsupported,
vendor HAL **Google (goldfish_x86_64)**, 48000 Hz / 1088 frames. It reports
**Magisk not installed**, **Zygisk disabled**, Java fallback active, and
**"Cannot run program 'su': Permission denied"**, which is expected on a device
without root.

## Whitelist And Settings

### Per-App Whitelist

![Per-App Whitelist editor with camera and Chrome enabled](assets/screenshots/06-whitelist.png)

Reached via **Settings -> Per-App Whitelist**. Live `PackageManager`
enumeration lists installed apps; here **com.android.camera2** is enabled and
**com.android.chrome** is enabled with the **Helium** preset bound through the
Bind Preset picker. An Add-by-package field and Reload apps control round it
out.

*Honest state:* the toggles and binding reflect local UI/config. The service
read-back is empty on an unrooted device, so this is intended configuration, not
an active hook.

### Settings

![Generated Settings docs screenshot](assets/screenshots/07-settings.png)

This image is a generated UI documentation screenshot, not an emulator or device
capture. It mirrors the current settings organization exposed by
`SettingsViewModel` and `SettingsState`: settings profiles, startup behavior,
engine mode, latency, sidetone, master/bypass controls, fail-closed safety,
diagnostic logging, persistent notification, Quick Settings, widget controls,
and the buttons for the Compatibility Wizard and Per-App Whitelist. Engine mode
is persisted through the control service; Compatibility mode asks the native
hook gate to stand down so fragile devices can use the fallback path.

## Quick Settings Tile

### Quick Settings Tile

![Echidna Quick Settings tile showing Unavailable](assets/screenshots/08-qstile.png)

The live Quick Settings tile, registered and rendered in the notification shade.
It reads **"Echidna - Unavailable"** because the native module is not
active/installed on the unrooted emulator. The tile is genuinely live-registered
and drawn in the shade, not merely declared in the manifest.

[spec-16]: https://github.com/supermarsx/echidna/blob/main/spec.md#16-ui-controls--preset-management--detailed-spec
[spec-20]: https://github.com/supermarsx/echidna/blob/main/spec.md#20-qa-checklist-ui--dsp
