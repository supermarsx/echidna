# syntax=docker/dockerfile:1.7
# echidna/android-build — deterministic companion-APK build.
#
# Temurin JDK 17 + pinned Android SDK (platform-34 / build-tools 34.0.0) with
# pre-accepted licenses + pinned Gradle 8.5. Builds android/app -> app-debug.apk
# (and the AARs on request) without the runner needing to install an SDK.
#
# The repo is mounted at /workspace at run time (not copied). First run fetches
# Gradle dependencies from the network; mount a persistent GRADLE_USER_HOME
# volume (see docker/compose.yaml) so subsequent runs are effectively offline.

ARG TEMURIN_TAG=17.0.13_11-jdk-jammy
FROM eclipse-temurin:${TEMURIN_TAG}

# --- Pinned versions (match android/app/app/build.gradle.kts + ci.yml) ---------
ARG CMDLINE_TOOLS_VERSION=11076708
# CONFIRM against Google's published checksum (see docker/README.md).
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG ANDROID_PLATFORM_VERSION=34
ARG ANDROID_BUILD_TOOLS_VERSION=34.0.0
ARG GRADLE_VERSION=8.5
# Gradle 8.5 -bin distribution checksum (published by Gradle at
# https://gradle.org/release-checksums/).
ARG GRADLE_SHA256=9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_VERSION=${GRADLE_VERSION} \
    GRADLE_USER_HOME=/opt/gradle-cache \
    DEBIAN_FRONTEND=noninteractive

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        unzip; \
    rm -rf /var/lib/apt/lists/*

# Android cmdline-tools (checksum-verified) + SDK platform/build-tools via
# sdkmanager (Google-signed manifest validates each pinned package).
RUN set -eux; \
    curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c -; \
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"; \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools; \
    mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest"; \
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools; \
    yes | "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null; \
    "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}"; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}"

# Pinned Gradle (checksum-verified). Used both to build and to regenerate the
# wrapper jar when the repo's is absent (see entrypoint).
RUN set -eux; \
    curl -fsSL -o /tmp/gradle.zip \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c -; \
    unzip -q /tmp/gradle.zip -d /opt; \
    ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle; \
    rm /tmp/gradle.zip

# Non-root builder with a writable, shareable Gradle cache. On Linux pass
# `--user "$(id -u):$(id -g)"` to keep bind-mounted APK output host-owned.
RUN set -eux; \
    useradd -m -u 1000 builder; \
    mkdir -p "${GRADLE_USER_HOME}"; \
    chmod -R 0777 "${GRADLE_USER_HOME}"

COPY --chmod=0755 android-build/entrypoint.sh /usr/local/bin/echidna-android-build

USER builder
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/echidna-android-build"]
