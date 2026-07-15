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
