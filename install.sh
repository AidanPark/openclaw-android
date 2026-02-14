#!/usr/bin/env bash
# install.sh - One-click installer for OpenClaw on Termux (Android)
# Usage: bash install.sh
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw for Android - Installer${NC}"
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
export NODE_OPTIONS="-r $HOME/.openclaw-android/patches/bionic-compat.js ${NODE_OPTIONS:-}"
export CONTAINER=1

# ─────────────────────────────────────────────
step 5 "Installing OpenClaw"

# Apply bionic-compat.js first (needed for npm install)
echo "Copying compatibility patches..."
mkdir -p "$HOME/.openclaw-android/patches"
cp "$SCRIPT_DIR/patches/bionic-compat.js" "$HOME/.openclaw-android/patches/bionic-compat.js"
echo -e "${GREEN}[OK]${NC}   bionic-compat.js installed"

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

# ─────────────────────────────────────────────
step 6 "Verifying Installation"
bash "$SCRIPT_DIR/tests/verify-install.sh"

# ─────────────────────────────────────────────
step 7 "Updating OpenClaw"
echo "Running: openclaw update"
echo ""
openclaw update

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
echo "To uninstall: bash ~/.openclaw-android/uninstall.sh"
echo ""
