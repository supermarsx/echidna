# Theming — Light, Dark, System, Material You & accents

Echidna's UI is fully theme-aware. You choose a light/dark mode, optionally opt in
to **Material You** dynamic color on Android 12+, or pick one of six curated
**accent** colours. Changes apply immediately, and the choice is remembered in
your settings. The in-app Help reader honours the same theme, so the docs match
the app.

You can set all of this during the [first-run wizard](getting-started.md#step-5-make-it-yours-theme)
(step 5) or later from **Settings**.

---

## Theme mode: System / Light / Dark

![Echidna in light theme](assets/screenshots/17-theming-light.png)

*Light theme.*

![Echidna in dark theme](assets/screenshots/18-theming-dark.png)

*Dark theme. The same screens, the same content — only the palette changes.*

| Mode | Behaviour |
| ---- | --------- |
| :material-theme-light-dark: **System** | Follows the OS light/dark setting (default). |
| :material-white-balance-sunny: **Light** | Always light. |
| :material-weather-night: **Dark** | Always dark. |

**System** is the default and tracks the device's dark-mode setting live.

---

## Material You (dynamic color)

![Echidna with a Material You / accent palette applied](assets/screenshots/19-theming-accent.png)

*Material You dynamic color derives the palette from the system wallpaper on
Android 12+. With it off, you pick one of the curated accents instead.*

On **Android 12+ (API 31)**, the **Material You colors** toggle uses the system
**dynamic palette** derived from your wallpaper for both light and dark schemes.

!!! note ":material-cellphone-cog: Availability"
    Material You dynamic color requires Android 12 or newer. On older versions the
    toggle is hidden and Echidna uses its curated accent palette instead. When
    Material You is **on**, accent selection is disabled — the accent row is
    labelled *"Accent (disabled while Material You is on)"*, because the wallpaper
    palette is driving the colours.

---

## Accent colours

When Material You is off (or unavailable), pick from six curated accents. Each maps
to a full Material 3 tonal ramp, so it works in both light and dark mode.

| Accent | Notes |
| ------ | ----- |
| :material-circle: **Violet** | Default. |
| :material-circle: **Blue** | |
| :material-circle: **Teal** | |
| :material-circle: **Green** | |
| :material-circle: **Amber** | |
| :material-circle: **Rose** | |

!!! tip ":material-palette-swatch: Accent vs. Material You"
    Think of it as a switch: **Material You** = "match my wallpaper" (Android 12+),
    **Accent** = "use this specific Echidna colour". Turning Material You off
    re-enables the accent swatches.

---

## Where the setting lives

Theme mode, the Material You toggle, and the accent are stored in your app
settings and applied instantly across the whole UI — including the in-app
[Help reader](usage-help.md), which renders these docs with your chosen
theme and accent.

---

## Related

- :material-rocket-launch: [Getting Started](getting-started.md) — set your theme in step 5 of the wizard.
- :material-widgets: [Widgets & Quick Settings](usage-widgets-quicksettings.md) — home-screen surfaces that follow the app state.
