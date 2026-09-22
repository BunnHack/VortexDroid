#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS"

ROOTFS_URL="https://github.com/cetotos/PolyDroid2/releases/download/rootfs-1/rootfs.tar.xz"
ROOTFS_SHA="2a61930a4c2a8efe780a935f84df947640407594b8f9ec8bba960afb5bcd7d34"

# Vortex client AppImage (https://playvortex.io).
# Linux downloads are not on the website yet; place the AppImage next to this
# repo (or set VORTEX_APPIMAGE) and it will be staged into the assets.
VORTEX_SHA="1b51417661cb191ef7041b9ef590b17a5876e7564c60f0b006c69bd5175ccc8f"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ ! -f "$ASSETS/vortex/Vortex-linux-x86_64.AppImage" ]]; then
    SRC="${VORTEX_APPIMAGE:-$ROOT/../Vortex-linux-x86_64.AppImage}"
    if [[ ! -f "$SRC" ]]; then
        SRC="$ROOT/Vortex-linux-x86_64.AppImage"
    fi
    if [[ -f "$SRC" ]]; then
        echo "Staging Vortex AppImage from $SRC"
        mkdir -p "$ASSETS/vortex"
        cp "$SRC" "$TMP/Vortex-linux-x86_64.AppImage"
        echo "$VORTEX_SHA  $TMP/Vortex-linux-x86_64.AppImage" | sha256sum -c -
        mv "$TMP/Vortex-linux-x86_64.AppImage" "$ASSETS/vortex/Vortex-linux-x86_64.AppImage"
    else
        echo "WARNING: Vortex AppImage not found (looked in \$VORTEX_APPIMAGE, repo root, parent dir)" >&2
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
