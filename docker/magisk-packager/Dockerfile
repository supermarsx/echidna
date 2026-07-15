# syntax=docker/dockerfile:1.7
# echidna/magisk-packager — lay out + zip the flashable Magisk module.
#
# Minimal Alpine + bash + zip. Runs tools/build_magisk_module.sh (t2-e14), which
# consumes the per-ABI native libs from echidna/native-build (build/<abi>/lib/*)
# and emits out/echidna-magisk.zip. No network at run time.
#
# The repo (with build/ output already produced by native-build) is mounted at
# /workspace at run time.

ARG ALPINE_TAG=3.20.3
FROM alpine:${ALPINE_TAG}

# bash (the script is bash, not POSIX sh) + zip + coreutils for the layout.
# Package versions are pinned by the pinned Alpine 3.20 release.
RUN set -eux; \
    apk add --no-cache \
        bash \
        coreutils \
        unzip \
        zip

# Non-root packager. On Linux pass `--user "$(id -u):$(id -g)"` so the emitted
# out/echidna-magisk.zip is host-owned.
RUN adduser -D -u 1000 builder

COPY --chmod=0755 magisk-packager/entrypoint.sh /usr/local/bin/echidna-magisk-packager

USER builder
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/echidna-magisk-packager"]
