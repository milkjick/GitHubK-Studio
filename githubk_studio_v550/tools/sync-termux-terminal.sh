#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/vendor/termux-app"
REF="v0.118.3"
if [ -d "$DEST/.git" ]; then
  git -C "$DEST" fetch --depth 1 origin "$REF"
  git -C "$DEST" checkout -q "$REF"
else
  git clone --depth 1 --branch "$REF" https://github.com/termux/termux-app.git "$DEST"
fi
mkdir -p "$ROOT/vendor/termux-terminal"
rm -rf "$ROOT/vendor/termux-terminal/terminal-emulator" "$ROOT/vendor/termux-terminal/terminal-view"
cp -a "$DEST/terminal-emulator" "$ROOT/vendor/termux-terminal/terminal-emulator"
cp -a "$DEST/terminal-view" "$ROOT/vendor/termux-terminal/terminal-view"
printf '%s\n' "Synced Termux $REF into vendor/termux-terminal. Review upstream licenses before redistribution."
