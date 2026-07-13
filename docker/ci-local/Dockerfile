# syntax=docker/dockerfile:1.7
# echidna/ci-local — orchestrates native-build -> magisk-packager -> android-build
# to reproduce the CI/release artifact pipeline locally and validate outputs
# before pushing.
#
# This image ships only the Docker + Compose CLI (no daemon). It drives the other
# helper images through the HOST Docker daemon, so it MUST be run with the host
# Docker socket mounted:
#   docker compose -f docker/compose.yaml run --rm ci-local
# (the compose.yaml ci-local service already wires /var/run/docker.sock).

ARG DOCKER_CLI_TAG=27.3.1-cli
FROM docker:${DOCKER_CLI_TAG}

# docker:*-cli is Alpine-based and already bundles the compose plugin; add bash
# for the orchestration script.
RUN apk add --no-cache bash

COPY --chmod=0755 ci-local/run-pipeline.sh /usr/local/bin/echidna-ci-local

WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/echidna-ci-local"]
