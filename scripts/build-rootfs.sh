#!/usr/bin/env bash
# build-rootfs.sh — Build the OpenClaw rootfs asset for the APK
#
# This script builds a pre-configured rootfs tarball that includes:
#   - Termux bootstrap (bash, coreutils, etc.)
#   - glibc runtime (ld-linux-aarch64.so.1 + libs)
#   - Node.js linux-arm64 (official binary, runs via glibc)
#   - OpenClaw (npm global install)
#   - CA certificates (SSL)
#   - DNS configuration (resolv.conf)
#
# The rootfs uses placeholder paths (__PREFIX__, __HOME__, etc.) that are
# replaced at runtime by env-init.sh when the app first launches.
#
# Requirements (host machine, Linux/macOS with Docker):
#   - Docker (for reproducible aarch64 environment)
#   - OR: run export-from-termux.sh directly on an Android device with Termux
#
# Usage:
#   ./scripts/build-rootfs.sh
#
# Output:
#   android/app/src/main/assets/rootfs-aarch64.tar.xz

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="${REPO_ROOT}/android/app/src/main/assets"
OUTPUT="${ASSETS_DIR}/rootfs-aarch64.tar.xz"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[build-rootfs]${NC} $*"; }
ok()   { echo -e "${GREEN}[ok]${NC} $*"; }
warn() { echo -e "${YELLOW}[warn]${NC} $*"; }
fail() { echo -e "${RED}[fail]${NC} $*"; exit 1; }

# ── Option A: Use existing export from Termux ─────────────────────────────────
if [ -f "${SCRIPT_DIR}/rootfs-aarch64.tar.xz" ]; then
    log "Found pre-built rootfs at scripts/rootfs-aarch64.tar.xz"
    cp "${SCRIPT_DIR}/rootfs-aarch64.tar.xz" "$OUTPUT"
    ok "Copied to ${OUTPUT}"
    exit 0
fi

# ── Option B: Build via Docker (aarch64 emulation) ───────────────────────────
if ! command -v docker >/dev/null 2>&1; then
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  rootfs-aarch64.tar.xz not found and Docker not available."
    echo ""
    echo "  To build the rootfs, choose one of:"
    echo ""
    echo "  OPTION A — Export from a real Android device with Termux:"
    echo "    1. Install Termux on your Android device"
    echo "    2. In Termux, run:"
    echo "         pkg install glibc-runner nodejs openclaw git ca-certificates"
    echo "         bash <(curl -fsSL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/post-setup.sh)"
    echo "    3. Copy scripts/export-from-termux.sh to the device and run it"
    echo "    4. Copy the output rootfs-aarch64.tar.xz to:"
    echo "         android/app/src/main/assets/rootfs-aarch64.tar.xz"
    echo ""
    echo "  OPTION B — Build via Docker (requires Docker Desktop):"
    echo "    Install Docker and re-run this script."
    echo ""
    echo "  OPTION C — Use the dynamic installer (post-setup.sh):"
    echo "    The app will download and install components at first launch."
    echo "    This requires internet access on first run."
    echo "════════════════════════════════════════════════════════════"
    echo ""
    exit 1
fi

log "Building rootfs via Docker (aarch64 emulation)..."

# Enable QEMU for aarch64 emulation
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes 2>/dev/null || true

STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT

# Build inside an aarch64 Ubuntu container
docker run --rm \
    --platform linux/arm64 \
    -v "${STAGING_DIR}:/output" \
    -v "${SCRIPT_DIR}:/scripts:ro" \
    ubuntu:22.04 \
    bash /scripts/docker-build-rootfs.sh

if [ -f "${STAGING_DIR}/rootfs-aarch64.tar.xz" ]; then
    mkdir -p "$ASSETS_DIR"
    cp "${STAGING_DIR}/rootfs-aarch64.tar.xz" "$OUTPUT"
    SIZE=$(du -sh "$OUTPUT" | cut -f1)
    ok "rootfs built: ${OUTPUT} (${SIZE})"
else
    fail "Docker build did not produce rootfs-aarch64.tar.xz"
fi
