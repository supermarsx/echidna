<!--
  S1 SCREENSHOT MANIFEST — the path -> caption -> what-it-shows contract.
  Doc authors (A1..A5) and the in-app Help reference EXACTLY these paths.
  Referenced from top-level docs as `assets/screenshots/NN-name.png`; from
  docs/hardening/*.md use `../assets/screenshots/NN-name.png`.
  Owner: S1 (branch t16-shots). All captures are HONEST device/emulator states.
-->

# Screenshot manifest (S1)

**Capture environment.** Unrooted-equivalent Android 14 (API 34) x86_64 emulator
(AVD `echidna_test`, 1080x2400), `assembleDebug` from branch `t16-shots` rebased
onto main HEAD (includes the t17 su-loop fix and the t18 single-title fix — every
top-app-bar title renders once).
The device carries a **non-functional Magisk stub (Zygisk disabled, module not
installed)**, so every engine/root state honestly reads **Engine Not Installed /
NOT INSTALLED**. The **Lab** DSP engine (`libech_dsp.so`, x86_64) was built and
packaged, so the Lab shows the **real in-process transform** (loaded v1.2.0) — this
is local mic + test-tone DSP, **NOT** live interception of another app's audio.
Status bar is normalised via SysUI demo mode (clock 12:00). No image implies
Magisk-flash or LSPosed-injection success.

| Path | Caption (honest) | What it shows |
|---|---|---|
| `assets/screenshots/01-dashboard.png` | Dashboard — Master enabled, **Engine Not Installed**, SELinux Enforcing, meters idle | Main entry screen; root-module install-risk banner, Voice-processing master toggle on, engine status card **NOT INSTALLED**, idle meters. Honest unrooted state. |
| `assets/screenshots/02-presets.png` | Preset Manager — 8 presets, **Natural Mask** active/default | Preset list with Import/Export, New Preset, search; Natural Mask card ACTIVE + DEFAULT (Low-Latency, 70% wet, 7 effects), Darth Vader below with Activate. |
| `assets/screenshots/03-effects.png` | Effects Chain — 7-stage chain for the active preset | Editing "Natural Mask": ordered Noise Gate, Equalizer, Compressor/AGC, Pitch Shift, Formant, Reverb, Dry/Wet Mix, each with bypass switch + plain-language description. |
| `assets/screenshots/03b-effects-pitch.png` | Effects — Pitch Shift stage expanded (semitones + fine cents) | Pitch Shift stage open: Semitones slider (−12..+12), Fine cents slider, with the "12 semitones = one octave" helper. Signature pitch/formant controls. |
| `assets/screenshots/04-diagnostics.png` | Diagnostics — Overview, honest empty telemetry (Engine Not Installed) | Diagnostics Overview tab: engine status Not Installed, latency/XRun/CPU counters empty because no native engine runs on an unrooted device. |
| `assets/screenshots/05-compatibility.png` | Compatibility Wizard — real on-device probe (Magisk/Zygisk/su unavailable) | Live probe: AAudio/low-latency capability, vendor HAL, sample rate/frames, and the honest **Magisk not usable / Zygisk disabled / su denied** verdict recommending the Java fallback (not proven active). |
| `assets/screenshots/06-whitelist.png` | Per-App Whitelist — Only-installed toggle + single-line app tags | Per-app targeting reached from Settings: installed-app enumeration, Only-installed filter, per-app enable + Bind Preset. Config only; read-back empty on an unrooted device (not an active hook). |
| `assets/screenshots/07-settings.png` | Settings — real device capture of the settings surface | Live Settings screen: profiles, startup/engine mode, safety/fail-closed, diagnostics, persistent notification, Quick Settings + widgets, Compatibility/Whitelist entries, Help & Docs, Run setup again. |
| `assets/screenshots/08-qstile.png` | Quick Settings tile — **Echidna · Unavailable** | The live-registered QS tile in the shade reads Unavailable because the native module is not active on the unrooted emulator (genuinely drawn, not just declared). |
| `assets/screenshots/10-onboard-welcome.png` | Onboarding 1/13 — Welcome, honest "what actually works" | First-run wizard step 1: Lab works with no root; system-wide interception needs root + Magisk/Zygisk + module; installing never proves interception. |
| `assets/screenshots/11-onboard-permissions.png` | Onboarding 2/13 — Permissions (mic + notifications) | Microphone and Notifications rows with Granted state; copy notes either can be denied and the app "degrades honestly". |
| `assets/screenshots/12-onboard-recovery.png` | Onboarding 4/13 — Recovery plan acknowledgement gate | The one non-skippable step: user must tick "I understand how to recover if a root module bootloops my device" (Next stays disabled) before the wizard advances (hardening requirement). Names the `/data/adb/modules/echidna/disable` marker. |
| `assets/screenshots/13-onboard-engine.png` | Onboarding 11/13 — Interception engine (honest "No Magisk/Zygisk detected") | Wizard engine step: Detection shows Zygisk enabled = no, Echidna module installed = no; copy says you can still use the Lab without root and install later if you root the device. |
| `assets/screenshots/13b-onboard-lab.png` | Onboarding 12/13 — "Hear it work" (Lab, DSP engine loaded) | Wizard step steering the user to the Lab to hear a preset applied offline; shows "DSP engine: loaded" with an Open the Lab action. |
| `assets/screenshots/14-help-tab.png` | Help & Docs — in-app offline docs browser | The in-app Help tab listing the bundled repository Markdown docs, rendered offline. |
| `assets/screenshots/15-help-search.png` | Help — full-text search ("bootloop") results | Native offline search over the bundled docs; query "bootloop" returns ranked doc matches. |
| `assets/screenshots/16-lab-testtone-ab.png` | Lab — test-tone A/B, the **real** local DSP transform (not interception) | 440 Hz sine (Dry, peak −4/rms −7 dB) vs Darth Vader preset output (Wet, peak −9/rms −19 dB): visibly different waveforms + A/B play. Real in-process engine; local mic/tone only, NOT another app's audio. |
| `assets/screenshots/17-theming-light.png` | Theming — Light mode | App in Light theme (settings theme controls). |
| `assets/screenshots/18-theming-dark.png` | Theming — Dark mode | App in Dark theme. |
| `assets/screenshots/19-theming-accent.png` | Theming — accent / Material You palette | Accent-colour picker (Violet/Blue/Teal/Green/Amber/Rose) and the Material You dynamic-colour toggle. |
| `assets/screenshots/20-alerts-tab.png` | Alerts — advisory alerts list (dismissible) | Alerts tab with advisory cards a user can dismiss. |
| `assets/screenshots/21-alerts-actionable.png` | Alerts — actionable alert with buttons | An advisory whose card exposes action buttons (e.g. open the relevant screen / acknowledge). |
| `assets/screenshots/22-widgets.png` | Home-screen widgets — the 3 Echidna widgets | The launcher widget picker previewing all three widgets: Echidna (2x2), Echidna Control ("Master toggle, panic and a live engine-status indicator"), and a Preset switcher. |
| `assets/screenshots/23-onboard-done.png` | Onboarding 13/13 — "You're set up" summary | Final wizard step: Summary (Theme System, Active preset Natural Mask, alerts on, controls notification on, QS tile enabled) and the Enter Echidna action. Title shown once. |
| `assets/screenshots/24-install-engine.png` | Install engine — guided installer, honest "nothing will be installed" | Device-status checklist (Control service Connected; Magisk+Zygisk/module absent) and the honest message that installing needs a rooted device with Zygisk; no install action available here. |
| `assets/screenshots/25-diagnostics-pipeline.png` | Diagnostics — Pipeline & hooks (no signal on this device) | Audio-pipeline graph (HAL -> DSP engine -> processed PCM -> consumer) with the honest note that no signal flows because the engine is not hooking audio here; on rooted+Zygisk the winning hook animates. |
| `assets/screenshots/26-lab-intro.png` | Lab — local DSP testbench overview (does NOT intercept) | Lab intro card stating it runs your own mic/test tones through the real DSP in-process and does NOT intercept or prove interception; "DSP engine: loaded (v1.2.0)". |

> Notes for authors: filenames are final (27 images). Number 09 is intentionally
> unused; 08 remains the QS-tile shot; effects/presets/theming refreshes reuse the
> names above rather than adding duplicates. Onboarding walkthrough shots are
> 10-welcome, 11-permissions, 12-recovery, 13-engine, 13b-lab, 23-done. Every
> caption above passes the honesty rules: unrooted states say Not
> Installed/Unavailable; the Lab is local DSP proof, never interception; nothing
> claims a Magisk-flash/LSPosed-inject success.
