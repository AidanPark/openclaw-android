#!/usr/bin/env bash
# setup-env.sh - Configure environment variables for OpenClaw in Termux (glibc architecture)
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== Setting Up Environment Variables ==="
echo ""

BASHRC="$HOME/.bashrc"
MARKER_START="# >>> OpenClaw on Android >>>"
MARKER_END="# <<< OpenClaw on Android <<<"

GLIBC_NODE_DIR="$HOME/.openclaw-android/node"
COMPAT_PATH="$HOME/.openclaw-android/patches/glibc-compat.js"

ENV_BLOCK="${MARKER_START}
export PATH=\"$GLIBC_NODE_DIR/bin:\$HOME/.local/bin:\$PATH\"
export TMPDIR=\"\$PREFIX/tmp\"
export TMP=\"\$TMPDIR\"
export TEMP=\"\$TMPDIR\"
export CONTAINER=1
export CLAWDHUB_WORKDIR=\"\$HOME/.openclaw/workspace\"
export CPATH=\"\$PREFIX/include/glib-2.0:\$PREFIX/lib/glib-2.0/include\"
export OA_GLIBC=1
${MARKER_END}"

# Create .bashrc if it doesn't exist
touch "$BASHRC"

# Check if block already exists
if grep -qF "$MARKER_START" "$BASHRC"; then
    echo -e "${GREEN}[OK]${NC}   Refreshing environment block in $BASHRC"
    # Remove old block
    sed -i "/${MARKER_START//\//\\/}/,/${MARKER_END//\//\\/}/d" "$BASHRC"
fi

# Append environment block
echo "" >> "$BASHRC"
echo "$ENV_BLOCK" >> "$BASHRC"
echo -e "${GREEN}[OK]${NC}   Added environment variables to $BASHRC"

echo ""
echo "Variables configured:"
echo "  PATH=$GLIBC_NODE_DIR/bin:\$HOME/.local/bin:\$PATH"
echo "  TMPDIR=\$PREFIX/tmp"
echo "  TMP=\$TMPDIR"
echo "  TEMP=\$TMPDIR"
echo "  CONTAINER=1  (suppresses systemd checks)"
echo "  CLAWDHUB_WORKDIR=\"\$HOME/.openclaw/workspace\"  (clawdhub skill install path)"
echo "  CPATH=\"\$PREFIX/include/glib-2.0:\$PREFIX/lib/glib-2.0/include\"  (native module builds)"
echo "  OA_GLIBC=1  (glibc architecture marker)"
echo ""
echo "Removed (no longer needed with glibc):"
echo "  NODE_OPTIONS  (handled by node wrapper auto-loading glibc-compat.js)"
echo "  CXXFLAGS      (glibc headers are complete)"
echo "  GYP_DEFINES   (glibc is standard Linux)"
echo "  CFLAGS        (glibc compiler is standard)"

# Source for current session
export PATH="$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export CONTAINER=1
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
export OA_GLIBC=1
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

# Create ar symlink if missing (Termux provides llvm-ar but not ar)
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
    echo -e "${GREEN}[OK]${NC}   Created ar → llvm-ar symlink"
fi

echo ""
echo -e "${GREEN}Environment setup complete.${NC}"
