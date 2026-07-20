# Release Signing Model

Echidna's control plane ships as a **single companion APK** (`com.echidna.app`) that hosts the
control service in-process. The optional Java fallback ships as a separate LSPosed shim APK
(`com.echidna.lsposed`). Because there is no separate service APK, the historical cross-package
co-signing problem is moot: the old `signature`-level `BIND_CONTROL_SERVICE` permission has been
removed as self-referential. This document covers how the release APKs are signed.

!!! abstract "Trust model at a glance"
    - :material-key-variant: **APK signing** — companion + shim signed with the release key;
      `verify_android_artifacts.py` pins the certificate SHA-256 and rejects debug certs.
    - :material-shield-lock: **Fail-closed hosting** — absent secrets *skip* publication; partial or
      invalid material *fails*. No path ever ships a publishable debug-signed artifact.
    - :material-fingerprint: **On-device trust bootstrap** — the module verifies the companion's
      signer against the embedded pin before staging the controller SPKI and telemetry HMAC key.
    - :material-file-sign: **Plugin signing** — third-party DSP `.so` plugins need a valid Ed25519
      signature; verification is fail-closed when no trusted key or crypto backend is present.

    Every one of these is verified at build/host level today; the on-device SELinux label and
    transformed-audio evidence for the effect-trust path remain device-gated (see
    [Verification](verification.md)).

## Distribution model

Distribution is **root / sideload via Magisk only** — there is no Play Store channel. `compileSdk`
and `targetSdk` are 34, which is sufficient for sideload; Play-store distribution (targetSdk 35 +
package-visibility hygiene) is explicitly out of scope.

## Where signing material comes from

The app module (`android/app/app/build.gradle.kts`) and LSPosed shim module
(`android/lsposed-shim/shim/build.gradle`) resolve release signing material in priority order.
**No keystore or password is ever hardcoded or committed.**

1. A git-ignored `keystore.properties` next to the app project
   (`android/app/keystore.properties`). Copy `android/app/keystore.properties.example` and fill in
   real values. The shim can also read `android/lsposed-shim/keystore.properties` when building it
   directly. `keystore.properties`, `*.jks`, and `*.keystore` are git-ignored.
2. Gradle properties / environment variables (preferred in CI, supplied as secrets):
   - `RELEASE_STORE_FILE` — path to the keystore (absolute is preferred in CI).
   - `RELEASE_STORE_PASSWORD` — keystore password.
   - `RELEASE_KEY_ALIAS` — key entry alias.
   - `RELEASE_KEY_PASSWORD` — key entry password.

The `keystore.properties` keys are `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Direct local Gradle fallback

When **no** signing material is available, a direct local Gradle release build falls back to
**debug signing** so developers can still exercise release build types. The result is a
debug-signed, **non-distributable** APK — do not ship it. This fallback is not the hosted release
policy.

```sh
cd android/app
./gradlew :app:assembleRelease   # release-signed if keystore present, else debug-signed

cd ../lsposed-shim
./gradlew :shim:assembleRelease  # same RELEASE_* environment, same fallback
```

The shim build also requires the dedicated native JNI and DSP outputs from
`tools/build_native_ndk.sh`; it fails rather than packaging missing or stale native inputs.

## Hosted release workflow fails closed

`.github/workflows/release.yml` requires all of these encrypted repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_CERT_SHA256`

On the normal main-branch CI path, an entirely absent five-secret set disables release publication:
the reusable workflow emits an explicit notice and skips its build and publish jobs successfully.
This lets CI remain green in repositories that have not enabled releases. A partial or invalid set
still fails closed, as does an unconfigured manual or tag-triggered release.

Before an automatic tag is created, `tools/check_release_signing.py` rejects those partial or
invalid inputs and malformed base64/certificate values. The workflow decodes the keystore with
mode `0600` under a restrictive umask, uses `keytool -importkeystore` to prove that the selected
private-key entry, store password, alias, and key password are valid, masks passwords, and removes
temporary keystores on every path.

After building, `tools/verify_android_artifacts.py` requires the exact companion and shim APK
payloads, rejects debug certificates and unexpected native libraries, checks both APKs against
`RELEASE_CERT_SHA256`, and verifies the release bundles before upload. Missing or invalid signing
material never falls back to a publishable debug-signed artifact.

