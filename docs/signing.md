# Release Signing Model

Echidna's control plane ships as a **single companion APK** (`com.echidna.app`) that hosts the
control service in-process. The optional Java fallback ships as a separate LSPosed shim APK
(`com.echidna.lsposed`). Because there is no separate service APK, the historical cross-package
co-signing problem is moot: the old `signature`-level `BIND_CONTROL_SERVICE` permission has been
removed as self-referential. This document covers how the release APKs are signed.

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

Before an automatic tag is created, `tools/check_release_signing.py` rejects missing or partial
inputs and malformed base64/certificate values. The workflow decodes the keystore with mode `0600`
under a restrictive umask, uses `keytool -importkeystore` to prove that the selected private-key
entry, store password, alias, and key password are valid, masks passwords, and removes temporary
keystores on every path.

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

The app and effect copies are never authorities. Missing, tampered, or metadata-mismatched regular
copies are restored only from the validated root pin; symlinks are refused. The root pin and its
root-owned hash metadata must agree, and neither an app nor effect copy may silently replace a
missing or changed root pin. App data clear therefore remains fail-closed until the signed companion
recreates its SPKI enrolment, after which the app copy can be restored. A module reinstall that
loses root state while leaving a derived copy also requires explicit reprovisioning. Key bytes are
never packaged, logged, or written to status metadata.

The packaging concern ships the effect inertly and, only after an eligible legacy-HIDL registry is
proved, copies the verified pending key into the module overlay at
`/system/etc/echidna/preprocessor_controller_p256.spki` for the next boot. The generated registry
remains outside the auto-mounted tree until early `post-fs-data` revalidates the current fingerprint,
stock source, config, library, key, and metadata; without that registry the library/key are inert.
The late service never restarts audioserver or silently rotates an active key. Any trust or
registration error leaves the preprocessor unregistered or identity-bypassed and records recovery
details under `/data/adb/echidna/trust/status.txt` and
`/data/adb/echidna/effect-registration/`.

Provisioning alone is not native-origin proof. No native telemetry protocol consumes this HMAC key
yet, and physical-device SELinux access for the effect host remains an explicit later gate.

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
