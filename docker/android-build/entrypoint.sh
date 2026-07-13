#!/usr/bin/env bash
# Entrypoint for echidna/android-build.
# Builds the companion APK (and, on request, the two AARs) offline-capable, using
# the pinned SDK baked into this image and the pinned Gradle. Handles the
# missing-wrapper-jar case (t2-e1/e2 committed the wrapper scripts but the binary
# gradle-wrapper.jar is toolchain-gated) by regenerating the wrapper with this
# image's own pinned Gradle when the jar is absent.
set -euo pipefail

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set (baked into the image)}"

if [ ! -f android/app/settings.gradle.kts ]; then
  echo "ERROR: android/app not found. Mount the repo root at /workspace." >&2
  echo "       e.g. docker run -v \"\$PWD:/workspace\" echidna/android-build" >&2
  exit 1
fi

# Point every Android project at the baked SDK (Gradle needs local.properties or
# ANDROID_SDK_ROOT; we write both belt-and-braces, matching .github ci.yml).
for d in android/app android/control-service android/lsposed-shim; do
  [ -d "$d" ] && echo "sdk.dir=${ANDROID_SDK_ROOT}" > "$d/local.properties"
done

# Materialize the Gradle wrapper jar if it is missing (source ships gradlew +
# wrapper .properties; the binary jar is regenerated here with the pinned Gradle).
if [ ! -f android/app/gradle/wrapper/gradle-wrapper.jar ]; then
  echo "gradle-wrapper.jar absent -> generating wrapper with Gradle ${GRADLE_VERSION}"
  gradle --project-dir android/app wrapper --gradle-version "${GRADLE_VERSION}"
fi

# Allow an override command (e.g. assembleRelease, a specific module, or a shell).
if [ "$#" -gt 0 ]; then
  exec gradle --project-dir android/app "$@"
fi

echo "echidna/android-build: assembleDebug (SDK=${ANDROID_SDK_ROOT}, Gradle ${GRADLE_VERSION})"
exec gradle --project-dir android/app assembleDebug
