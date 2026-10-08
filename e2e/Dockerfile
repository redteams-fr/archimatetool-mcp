# Headless runtime to start Archi (Linux x64 build) with the plugin and test it over HTTP.
# Archi itself is not copied into the image: e2e/run.sh mounts .archi-sdk/Archi.
FROM ubuntu:24.04

# Optional apt mirror replacing archive.ubuntu.com and security.ubuntu.com, e.g.
# http://azure.archive.ubuntu.com/ubuntu/ on GitHub runners. Empty: Ubuntu defaults.
ARG UBUNTU_MIRROR=

ENV DEBIAN_FRONTEND=noninteractive
# Short network timeouts: a stalled mirror fails in seconds instead of minutes
RUN printf 'Acquire::http::Timeout "20";\nAcquire::Retries "3";\n' > /etc/apt/apt.conf.d/99timeouts \
    && if [ -n "$UBUNTU_MIRROR" ]; then \
        sed -i -e "s#http://archive.ubuntu.com/ubuntu/#${UBUNTU_MIRROR}#" \
               -e "s#http://security.ubuntu.com/ubuntu/#${UBUNTU_MIRROR}#" \
               /etc/apt/sources.list.d/ubuntu.sources; \
    fi \
    && apt-get update && apt-get install -y --no-install-recommends \
        xvfb xauth libgtk-3-0t64 libxtst6 libxi6 libnss3 libasound2t64 dbus-x11 \
        fonts-dejavu-core curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m archi
USER archi
WORKDIR /home/archi
