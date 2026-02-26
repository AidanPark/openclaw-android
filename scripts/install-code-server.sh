#!/usr/bin/env bash
# install-code-server.sh - Install or update code-server (browser IDE) on Termux
# Usage: bash install-code-server.sh [install|update]
#
# Workarounds applied:
#   1. Replace bundled glibc node with Termux node
#   2. Patch argon2 native module with JS stub (--auth none makes it unused)
#   3. Ignore tar hard link errors (Android restriction) and recover .node files
#
# This script is WARN-level: failure does not abort the parent installer.
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

MODE="${1:-install}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="$HOME/.local/lib"
BIN_DIR="$HOME/.local/bin"

# ── Helper ────────────────────────────────────

fail_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    exit 0
}

# ── Pre-checks ────────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    fail_warn "Not running in Termux (\$PREFIX not set)"
fi

if ! command -v node &>/dev/null; then
    fail_warn "node not found — code-server requires Node.js"
fi

if ! command -v curl &>/dev/null; then
    fail_warn "curl not found — cannot download code-server"
fi

# ── Check current installation ────────────────

CURRENT_VERSION=""
if [ -x "$BIN_DIR/code-server" ]; then
    CURRENT_VERSION=$("$BIN_DIR/code-server" --version 2>/dev/null | head -1 || true)
fi

# ── Determine target version ──────────────────

if [ "$MODE" = "install" ] && [ -n "$CURRENT_VERSION" ]; then
    echo -e "${GREEN}[SKIP]${NC} code-server already installed ($CURRENT_VERSION)"
    exit 0
fi

# Fetch latest version from GitHub API
echo "Checking latest code-server version..."
LATEST_VERSION=$(curl -sfL --max-time 10 \
    "https://api.github.com/repos/coder/code-server/releases/latest" \
    | grep '"tag_name"' | head -1 | sed 's/.*"v\([^"]*\)".*/\1/') || true

if [ -z "$LATEST_VERSION" ]; then
    fail_warn "Failed to fetch latest code-server version from GitHub"
fi

echo "  Latest: v$LATEST_VERSION"

if [ "$MODE" = "update" ] && [ -n "$CURRENT_VERSION" ]; then
    if [ "$CURRENT_VERSION" = "$LATEST_VERSION" ]; then
        echo -e "${GREEN}[SKIP]${NC} code-server $CURRENT_VERSION is already the latest"
        exit 0
    fi
    echo "  Current: v$CURRENT_VERSION → updating to v$LATEST_VERSION"
fi

VERSION="$LATEST_VERSION"

# ── Download ──────────────────────────────────

TARBALL="code-server-${VERSION}-linux-arm64.tar.gz"
DOWNLOAD_URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${TARBALL}"
TMP_DIR=$(mktemp -d "$PREFIX/tmp/code-server-install.XXXXXX") || fail_warn "Failed to create temp directory"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Downloading code-server v${VERSION}..."
if ! curl -fL --max-time 300 "$DOWNLOAD_URL" -o "$TMP_DIR/$TARBALL"; then
    fail_warn "Failed to download code-server v${VERSION}"
fi
echo -e "${GREEN}[OK]${NC}   Downloaded $TARBALL"

# ── Extract (ignore hard link errors) ─────────

echo "Extracting..."
# Android's filesystem does not support hard links, so tar will report errors
# for hardlinked .node files. We extract what we can and recover them below.
tar -xzf "$TMP_DIR/$TARBALL" -C "$TMP_DIR" 2>/dev/null || true

EXTRACTED_DIR="$TMP_DIR/code-server-${VERSION}-linux-arm64"
if [ ! -d "$EXTRACTED_DIR" ]; then
    fail_warn "Extraction failed — directory not found"
fi

# ── Recover hard-linked .node files ───────────
# The obj.target/ directories contain the original .node files that tar
# couldn't hard-link into Release/. Copy them manually.

