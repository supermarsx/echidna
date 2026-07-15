# syntax=docker/dockerfile:1.7
# echidna/native-build — reproducible per-ABI Android NDK cross-compile.
#
# Produces the on-device native libraries the Windows/host CI cannot:
#   build/<abi>/lib/libech_dsp.so  (DSP engine)
#   build/<abi>/lib/libechidna.so  (Zygisk module -> packaged as zygisk/<abi>.so)
# for arm64-v8a (primary), armeabi-v7a, x86_64 — by running the repo's real
# tools/build_native_ndk.sh (t2-e10) against the NDK baked in here.
#
# The repo is NOT copied in; mount it at /workspace at run time so a source
# change never requires an image rebuild.

ARG TEMURIN_TAG=17.0.13_11-jdk-jammy
# Temurin 17 on Ubuntu 22.04 (jammy). A JDK is required only because sdkmanager
# is a Java tool; the NDK cross-compile itself gets jni.h from the NDK sysroot.
FROM eclipse-temurin:${TEMURIN_TAG}

# --- Pinned tool versions ------------------------------------------------------
ARG CMAKE_VERSION=3.30.5
# Ninja 1.12.1 was never published to PyPI (versions jump 1.11.1.4 -> 1.13.0),
# which broke `pip3 install ninja==...` below. 1.13.0 is published and provides a
# manylinux2014_x86_64 wheel for this jammy base.
ARG NINJA_VERSION=1.13.0
ARG CMDLINE_TOOLS_VERSION=11076708
# Integrity gate for the cmdline-tools bootstrap zip. CONFIRM this against
# Google's published checksum (see docker/README.md) and override with
# --build-arg CMDLINE_TOOLS_SHA256=... if Google republishes the archive.
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
# NDK r27. sdkmanager validates this revision against Google's signed manifest.
ARG NDK_VERSION=27.0.12077973
ARG ANDROID_PLATFORM_VERSION=34
ARG ANDROID_BUILD_TOOLS_VERSION=34.0.0

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_NDK=/opt/android-sdk/ndk/27.0.12077973 \
    ANDROID_PLATFORM=android-26 \
    DEBIAN_FRONTEND=noninteractive

# OS deps (versions come from the pinned jammy base image).
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        python3 \
        python3-pip \
        unzip; \
    rm -rf /var/lib/apt/lists/*

# Pinned CMake + Ninja from PyPI wheels (hash-verified by pip). Newer than the
# jammy apt CMake (3.22.1); matches the >=3.22 the repo requires and gives the
# BoringSSL FetchContent sub-build a modern CMake.
RUN set -eux; \
    pip3 install --no-cache-dir \
        "cmake==${CMAKE_VERSION}" \
        "ninja==${NINJA_VERSION}"

# Android cmdline-tools (checksum-verified) + NDK via sdkmanager (Google-signed
# repo manifest pins/validates ndk;${NDK_VERSION} — the exact revision e10/e24
# verified on the host).
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
        "ndk;${NDK_VERSION}" \
        "platforms;android-${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}"; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}"

# Non-root by default. SDK/NDK are world-readable, so on Linux you may also pass
# `--user "$(id -u):$(id -g)"` to make bind-mounted build/ output host-owned.
RUN useradd -m -u 1000 builder

COPY --chmod=0755 native-build/entrypoint.sh /usr/local/bin/echidna-native-build

USER builder
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/echidna-native-build"]
