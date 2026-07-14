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

## Graceful fallback

When **no** signing material is available (local debug builds, CI without secrets), the release build
falls back to **debug signing** so the build still succeeds. The result is a debug-signed,
**non-distributable** APK — do not ship it. A real release requires real keystore material.

```sh
cd android/app
./gradlew :app:assembleRelease   # release-signed if keystore present, else debug-signed

cd ../lsposed-shim
./gradlew :shim:assembleRelease  # same RELEASE_* environment, same fallback
```

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

Keep the keystore and passwords out of the repository. In CI, provide them as encrypted secrets via
the `RELEASE_*` environment variables rather than a checked-in file.

## Native / Magisk signing

The native libraries and the Magisk flashable zip are **not** APK-signed. Integrity for the module is
provided by Magisk's own flashing flow, and third-party DSP plugins are gated by an **Ed25519
signature** over the plugin `.so` (see the DSP plugin schema in
[developer_readme.md](developer_readme.md#dsp-plugin-schema)). The trusted plugin public key is a
build-provisioned compile definition with a fail-closed all-zero placeholder.
