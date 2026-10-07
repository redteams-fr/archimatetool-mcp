#!/usr/bin/env bash
# Downloads the Linux build of Archi into .archi-sdk/Archi.
# It is only used as a compile-time SDK (its plugins/*.jar) and by the e2e test:
# the resulting plugin works with Archi on macOS, Windows and Linux.
#
# Usage: ./scripts/fetch-archi.sh [version]     (default: 5.10.0)
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-5.10.0}"
MINOR="${VERSION%.*}"                     # 5.10.0 -> 5.10
BASE="https://github.com/archimatetool/archi.io/releases/download/${MINOR}"
FILE="Archi-Linux64-${VERSION}.tgz"
SDK=".archi-sdk"

if [ -f "$SDK/VERSION" ] && [ "$(cat "$SDK/VERSION")" = "$VERSION" ] && [ -d "$SDK/Archi/plugins" ]; then
    echo "Archi $VERSION already in $SDK/Archi"
    exit 0
fi

mkdir -p "$SDK"
echo "Downloading $FILE..."
curl -fL --progress-bar -o "$SDK/$FILE" "$BASE/$FILE"

echo "Verifying checksum..."
EXPECTED=$(curl -fsSL "$BASE/Archi-${VERSION}-SHA256.txt" | awk -v f="$FILE" '$2 == f || $2 == "*"f {print $1}')
if [ -z "$EXPECTED" ]; then
    echo "Checksum for $FILE not found in Archi-${VERSION}-SHA256.txt" >&2
    exit 1
fi
if command -v sha256sum >/dev/null; then
    ACTUAL=$(sha256sum "$SDK/$FILE" | awk '{print $1}')
else
    ACTUAL=$(shasum -a 256 "$SDK/$FILE" | awk '{print $1}')
fi
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "Checksum mismatch for $FILE" >&2
    exit 1
fi

rm -rf "$SDK/Archi"
tar xzf "$SDK/$FILE" -C "$SDK"
rm "$SDK/$FILE"
echo "$VERSION" > "$SDK/VERSION"
echo "Archi $VERSION ready in $SDK/Archi"