The same normalized `RELEASE_CERT_SHA256` is embedded in the production Magisk module. Packaging
requires the pin and the API-26-compatible app-process trust helper; missing, wildcard, all-zero,
malformed, and known Android-debug pins are rejected. A local debug pin is accepted only with
explicit `ECHIDNA_TRUST_MODE=development`, and that module is labelled non-production.

## Companion trust bootstrap

On Android 26 through 33, late-start `service.sh` invokes a module-owned Dex helper through
`app_process`. The helper asks `PackageManager` for `com.echidna.app`, verifies its user-0 UID and
data directory, and requires its current signer to match the embedded release pin. API 26/27 uses
`GET_SIGNATURES`; API 28 through 33 uses `SigningInfo`, requires one current signer, and validates
that the bounded, duplicate-free signing lineage contains the current signer exactly once. An old
lineage certificate never substitutes for the pinned current signer.

Only after that check does the helper read
`${applicationInfo.dataDir}/files/echidna/preprocessor_controller_p256.spki`. It requires an
app-UID-owned `0700` parent, regular `0600` file, 1–1024 byte canonical P-256 DER SPKI, stable inode
metadata, and no symlink. It atomically writes the exact bytes root-owned and read-only to the
module's inert `trust/next-boot/` directory. Existing pending or active bytes must match; silent key
rotation is refused.

Only after the same PackageManager signer, user-0 UID/dataDir, and app SPKI checks pass, the helper
also provisions a per-install telemetry-origin proof key. A fresh install receives 32 bytes from
Android's `SecureRandom`, pinned as root:root `0400` under the module's `trust/state/` directory.
The helper records only its SHA-256 and 16-hex key ID. It atomically derives two identical copies
from that root pin: an app-UID-owned `0600` file at
`${applicationInfo.dataDir}/files/echidna/preprocessor_telemetry_hmac.key`, and a root:audio
(`AID_AUDIO=1005`) `0440` next-boot backing file for
`/system/etc/echidna/preprocessor_telemetry_hmac.key`.

Before that backing inode can be exposed by the next Magisk mount, the shared key-label helper
rechecks its no-symlink regular-file identity, 32-byte length, root-pin hash, owner/mode, and stable
inode. It applies and verifies the dedicated
`u:object_r:echidna_telemetry_key_file:s0` type. Policy grants only `{ getattr open read }` to the
specific `audioserver` domain used by legacy framework-hosted effects and the standard
`hal_audio_server` attribute used by HIDL/Stable-AIDL audio HAL services. It does not grant access
to `system_file`, vendor/config types, app domains, or any write, map, or execute permission. A
missing type, failed relabel, label drift, hash mismatch, or unsafe path removes only the derived
effect copy and leaves the authoritative root pin intact for explicit restaging.

The app and effect copies are never authorities. Missing, tampered, or metadata-mismatched regular
copies are restored only from the validated root pin; symlinks are refused. The root pin and its
root-owned hash metadata must agree, and neither an app nor effect copy may silently replace a
missing or changed root pin. App data clear therefore remains fail-closed until the signed companion
recreates its SPKI enrolment. The same APK signer is not enough after Android has deleted the
app-private Android Keystore key: a different SPKI is an intentional trust-rotation boundary. A
module reinstall or update that loses the authoritative root state while a derived copy survives
also remains fail-closed; that copy is never promoted to authority.

Recovery from either boundary requires explicit trusted-pair reprovisioning: disable Echidna first,
back up any app settings that matter, reinstall the intended signed companion and module pair, and
let the companion recreate its SPKI. Never copy the app/effect HMAC file into root state or replace
a pin while the effect host is live. A fresh eligible registration is next-boot-only: the first boot
verifies and stages trust/registration, and the subsequent reboot activates those staged files.
Key bytes are never packaged, logged, or written to status metadata.

The packaging concern ships the effect inertly and, only after an eligible legacy-HIDL registry is
proved, copies the verified pending key into the module overlay at
`/system/etc/echidna/preprocessor_controller_p256.spki` for the next boot. The generated registry
remains outside the auto-mounted tree until early `post-fs-data` revalidates the current fingerprint,
stock source, config, library, key, and metadata; without that registry the library/key are inert.
The late service never restarts audioserver or silently rotates an active key. Any trust or
registration error leaves the preprocessor unregistered or identity-bypassed and records recovery
details under `/data/adb/echidna/trust/status.txt` and
`/data/adb/echidna/effect-registration/`.

