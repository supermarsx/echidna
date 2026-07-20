# Release packages — what each download actually is

Every published Echidna release attaches the same set of files. Most of them are
**not** things you install: three are real installable packages, three are
convenience bundles for offline, manual, or integrator use, and two are text
files describing and hashing the rest. This page says what is inside each one,
who needs it, and how it is consumed.

!!! danger "⚠️ Downloading is the easy part"
    Two of these packages install a **root module that hooks the audio capture
    path**. Echidna is experimental and is likely not to work on many phones. Do
    not flash anything unless you already know how to disable a Magisk/Zygisk
    module manually from recovery, adb, safe mode, or another out-of-band rescue
    path — see [Recovering from a bootloop](recovery.md).

!!! tip ":material-download: The short answer"
    For a normal install you need exactly **two** files: the companion APK and
    the Magisk zip. Everything else is optional, and the three `.zip` bundles
    are repackagings of files you already have.

| Asset | Kind | Do you need it? |
| --- | --- | --- |
| `echidna-companion-<TAG>.apk` | Installable | **Yes** — start here. |
| `echidna-magisk-<TAG>.zip` | Installable | **Yes**, for interception on a rooted device. |
| `echidna-lsposed-shim-<TAG>.apk` | Installable | Only for the Java `AudioRecord` fallback path. |
| `echidna-apks-<TAG>.zip` | Convenience bundle | No — it contains the two APKs above. |
| `echidna-native-libs-<TAG>.zip` | Convenience bundle | No — raw `.so` files for inspection. |
| `echidna-complete-<TAG>.zip` | Convenience bundle | No — an archive of everything above. |
| `SHA256SUMS.txt` | Metadata | Yes, if you intend to verify your downloads. |
| `RELEASE_ARTIFACTS.md` | Metadata | The short manifest this page expands on. |

!!! warning "⚠️ Use the newest release"
    Use the newest release unless you are intentionally rolling back or already
    understand the recovery procedure. Earlier releases may contain boot/module
    bugs fixed later and can be harder to recover from after flashing. The
    companion app resolves `releases/latest` at run time and never carries a
    hardcoded tag.

---

## Installable packages

### `echidna-companion-<TAG>.apk` — the companion app

**What it contains.** The Jetpack Compose companion app and its in-process
control service (`libechidna_control_jni.so`), plus three things that are easy
to overlook:

- Per-ABI `libech_dsp.so`, so [the Lab](usage-lab.md) can run your own audio
  through the DSP engine locally, with no root and no other app involved.
- The whole `docs/` tree mirrored into `assets/help/docs/`, which is what
  [in-app Help & search](usage-help.md) renders offline — including this page.
!!! note "The published APK does **not** bundle the module zip"
    A locally built APK embeds `out/echidna-magisk.zip` as an asset when that file
    exists, which lets the [guided installer](installer-guide.md) flash without a
    file picker. The **published** release APKs do not: CI builds the module in a
    separate job from the APKs, so the zip is absent at APK-assembly time. On a
    release build the installer therefore says *"No engine package is bundled in
    this build"* and the module comes from either the in-app download below or a
    `.zip` you pick yourself. Verified against the published
    `echidna-companion-<TAG>.apk`, whose only non-`help/` assets are the baseline
    profiles.

**Who needs it.** Everyone. It is the only package that is useful on its own:
on an unrooted phone the app still runs, every screen renders, the Lab works,
and the engine honestly reports itself as **not installed**.

**How to install it.** Sideload it — `adb install -r echidna-companion-<TAG>.apk`
or the system package installer. It is not a Play Store app.

**Why pick it over the alternatives.** There is no alternative; the copy inside
`echidna-apks-<TAG>.zip` is the same file.

!!! warning "Signing-certificate migration"
    Android only replaces a package in place when the installed and replacement
    APKs share a signing certificate. A previously installed debug-signed
    companion cannot be upgraded to a release-signed one: back up any app data
    you need, uninstall once, then install the release APK. See
    [Signing](signing.md).

### `echidna-magisk-<TAG>.zip` — the flashable engine module

**What it contains.** A real, flashable Magisk module built by
`tools/build_magisk_module.sh`:

