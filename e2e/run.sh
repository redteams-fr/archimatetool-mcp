#!/usr/bin/env bash
# End-to-end test: runs the real Archi (Linux build, headless with Xvfb) with the plugin
# in a Docker container, then exercises every MCP tool over HTTP from a second container.
#
#   ./e2e/run.sh            default settings (127.0.0.1): the client shares Archi's network
#                           namespace, and a client from another container must be refused
#   ./e2e/run.sh --remote   Archi listens on 0.0.0.0: the client connects over the Docker network
#
# Prerequisites: Docker, ./scripts/fetch-archi.sh (Linux build in .archi-sdk/Archi), and a build
# (./gradlew build or ./scripts/build-with-docker.sh). On Apple Silicon the Archi container
# runs under x86_64 emulation: expect a slow start-up.
# UBUNTU_MIRROR=http://... selects the apt mirror used to build the image (default: Ubuntu's).
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=local
[ "${1:-}" = "--remote" ] && MODE=remote

ARCHI_DIR="$PWD/.archi-sdk/Archi"
CONTAINER=archi-mcp-e2e
NETWORK=archi-mcp-e2e-net
TOKEN=e2e-test-token
[ -x "$ARCHI_DIR/Archi" ] || { echo "Linux Archi not found in $ARCHI_DIR: run ./scripts/fetch-archi.sh"; exit 1; }
ls build/libs/fr.redteams.archi.mcp_*.jar >/dev/null 2>&1 || { echo "Plugin not built: run ./gradlew build"; exit 1; }

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
cleanup
trap cleanup EXIT

echo "Building the e2e image..."
docker build -q --platform linux/amd64 --build-arg UBUNTU_MIRROR="${UBUNTU_MIRROR:-}" -t archi-mcp-e2e e2e >/dev/null
docker network create "$NETWORK" >/dev/null
echo "Starting the Archi container..."
docker run -d --name "$CONTAINER" --network "$NETWORK" --platform linux/amd64 \
    -e BIND="$([ $MODE = remote ] && echo 0.0.0.0 || echo 127.0.0.1)" \
    -v "$ARCHI_DIR":/opt/archi:ro \
    -v "$PWD":/work:ro \
    archi-mcp-e2e bash /work/e2e/start-archi.sh >/dev/null

echo "Mode: $MODE"
status=0
if [ $MODE = remote ]; then
    docker run --rm --network "$NETWORK" -v "$PWD":/work:ro python:3-slim \
        python3 /work/e2e/smoke_test.py "http://$CONTAINER:18765/mcp" "$TOKEN" || status=$?
    if [ $status -eq 0 ]; then
        # Read the whole log first: "grep -q" would close the pipe early (SIGPIPE + pipefail)
        archi_log=$(docker logs "$CONTAINER" 2>&1)
        if [[ "$archi_log" == *"REMOTE ACCESS ENABLED"* ]]; then
            echo "  ok  Archi log reports remote access"
        else
            echo "  FAIL remote access not reported in the Archi log"; status=1
        fi
    fi
else
    docker run --rm --network "container:$CONTAINER" -v "$PWD":/work:ro python:3-slim \
        python3 /work/e2e/smoke_test.py http://127.0.0.1:18765/mcp "$TOKEN" || status=$?
    if [ $status -eq 0 ]; then
        # Listening on 127.0.0.1 only: another machine (container) must not get through
        if docker run --rm --network "$NETWORK" python:3-slim python3 -c \
            "import socket; socket.create_connection(('$CONTAINER', 18765), timeout=5)" 2>/dev/null; then
            echo "  FAIL server reachable from another container while bound to 127.0.0.1"; status=1
        else
            echo "  ok  not reachable from another container (bound to 127.0.0.1)"
        fi
    fi
fi

if [ $status -ne 0 ]; then
    echo "--- Archi console"
    docker logs "$CONTAINER" 2>&1 | tail -80
    echo "--- Archi error log"
    docker exec "$CONTAINER" cat /home/archi/.archi/.metadata/.log 2>/dev/null | tail -80 || true
fi
exit $status
