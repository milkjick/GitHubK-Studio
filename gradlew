#!/usr/bin/env sh
# GitHubK Studio self-bootstrapping Gradle launcher.
# Designed for Termux/ZeroTermux/AIDE where gradle-wrapper.jar may be missing
# or /storage/emulated/0 is mounted noexec.
#
# v6.1: read distributionUrl from gradle-wrapper.properties (mirror support),
# fall back to huaweicloud mirror when wrapper props are missing.
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# ---- resolve VERSION / URL from gradle-wrapper.properties ----
VERSION="8.7"
URL="https://mirrors.huaweicloud.com/gradle/gradle-$VERSION-bin.zip"
WRAPPER_PROP="$BASE_DIR/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$WRAPPER_PROP" ]; then
  # shellcheck disable=SC2002
  PROP_URL=$(cat "$WRAPPER_PROP" | tr -d '\r' | sed -n 's/^distributionUrl=//p' | head -1)
  case "$PROP_URL" in
    https://*|http://*)
      # decode ':' '=' escapes left by gradle wrapper format
      PROP_URL=$(printf '%s' "$PROP_URL" | sed 's/\\:/:/g; s/\\=//g')
      URL="$PROP_URL"
      NEW_VER=$(printf '%s' "$URL" | sed -n 's/.*gradle-\([0-9][0-9.]*[0-9]\)-bin\.zip.*/\1/p')
      if [ -n "$NEW_VER" ]; then VERSION="$NEW_VER"; fi
      ;;
  esac
fi

DIST_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/githubk-distributions/gradle-$VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"
if [ -x "$GRADLE_BIN" ]; then
  exec "$GRADLE_BIN" "$@"
fi
if [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
  exec "$GRADLE_HOME/bin/gradle" "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  SYS_VER=$(gradle --version 2>/dev/null | awk '/^Gradle / {print $2; exit}') || SYS_VER=""
  if [ "$SYS_VER" = "$VERSION" ]; then
    exec gradle "$@"
  fi
fi
CACHE="${TMPDIR:-/tmp}/githubk-gradle-$VERSION.zip"
mkdir -p "$(dirname "$DIST_DIR")"
echo "[GitHubK] Gradle $VERSION not found; downloading from mirror..." >&2
echo "[GitHubK] URL: $URL" >&2
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 --connect-timeout 15 -C - -o "$CACHE.part" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -c -O "$CACHE.part" "$URL"
else
  echo "curl/wget is required to bootstrap Gradle" >&2
  exit 1
fi
mv "$CACHE.part" "$CACHE"
TMP_EXTRACT="${DIST_DIR}.tmp"
rm -rf "$TMP_EXTRACT"
mkdir -p "$TMP_EXTRACT"
if command -v unzip >/dev/null 2>&1; then
  unzip -q "$CACHE" -d "$TMP_EXTRACT"
else
  echo "unzip is required to bootstrap Gradle" >&2
  exit 1
fi
rm -rf "$DIST_DIR"
mv "$TMP_EXTRACT/gradle-$VERSION" "$DIST_DIR"
rm -rf "$TMP_EXTRACT"
chmod +x "$DIST_DIR/bin/gradle"
exec "$GRADLE_BIN" "$@"
