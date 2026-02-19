#!/usr/bin/env bash
# install.sh - One-click installer for OpenClaw on Termux (Android)
# Usage: bash install.sh
set -euo pipefail

GREEN='\033[0;32m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Installer${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "This script installs OpenClaw on Termux without proot-distro."
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/7] $2${NC}"
    echo "----------------------------------------"
}

# ─────────────────────────────────────────────
step 1 "Environment Check"
bash "$SCRIPT_DIR/scripts/check-env.sh"

# ─────────────────────────────────────────────
step 2 "Installing Dependencies"
bash "$SCRIPT_DIR/scripts/install-deps.sh"

# ─────────────────────────────────────────────
step 3 "Setting Up Paths"
bash "$SCRIPT_DIR/scripts/setup-paths.sh"

# ─────────────────────────────────────────────
step 4 "Configuring Environment Variables"
bash "$SCRIPT_DIR/scripts/setup-env.sh"

# Source the new environment for current session
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export NODE_OPTIONS="-r $HOME/.openclaw-android/patches/bionic-compat.js"
export CONTAINER=1
export CXXFLAGS="-include $HOME/.openclaw-android/patches/termux-compat.h"
export GYP_DEFINES="OS=linux android_ndk_path=$PREFIX"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

# ─────────────────────────────────────────────
step 5 "Installing OpenClaw"

# Apply bionic-compat.js first (needed for npm install)
echo "Copying compatibility patches..."
mkdir -p "$HOME/.openclaw-android/patches"
cp "$SCRIPT_DIR/patches/bionic-compat.js" "$HOME/.openclaw-android/patches/bionic-compat.js"
echo -e "${GREEN}[OK]${NC}   bionic-compat.js installed"

cp "$SCRIPT_DIR/patches/termux-compat.h" "$HOME/.openclaw-android/patches/termux-compat.h"
echo -e "${GREEN}[OK]${NC}   termux-compat.h installed"

# Install spawn.h stub if missing (needed for koffi/native module builds)
if [ ! -f "$PREFIX/include/spawn.h" ]; then
    cp "$SCRIPT_DIR/patches/spawn.h" "$PREFIX/include/spawn.h"
    echo -e "${GREEN}[OK]${NC}   spawn.h stub installed"
else
    echo -e "${GREEN}[OK]${NC}   spawn.h already exists"
fi

# Copy update script
cp "$SCRIPT_DIR/update.sh" "$HOME/.openclaw-android/update.sh"
chmod +x "$HOME/.openclaw-android/update.sh"
echo -e "${GREEN}[OK]${NC}   update.sh installed"

echo ""
echo "Running: npm install -g openclaw@latest"
echo "This may take several minutes..."
echo ""

npm install -g openclaw@latest

echo ""
echo -e "${GREEN}[OK]${NC}   OpenClaw installed"

# Apply path patches to installed modules
echo ""
bash "$SCRIPT_DIR/patches/apply-patches.sh"

# Build sharp for image processing (non-critical)
echo ""
bash "$SCRIPT_DIR/scripts/build-sharp.sh"

# ─────────────────────────────────────────────
step 6 "Verifying Installation"
bash "$SCRIPT_DIR/tests/verify-install.sh"

# ─────────────────────────────────────────────
step 7 "Updating OpenClaw"
echo "Running: openclaw update"
echo ""
openclaw update || true

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${GREEN}${BOLD}  Installation Complete!${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo -e "  OpenClaw $(openclaw --version)"
echo ""
echo "Next step:"
echo "  Run 'openclaw onboard' to start setup."
echo ""
echo "To update:    curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/update.sh | bash"
echo "To uninstall: bash ~/.openclaw-android/uninstall.sh"
echo ""