find "$EXTRACTED_DIR" -path "*/obj.target/*.node" -type f 2>/dev/null | while read -r OBJ_FILE; do
    # obj.target/foo.node → Release/foo.node
    RELEASE_DIR="$(dirname "$(dirname "$OBJ_FILE")")/Release"
    BASENAME="$(basename "$OBJ_FILE")"
    if [ -d "$RELEASE_DIR" ] && [ ! -f "$RELEASE_DIR/$BASENAME" ]; then
        cp "$OBJ_FILE" "$RELEASE_DIR/$BASENAME"
    fi
done
echo -e "${GREEN}[OK]${NC}   Extracted and recovered .node files"

# ── Install to ~/.local/lib ───────────────────

mkdir -p "$INSTALL_DIR" "$BIN_DIR"

# Remove previous code-server versions
rm -rf "$INSTALL_DIR"/code-server-*

# Move extracted directory to install location
mv "$EXTRACTED_DIR" "$INSTALL_DIR/code-server-${VERSION}"
echo -e "${GREEN}[OK]${NC}   Installed to $INSTALL_DIR/code-server-${VERSION}"

CS_DIR="$INSTALL_DIR/code-server-${VERSION}"

# ── Replace bundled node with Termux node ─────
# The standalone release bundles a glibc-linked node binary that cannot
# run on Termux (Bionic libc). Swap it with the system node.

if [ -f "$CS_DIR/lib/node" ] || [ -L "$CS_DIR/lib/node" ]; then
    rm -f "$CS_DIR/lib/node"
fi
ln -s "$PREFIX/bin/node" "$CS_DIR/lib/node"
echo -e "${GREEN}[OK]${NC}   Replaced bundled node → Termux node"

# ── Patch argon2 native module ────────────────
# argon2 ships a .node binary compiled against glibc. Since we run
# code-server with --auth none, argon2 is never called. Replace the
# module entry point with a JS stub.

ARGON2_STUB=""
# Check multiple possible locations for the stub
if [ -f "$SCRIPT_DIR/../patches/argon2-stub.js" ]; then
    ARGON2_STUB="$SCRIPT_DIR/../patches/argon2-stub.js"
elif [ -f "$HOME/.openclaw-android/patches/argon2-stub.js" ]; then
    ARGON2_STUB="$HOME/.openclaw-android/patches/argon2-stub.js"
fi

if [ -n "$ARGON2_STUB" ]; then
    # Find argon2 module entry point in code-server
    ARGON2_INDEX=$(find "$CS_DIR" -path "*/argon2/argon2.js" -type f 2>/dev/null | head -1 || true)
    if [ -z "$ARGON2_INDEX" ]; then
        ARGON2_INDEX=$(find "$CS_DIR" -path "*/node_modules/argon2/index.js" -type f 2>/dev/null | head -1 || true)
    fi
    if [ -n "$ARGON2_INDEX" ]; then
        cp "$ARGON2_STUB" "$ARGON2_INDEX"
        echo -e "${GREEN}[OK]${NC}   Patched argon2 module with JS stub"
    else
        echo -e "${YELLOW}[WARN]${NC} argon2 module not found in code-server (may not be needed)"
    fi
else
    echo -e "${YELLOW}[WARN]${NC} argon2-stub.js not found — skipping argon2 patch"
fi

# ── Create symlink ────────────────────────────

rm -f "$BIN_DIR/code-server"
ln -s "$CS_DIR/bin/code-server" "$BIN_DIR/code-server"
echo -e "${GREEN}[OK]${NC}   Symlinked $BIN_DIR/code-server"

# ── Verify ────────────────────────────────────

# Add ~/.local/bin to PATH for this session so we can verify with just "code-server"
export PATH="$BIN_DIR:$PATH"

echo ""
if code-server --version &>/dev/null; then
    INSTALLED_VER=$(code-server --version 2>/dev/null | head -1 || true)
    echo -e "${GREEN}[OK]${NC}   code-server ${INSTALLED_VER:-unknown} installed successfully"
else
    echo -e "${YELLOW}[WARN]${NC} code-server installed but --version check failed"
    echo "       This may work once ~/.local/bin is on PATH (restart shell or: source ~/.bashrc)"
fi
