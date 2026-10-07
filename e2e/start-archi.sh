#!/usr/bin/env bash
# Runs inside the e2e container: installs the plugin, then starts Archi headless (Xvfb).
set -euo pipefail

PORT=18765
TOKEN=e2e-test-token
BIND="${BIND:-127.0.0.1}"

# Plugin in the user dropins folder (as "Manage Plug-ins > Install" would do)
mkdir -p ~/.archi/dropins
cp /work/build/libs/fr.redteams.archi.mcp_*.jar ~/.archi/dropins/

# Preset the plugin preferences (known token)
PREFS=~/.archi/.metadata/.plugins/org.eclipse.core.runtime/.settings
mkdir -p "$PREFS"
printf 'eclipse.preferences.version=1\nport=%s\ntoken=%s\nbindAddress=%s\n' "$PORT" "$TOKEN" "$BIND" > "$PREFS/fr.redteams.archi.mcp.prefs"

Xvfb :99 -screen 0 1600x1000x24 >/tmp/xvfb.log 2>&1 &
export DISPLAY=:99

echo "Starting Archi..."
exec /opt/archi/Archi -nosplash -consoleLog
