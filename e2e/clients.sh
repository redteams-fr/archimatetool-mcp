#!/usr/bin/env bash
# Checks that real MCP clients connect to the plugin running in Archi, using the
# configurations documented in the README (token read from ARCHI_MCP_TOKEN):
#   - Claude Code  : claude mcp add ... 'Authorization: Bearer ${ARCHI_MCP_TOKEN}' -> claude mcp list
#   - OpenCode     : e2e/clients/opencode.json -> opencode mcp list
#   - Mistral Vibe : vibe mcp add ... --api-key-env -> tools listed by Vibe's own MCP client
#
# Each client is installed (latest version) in its own container sharing Archi's network.
# Not run in CI: it depends on third-party packages that change often.
# Prerequisites: same as e2e/run.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

ARCHI_DIR="$PWD/.archi-sdk/Archi"
CONTAINER=archi-mcp-clients
TOKEN=e2e-test-token
URL=http://127.0.0.1:18765/mcp
[ -x "$ARCHI_DIR/Archi" ] || { echo "Linux Archi not found in $ARCHI_DIR: run ./scripts/fetch-archi.sh"; exit 1; }
ls build/libs/fr.redteams.archi.mcp_*.jar >/dev/null 2>&1 || { echo "Plugin not built: run ./gradlew build"; exit 1; }

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
cleanup
trap cleanup EXIT

docker build -q --platform linux/amd64 --build-arg UBUNTU_MIRROR="${UBUNTU_MIRROR:-}" -t archi-mcp-e2e e2e >/dev/null
docker run -d --name "$CONTAINER" --platform linux/amd64 \
    -v "$ARCHI_DIR":/opt/archi:ro -v "$PWD":/work:ro \
    archi-mcp-e2e bash /work/e2e/start-archi.sh >/dev/null

# Runs a script in a client container sharing Archi's network, with the token variable set
client() {
    local image=$1 script=$2
    docker run --rm --network "container:$CONTAINER" -e ARCHI_MCP_TOKEN="$TOKEN" -e URL="$URL" \
        -v "$PWD/e2e/clients":/cfg:ro "$image" bash -c "$script"
}

echo "Waiting for Archi..."
client python:3-slim 'python3 -c "
import socket, time
for _ in range(150):
    try: socket.create_connection((\"127.0.0.1\", 18765), timeout=2); break
    except OSError: time.sleep(2)
else: raise SystemExit(\"MCP server not reachable\")"'

status=0

echo "== Claude Code"
client node:22-slim '
npm i -g @anthropic-ai/claude-code@latest >/tmp/npm.log 2>&1 || { cat /tmp/npm.log; exit 1; }
claude --version
claude mcp add --scope user --transport http archi "$URL" --header '"'"'Authorization: Bearer ${ARCHI_MCP_TOKEN}'"'"' >/dev/null
claude mcp list | tee /tmp/out | grep "archi.*Connected" >/dev/null || { cat /tmp/out; exit 1; }
echo "  ok  connected"' || status=1

echo "== OpenCode"
client node:22-slim '
npm i -g opencode-ai@latest >/tmp/npm.log 2>&1 || { cat /tmp/npm.log; exit 1; }
echo "opencode $(opencode --version)"
mkdir -p ~/.config/opencode && cp /cfg/opencode.json ~/.config/opencode/opencode.json
opencode mcp list 2>&1 | tee /tmp/out | grep "archi.*connected" >/dev/null || { cat /tmp/out; exit 1; }
echo "  ok  connected"' || status=1

echo "== Mistral Vibe"
client python:3.12-slim '
pip install -q mistral-vibe >/tmp/pip.log 2>&1 || { cat /tmp/pip.log; exit 1; }
echo "mistral-vibe $(pip show mistral-vibe | sed -n "s/^Version: //p")"
vibe mcp add archi --url "$URL" --api-key-env ARCHI_MCP_TOKEN >/dev/null
python /cfg/vibe_check.py | tee /tmp/out | grep "^17 tools" >/dev/null || { cat /tmp/out; exit 1; }
echo "  ok  17 tools listed"' || status=1

[ $status -eq 0 ] && echo "All clients connected." || echo "Some clients failed."
exit $status
