# Headless runtime to start Archi (Linux x64 build) with the plugin and test it over HTTP.
# Archi itself is not copied into the image: e2e/run.sh mounts .archi-sdk/Archi.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb xauth libgtk-3-0t64 libxtst6 libxi6 libnss3 libasound2t64 dbus-x11 \
        fonts-dejavu-core curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m archi
USER archi
WORKDIR /home/archi
