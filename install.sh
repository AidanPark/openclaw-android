#!/usr/bin/env bash
# install.sh - One-click installer for OpenClaw on Termux (Android)
# Usage: bash install.sh
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OA_VERSION="0.8.2"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Installer v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "This script installs OpenClaw on Termux without proot-distro."
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/9] $2${NC}"
    echo "----------------------------------------"
}

# ─────────────────────────────────────────────
step 1 "Environment Check"

# Enable background kill prevention (Termux wake lock)
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Termux wake lock enabled"
fi
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
export PATH="$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export NODE_OPTIONS="-r $HOME/.openclaw-android/patches/bionic-compat.js"
export CONTAINER=1
export CFLAGS="-Wno-error=implicit-function-declaration"
export CXXFLAGS="-include $HOME/.openclaw-android/patches/termux-compat.h"
export GYP_DEFINES="OS=linux android_ndk_path=$PREFIX"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"

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

# Install oa CLI command (oa.sh → $PREFIX/bin/oa)
cp "$SCRIPT_DIR/oa.sh" "$PREFIX/bin/oa"
chmod +x "$PREFIX/bin/oa"
echo -e "${GREEN}[OK]${NC}   oa command installed"

# Install oaupdate command (update.sh wrapper → $PREFIX/bin/oaupdate)
cp "$SCRIPT_DIR/update.sh" "$PREFIX/bin/oaupdate"
chmod +x "$PREFIX/bin/oaupdate"
echo -e "${GREEN}[OK]${NC}   oaupdate command installed"

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

# Install clawhub (skill manager) and fix undici dependency
echo ""
echo "Installing clawhub (skill manager)..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "${GREEN}[OK]${NC}   clawhub installed"
    # Node.js v24+ on Termux doesn't bundle undici; clawhub needs it
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "Installing undici dependency for clawhub..."
        if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
            echo -e "${GREEN}[OK]${NC}   undici installed for clawhub"
        else
            echo -e "${YELLOW}[WARN]${NC} undici installation failed (clawhub may not work)"
        fi
    fi
else
    echo -e "${YELLOW}[WARN]${NC} clawhub installation failed (non-critical)"
    echo "       Retry manually: npm i -g clawdhub"
fi

# ─────────────────────────────────────────────
step 6 "Installing code-server (IDE)"
echo "Installing code-server (browser-based IDE)..."
# Copy argon2 stub needed by the installer
mkdir -p "$HOME/.openclaw-android/patches"
cp "$SCRIPT_DIR/patches/argon2-stub.js" "$HOME/.openclaw-android/patches/argon2-stub.js"
echo -e "${GREEN}[OK]${NC}   argon2-stub.js installed"

if bash "$SCRIPT_DIR/scripts/install-code-server.sh" install; then
    echo -e "${GREEN}[OK]${NC}   code-server installation step complete"
else
    echo -e "${YELLOW}[WARN]${NC} code-server installation failed (non-critical)"
fi

# ─────────────────────────────────────────────
step 7 "AI CLI Tools (Optional)"
bash "$SCRIPT_DIR/scripts/install-ai-tools.sh" || true

# ─────────────────────────────────────────────
step 8 "Verifying Installation"
bash "$SCRIPT_DIR/tests/verify-install.sh"

# ─────────────────────────────────────────────
step 9 "Updating OpenClaw"
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
echo -e "${BOLD}Manage with the 'oa' command:${NC}"
echo "  oa --update       Update OpenClaw and patches"
echo "  oa --status       Show installation status"
echo "  oa ide            Start code-server (browser IDE)"
echo "  oa --uninstall    Remove OpenClaw on Android"
echo "  oa --help         Show all options"
echo ""