Native consumption is implemented and host-tested: the legacy effect loads the root:audio key and
signs a capability-incarnation- and nonce-bound ECHT v2 proof, LSPosed relays that proof, and the
control service verifies its key ID and HMAC before accepting telemetry. Release validation must
still confirm the exact label, absence of relevant AVCs, and transformed-audio evidence under
Enforcing SELinux on each supported platform path.

## Signing-certificate migration

Android package upgrades require the new APK to use the same signing certificate as the installed
APK. An existing debug-signed `com.echidna.app` or `com.echidna.lsposed` installation therefore
cannot be upgraded in place to an APK signed with a new release certificate.

Before the first release-signed install, back up any app data you need, uninstall the old package,
and install the release-signed APK. This is a one-time migration for each package. Keep the release
keystore stable and backed up afterward: losing or replacing it forces the same uninstall/data-loss
boundary again. Do not weaken signature verification or redistribute the private key to bypass it.

## Minification

Minification and resource-shrinking are **disabled** for the first release.

**R8** is Android's release optimizer/shrinker. It rewrites bytecode, removes code it thinks is
unused, and can rename classes and methods. That is useful for ordinary apps, but Echidna has
reflection-sensitive entry points: AIDL stubs (`IEchidnaControlService`), JNI
(`libechidna_control_jni`), Compose-generated code, and the LSPosed shim entry named from
`assets/xposed_init`. If R8 is enabled without a proven keep-rule file, it can silently strip or
rename code that is only reached from Binder, native code, or LSPosed.

**Resource shrinking** is the matching Android resource pass. It removes layouts, drawables, and
other resources that look unused after R8 analysis. That also needs proof because LSPosed,
notifications, widgets, and Compose preview/runtime paths can reference resources indirectly.

For now, release builds are larger but safer: `isMinifyEnabled = false` and
`isShrinkResources = false`. Enabling R8/resource shrinking later means adding a
`proguard-rules.pro` keep-rule set for AIDL, JNI, Compose, LSPosed, widgets, and notifications,
then proving install, launch, service bind, shim load, and diagnostics on the minified APK.

## Generating a keystore

```sh
keytool -genkeypair -v -keystore echidna-release.jks -alias echidna \
        -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and passwords out of the repository. Back up the keystore securely and record its
certificate SHA-256 separately. In CI, provide signing values as encrypted secrets rather than a
checked-in file.

## Native / Magisk signing

The native libraries and the Magisk flashable zip are **not** APK-signed. Integrity for the module is
provided by Magisk's own flashing flow, and third-party DSP plugins are gated by an **Ed25519
signature** over the plugin `.so` (see the DSP plugin schema in
[developer_readme.md](developer_readme.md#dsp-plugin-schema)). The trusted plugin public key is a
build-provisioned compile definition with a fail-closed all-zero placeholder.

## In-app release downloads

The companion's install screen can **optionally** fetch release artifacts from GitHub Releases. It is
strictly additive: the bundled module asset and the `.zip` picker remain the offline install path,
nothing is downloaded or installed without an explicit user action, and the resolved tag and asset
name are shown before any bytes are fetched. The latest release is resolved from
`releases/latest` at run time — no tag or asset filename is hardcoded in the app.

Every downloaded artifact passes two independent checks before it can be staged for install, and any
failure deletes the file and reports which check rejected it rather than falling back to the bundled
package:

* **Integrity** — SHA-256 against `SHA256SUMS.txt` published in the same release.
* **Origin** — an APK must be signed by the same certificate as the running companion (read via
  `PackageManager` / `SigningInfo`); the Magisk zip, which is not APK-signed, must carry a
  `common/release-cert-sha256` pin equal to that certificate. The known Android debug certificate is
  rejected on both paths, and a debug-signed companion cannot download at all because it has no
  forgery-resistant certificate to bind to.

Transport is HTTPS-only, including every redirect hop, and confined to GitHub-owned hosts.

The convenience bundles (`echidna-apks-*.zip`, `echidna-native-libs-*.zip`,
`echidna-complete-*.zip`) are therefore **not fetchable in-app**: a plain zip carries neither an APK
signature nor a `common/release-cert-sha256` pin, so there is no origin to bind it to the installed
app and the verifier refuses it rather than trusting the checksum alone. Download those by hand and
verify them against `SHA256SUMS.txt` — see [Release Packages](release-packages.md).