- `zygisk/<abi>.so` — the Zygisk hook engine (`libechidna.so`) for
  `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- `libs/<abi>/libech_dsp.so` — the DSP engine, placed system-side by
  `customize.sh`.
- `preproc/<abi>/libechidna_preproc.so` — the **default-off** legacy input
  preprocessor, packaged as a next-boot registration source only. Packaging it
  is not registration, and registration is not session attachment.
- `module.prop`, `customize.sh`, `post-fs-data.sh`, `service.sh`,
  `sepolicy.rule`, the `META-INF` installer stub, the boot/trust helper scripts
  under `common/`, and `LICENSE.md`.
- `common/release-cert-sha256` — the exact certificate the companion app must
  be signed with. This pin is what binds the module to your installed app, and
  the packager refuses to build a production module pinned to the public
  Android debug certificate.

**Who needs it.** Anyone who wants Echidna to transform audio in *other* apps.
Nobody who only wants to try the Lab.

**How to install it.** Either let the [guided installer](installer-guide.md)
stage and flash the bundled or downloaded copy, or flash it by hand from
**Magisk Manager → Modules → Install from storage**. Magisk 24.0+ with Zygisk
enabled is required, and the installer aborts on API < 26. A Zygisk module only
loads at boot, so a reboot is always required.

**Why pick it over the alternatives.** The raw `.so` files in
`echidna-native-libs-<TAG>.zip` are the same libraries, but they are *not* a
module: no installer stub, no boot scripts, no SELinux rule, no certificate
pin. Placing them by hand is not a supported install path.

### `echidna-lsposed-shim-<TAG>.apk` — the optional Java fallback

**What it contains.** The LSPosed/Xposed shim module that hooks Java
`AudioRecord`, bundling its own per-ABI `libechidna_shim_jni.so` bridge and
`libech_dsp.so`. It fetches policy from the companion's authenticated read-only
Binder provider.

**Who needs it.** Only people validating or using the Java fallback route — for
example when a target app never reaches the native hook candidates. It is not
part of a normal install.

**How to install it.** Sideload the APK, install and enable LSPosed, enable the
Echidna module, and pick its scope.

!!! warning "Assign one capture owner per process"
    Do not scope the same target app into both Zygisk and LSPosed unless you are
    deliberately testing duplicate-hook behaviour. Each consumer needs its own
    `captureOwners` value — see [Limitations](limitations.md).

---

## Convenience packages — not needed for a normal install

None of the three bundles below contain anything you cannot get from the
installable assets. They exist for offline transfer, manual archiving, and
development. If you are installing Echidna on a phone, skip all three.

### `echidna-apks-<TAG>.zip`

`apks/echidna-companion-<TAG>.apk`, `apks/echidna-lsposed-shim-<TAG>.apk`, and a
short `README.md`. The release workflow verifies that both bundled members are
byte-identical (SHA-256) to the standalone APKs and that the bundle carries no
other APK.

**Pick it when** you are moving both APKs to an offline machine in one download.
Otherwise take the standalone APKs — they are the same bytes with fewer steps.

### `echidna-native-libs-<TAG>.zip`

`native-libs/<abi>/` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, each holding
four libraries — `libechidna.so` (Zygisk engine), `libech_dsp.so` (DSP),
`libechidna_shim_jni.so` (shim JNI bridge), and `libechidna_preproc.so` (the
default-off legacy effect library) — plus a `README.md`.

**Pick it when** you want to inspect, disassemble, or diff the shipped binaries,
or you are integrating the DSP library elsewhere. This is not an install path:
these files carry no installer, no boot scripts, and no certificate pin.

### `echidna-complete-<TAG>.zip`

The five assets above plus `RELEASE_ARTIFACTS.md`. Note that `SHA256SUMS.txt` is
generated *after* this archive is built, so it is not inside it.

**Pick it when** you are archiving a whole release or preparing an offline
mirror. For installing, it just makes you unzip before you can start.

---

## Verifying a download with `SHA256SUMS.txt`

`SHA256SUMS.txt` lists the SHA-256 of every other asset in the release,
including the bundles. Download it alongside whatever you fetched and check the
files you actually have:

```sh
# From the directory holding the downloaded files
sha256sum --ignore-missing -c SHA256SUMS.txt
```

On Windows without a POSIX shell:

```powershell
Get-FileHash .\echidna-magisk-<TAG>.zip -Algorithm SHA256
```

then compare that hash against the matching line in `SHA256SUMS.txt`.

!!! note ":material-shield-half-full: What a checksum does and does not prove"
    `SHA256SUMS.txt` travels over the same channel as the artifacts, so on its
    own it proves integrity — that you got the bytes the release published — not
    provenance. Origin is established separately: the APKs are signed with the
    release certificate, and the Magisk zip carries the
    `common/release-cert-sha256` pin. That is exactly the distinction the in-app
    downloader enforces below.

---

## Fetching a release from inside the app

The [guided installer](installer-guide.md) can fetch a release for you instead
of you downloading and pushing a zip by hand. On a published release build,
where no module is bundled, this and the `.zip` picker are the two ways to get
the module. It is strictly additive: the picker (and a bundled asset when a
locally built APK has one) remains the offline path, nothing is polled or
downloaded automatically, and the resolved tag and asset name are shown before
any bytes are fetched.

**Check for latest release** resolves `releases/latest` from the GitHub API —
no tag or filename is hardcoded — and identifies the `echidna-magisk-<tag>.zip`
asset by shape. **Download** then fetches it over HTTPS-only transport confined
to GitHub-owned hosts and runs two independent checks before anything can be
staged for install:

- **Integrity** — SHA-256 against the `SHA256SUMS.txt` published in the same
  release.
- **Origin** — the module's `common/release-cert-sha256` pin must equal the
  certificate that signed the running companion app. An APK is instead checked
  against that certificate directly.

Any failure deletes the file and names the check that rejected it; it never
falls back to the bundled asset behind your back. A debug-signed companion
cannot download at all, because it has no forgery-resistant certificate to bind
a download to. Full detail is in
[Signing → In-app release downloads](signing.md#in-app-release-downloads).

!!! warning ":material-package-variant-closed: The convenience bundles cannot be fetched in-app — by design"
    `echidna-native-libs-<TAG>.zip`, `echidna-apks-<TAG>.zip`, and
    `echidna-complete-<TAG>.zip` are **refused** by the in-app downloader. They
    are plain zips: they carry no APK signature, and they carry no
    `common/release-cert-sha256` pin. Nothing binds them to the certificate that
    signed your installed app, so the verifier has no origin to check and rejects
    them rather than accepting them on their checksum alone. This is not a gap to
    be worked around — if you want those bundles, download them yourself and
    verify them against `SHA256SUMS.txt`.

---

## See also

- [Build & Install](build-install.md) — building these same artifacts from source.
- [Installer guide](installer-guide.md) — the guided in-app engine installer.
- [Magisk Release](magisk_release.md) — the module layout and manual flash path.
- [Signing](signing.md) — release signing, certificate migration, and download verification.
- [Verification](verification.md) — what is proven on host/emulator vs. still device-gated.
