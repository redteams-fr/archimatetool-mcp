#!/usr/bin/env bash
# Builds the plugin without a local JDK, using the eclipse-temurin:21-jdk image.
# Gradle downloads are cached in the "archi-mcp-gradle" Docker volume.
#
# Usage: ./scripts/build-with-docker.sh [gradle tasks...]     (default: build)
set -euo pipefail
cd "$(dirname "$0")/.."

[ -d .archi-sdk/Archi/plugins ] || ./scripts/fetch-archi.sh

docker run --rm \
    -v "$PWD":/work -w /work \
    -v archi-mcp-gradle:/root/.gradle \
    eclipse-temurin:21-jdk \
    ./gradlew --no-daemon "${@:-build}"
