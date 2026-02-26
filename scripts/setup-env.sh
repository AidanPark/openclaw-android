#!/usr/bin/env bash
# setup-env.sh - Configure environment variables for OpenClaw in Termux
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== Setting Up Environment Variables ==="
echo ""

BASHRC="$HOME/.bashrc"
MARKER_START="# >>> OpenClaw on Android >>>"
MARKER_END="# <<< OpenClaw on Android <<<"

COMPAT_PATH="$HOME/.openclaw-android/patches/bionic-compat.js"

COMPAT_HEADER="$HOME/.openclaw-android/patches/termux-compat.h"

ENV_BLOCK="${MARKER_START}
export PATH=\"\$HOME/.local/bin:\$PATH\"
export TMPDIR=\"\$PREFIX/tmp\"
export TMP=\"\$TMPDIR\"
export TEMP=\"\$TMPDIR\"
export NODE_OPTIONS=\"-r $COMPAT_PATH\"
export CONTAINER=1
export CFLAGS=\"-Wno-error=implicit-function-declaration\"
export CXXFLAGS=\"-include $COMPAT_HEADER\"
export GYP_DEFINES=\"OS=linux android_ndk_path=\$PREFIX\"
export CPATH=\"\$PREFIX/include/glib-2.0:\$PREFIX/lib/glib-2.0/include\"
export CLAWDHUB_WORKDIR=\"\$HOME/.openclaw/workspace\"
${MARKER_END}"

# Create .bashrc if it doesn't exist
touch "$BASHRC"

# Check if block already exists
if grep -qF "$MARKER_START" "$BASHRC"; then
    echo -e "${YELLOW}[SKIP]${NC} Environment block already exists in $BASHRC"
    echo "       Removing old block and re-adding..."
    # Remove old block
    sed -i "/${MARKER_START//\//\\/}/,/${MARKER_END//\//\\/}/d" "$BASHRC"
fi

# Append environment block
echo "" >> "$BASHRC"
echo "$ENV_BLOCK" >> "$BASHRC"
echo -e "${GREEN}[OK]${NC}   Added environment variables to $BASHRC"

echo ""
echo "Variables configured:"
echo "  PATH=\$HOME/.local/bin:\$PATH"
echo "  TMPDIR=\$PREFIX/tmp"
echo "  TMP=\$TMPDIR"
echo "  TEMP=\$TMPDIR"
echo "  NODE_OPTIONS=\"-r $COMPAT_PATH\""
echo "  CONTAINER=1  (suppresses systemd checks)"
echo "  CFLAGS=\"-Wno-error=...\"  (Clang implicit-function-declaration fix)"
echo "  CXXFLAGS=\"-include ...termux-compat.h\"  (native build fixes)"
echo "  GYP_DEFINES=\"OS=linux ...\"  (node-gyp Android override)"
echo "  CPATH=\"...glib-2.0...\"  (sharp header paths)"
echo "  CLAWDHUB_WORKDIR=\"\$HOME/.openclaw/workspace\"  (clawhub skill install path)"

# Source for current session
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export NODE_OPTIONS="-r $COMPAT_PATH"
export CONTAINER=1
export CFLAGS="-Wno-error=implicit-function-declaration"
export CXXFLAGS="-include $COMPAT_HEADER"
export GYP_DEFINES="OS=linux android_ndk_path=$PREFIX"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"

# Create ar symlink if missing (Termux provides llvm-ar but not ar)
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
    echo -e "${GREEN}[OK]${NC}   Created ar → llvm-ar symlink"
fi

echo ""
echo -e "${GREEN}Environment setup complete.${NC}"
