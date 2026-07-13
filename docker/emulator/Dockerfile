# syntax=docker/dockerfile:1.7
# echidna/emulator (OPTIONAL) — KVM-accelerated Android emulator for the
# instrumentation / E2E tests spec §11 (ABI matrix) and §20 (QA) call for.
#
# REQUIRES KVM on the host. Run with:
#   docker run --rm --device /dev/kvm -v "$PWD:/workspace" echidna/emulator
# Without /dev/kvm the emulator will not boot. This image is a scaffold for
# on-device/on-emulator tests (t2-e18) — it is NOT part of the default artifact
# pipeline and is gated behind the `emulator` compose profile.

ARG TEMURIN_TAG=17.0.13_11-jdk-jammy
FROM eclipse-temurin:${TEMURIN_TAG}

ARG CMDLINE_TOOLS_VERSION=11076708
# CONFIRM against Google's published checksum (see docker/README.md).
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG ANDROID_PLATFORM_VERSION=34
ARG ANDROID_BUILD_TOOLS_VERSION=34.0.0
# Pinned emulator system image. google_apis (not -playstore) so it is rootable
# for LSPosed/Magisk-style testing; x86_64 for KVM acceleration.
ARG EMULATOR_SYSTEM_IMAGE=system-images;android-34;google_apis;x86_64
ARG AVD_NAME=echidna-avd

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_AVD_HOME=/opt/android-avd \
    DEBIAN_FRONTEND=noninteractive

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        unzip \
        cpu-checker \
        libpulse0 \
        libnss3 \
        libxcursor1 \
        libxcomposite1 \
        libxdamage1 \
        libxi6 \
        libxtst6 \
        libgl1; \
    rm -rf /var/lib/apt/lists/*

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
        "emulator" \
        "platforms;android-${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
        "${EMULATOR_SYSTEM_IMAGE}"; \
    mkdir -p "${ANDROID_AVD_HOME}"; \
    echo "no" | "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/avdmanager" create avd \
        -n "${AVD_NAME}" -k "${EMULATOR_SYSTEM_IMAGE}" --force; \
    chmod -R a+rX "${ANDROID_SDK_ROOT}"

ENV PATH="${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/emulator:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${PATH}" \
    ECHIDNA_AVD_NAME=${AVD_NAME}

WORKDIR /workspace

# Headless emulator boot. Overridable (e.g. run adb/instrumentation instead).
CMD ["bash", "-lc", "emulator -avd ${ECHIDNA_AVD_NAME} -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect"]
