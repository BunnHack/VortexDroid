#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS"

ROOTFS_URL="${ROOTFS_URL:-https://github.com/cetotos/PolyDroid2/releases/download/rootfs-1/rootfs.tar.xz}"
ROOTFS_SHA="2a61930a4c2a8efe780a935f84df947640407594b8f9ec8bba960afb5bcd7d34"

# Vortex client AppImage (https://playvortex.io). Linux downloads are not on
# the website yet. Resolution order:
#   1. already staged in assets
#   2. $VORTEX_APPIMAGE env var
#   3. repo root / parent dir (local dev with the file at hand)
#   4. this repo's GitHub releases (CI)
VORTEX_SHA="1b51417661cb191ef7041b9ef590b17a5876e7564c60f0b006c69bd5175ccc8f"
REPO_SLUG="${VORTEX_REPO:-BunnHack/VortexDroid}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch_vortex() {
    mkdir -p "$ASSETS/vortex"
    local src=""
    if [[ -n "${VORTEX_APPIMAGE:-}" && -f "$VORTEX_APPIMAGE" ]]; then
        src="$VORTEX_APPIMAGE"
    elif [[ -f "$ROOT/Vortex-linux-x86_64.AppImage" ]]; then
        src="$ROOT/Vortex-linux-x86_64.AppImage"
    elif [[ -f "$ROOT/../Vortex-linux-x86_64.AppImage" ]]; then
        src="$ROOT/../Vortex-linux-x86_64.AppImage"
    else
        # find it on this repo's releases (any release, first asset match)
        local url
        url="$(curl -sfL "https://api.github.com/repos/$REPO_SLUG/releases?per_page=100" \
            | grep -oE '"browser_download_url": *"[^"]+"' \
            | grep -oE 'https://[^"]+/Vortex-linux-x86_64\.AppImage"' \
            | head -1 | tr -d '"')" || true
        if [[ -z "$url" ]]; then
            return 1
        fi
        echo "Downloading Vortex AppImage from $url"
        curl -sfL --retry 3 -o "$TMP/Vortex-linux-x86_64.AppImage" "$url"
        src="$TMP/Vortex-linux-x86_64.AppImage"
    fi
    echo "Staging Vortex AppImage from $src"
    cp "$src" "$TMP/staged.AppImage"
    echo "$VORTEX_SHA  $TMP/staged.AppImage" | sha256sum -c -
    mv "$TMP/staged.AppImage" "$ASSETS/vortex/Vortex-linux-x86_64.AppImage"
}

if [[ ! -f "$ASSETS/vortex/Vortex-linux-x86_64.AppImage" ]]; then
    if ! fetch_vortex; then
        echo "WARNING: Vortex AppImage not found (checked \$VORTEX_APPIMAGE, repo root, parent dir, $REPO_SLUG releases)" >&2
        echo "         The app will fail to install the client." >&2
    fi
fi

if [[ ! -f "$ASSETS/rootfs.tar.xz" ]]; then
    echo "Fetching rootfs..."
    curl --fail --location --retry 3 --retry-delay 5 -o "$TMP/rootfs.tar.xz" "$ROOTFS_URL"
    echo "$ROOTFS_SHA  $TMP/rootfs.tar.xz" | sha256sum -c -
    mv "$TMP/rootfs.tar.xz" "$ASSETS/rootfs.tar.xz"
fi

echo "Done"
