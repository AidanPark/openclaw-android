#!/usr/bin/env bash
# install.sh - One-click installer for OpenClaw on Termux (Android)
# Architecture: glibc-based (grun + proot for Bun standalone)
# Usage: bash install.sh
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OA_VERSION="1.0.1"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Installer v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "This script installs OpenClaw on Termux with glibc environment."
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/11] $2${NC}"
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
step 2 "Installing Base Dependencies"
bash "$SCRIPT_DIR/scripts/install-deps.sh"

# ─────────────────────────────────────────────
step 3 "Installing glibc Environment"
bash "$SCRIPT_DIR/scripts/install-glibc-env.sh"

# ─────────────────────────────────────────────
step 4 "Setting Up Paths"
bash "$SCRIPT_DIR/scripts/setup-paths.sh"

# ─────────────────────────────────────────────
step 5 "Configuring Environment Variables"
bash "$SCRIPT_DIR/scripts/setup-env.sh"

# Source the new environment for current session
GLIBC_NODE_DIR="$HOME/.openclaw-android/node"
export PATH="$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export CONTAINER=1
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
export OA_GLIBC=1

# ─────────────────────────────────────────────
step 6 "Installing OpenClaw"

# Copy glibc-compat.js (needed for Node.js runtime patches)
echo "Copying compatibility patches..."
mkdir -p "$HOME/.openclaw-android/patches"
cp "$SCRIPT_DIR/patches/glibc-compat.js" "$HOME/.openclaw-android/patches/glibc-compat.js"
echo -e "${GREEN}[OK]${NC}   glibc-compat.js installed"

# Install oa CLI command (oa.sh → $PREFIX/bin/oa)
cp "$SCRIPT_DIR/oa.sh" "$PREFIX/bin/oa"
chmod +x "$PREFIX/bin/oa"
echo -e "${GREEN}[OK]${NC}   oa command installed"

# Install oaupdate command (update.sh wrapper → $PREFIX/bin/oaupdate)
cp "$SCRIPT_DIR/update.sh" "$PREFIX/bin/oaupdate"
chmod +x "$PREFIX/bin/oaupdate"
echo -e "${GREEN}[OK]${NC}   oaupdate command installed"

# Copy uninstall.sh to openclaw-android directory (for oa --uninstall)
cp "$SCRIPT_DIR/uninstall.sh" "$HOME/.openclaw-android/uninstall.sh"
chmod +x "$HOME/.openclaw-android/uninstall.sh"
echo -e "${GREEN}[OK]${NC}   uninstall.sh installed"

# Set CPATH for native module builds (sharp needs glib-2.0 headers)
# These are in Termux-specific subdirectories that compilers don't search by default
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

echo ""
echo "Running: npm install -g openclaw@latest --ignore-scripts"
echo "This may take several minutes..."
echo ""

# Use --ignore-scripts to skip postinstall scripts that attempt native compilation.
# Prebuilt binaries (sharp, koffi, node-llama-cpp) are used instead.
npm install -g openclaw@latest --ignore-scripts

echo ""
echo -e "${GREEN}[OK]${NC}   OpenClaw installed"

# Apply path patches to installed modules
echo ""
bash "$SCRIPT_DIR/patches/apply-patches.sh"

# Install clawdhub (skill manager) and fix undici dependency
echo ""
echo "Installing clawdhub (skill manager)..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "${GREEN}[OK]${NC}   clawdhub installed"
    # Node.js v24+ on Termux doesn't bundle undici; clawdhub needs it
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "Installing undici dependency for clawdhub..."
        if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
            echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
        else
            echo -e "${YELLOW}[WARN]${NC} undici installation failed (clawdhub may not work)"
        fi
    fi
else
    echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
    echo "       Retry manually: npm i -g clawdhub"
fi

# ─────────────────────────────────────────────
step 7 "Installing code-server (IDE)"
echo "Installing code-server (browser-based IDE)..."
# Copy argon2 stub (may still be needed if glibc argon2 doesn't work)
mkdir -p "$HOME/.openclaw-android/patches"
cp "$SCRIPT_DIR/patches/argon2-stub.js" "$HOME/.openclaw-android/patches/argon2-stub.js"
echo -e "${GREEN}[OK]${NC}   argon2-stub.js installed"

if bash "$SCRIPT_DIR/scripts/install-code-server.sh" install; then
    echo -e "${GREEN}[OK]${NC}   code-server installation step complete"
else
    echo -e "${YELLOW}[WARN]${NC} code-server installation failed (non-critical)"
fi

# ─────────────────────────────────────────────
step 8 "Installing OpenCode (Optional)"

OPENCODE_FLAGS=""
SKIP_OPENCODE=false
if [ -t 0 ]; then
    echo ""
    read -rp "Install OpenCode (AI coding assistant)? [Y/n] " REPLY
    if [[ "$REPLY" =~ ^[Nn]$ ]]; then
        SKIP_OPENCODE=true
        echo -e "${YELLOW}[SKIP]${NC} Skipping OpenCode installation"
    else
        read -rp "Install oh-my-opencode (OpenCode plugin framework)? [Y/n] " REPLY
        if [[ "$REPLY" =~ ^[Nn]$ ]]; then
            OPENCODE_FLAGS="--no-omo"
        fi
    fi
fi

if [ "$SKIP_OPENCODE" = false ]; then
    if bash "$SCRIPT_DIR/scripts/install-opencode.sh" $OPENCODE_FLAGS; then
        echo -e "${GREEN}[OK]${NC}   OpenCode installation step complete"
    else
        echo -e "${YELLOW}[WARN]${NC} OpenCode installation failed (non-critical)"
    fi
fi

# ─────────────────────────────────────────────
step 9 "AI CLI Tools (Optional)"

# AI tools installer has its own tty detection — skips automatically in non-interactive mode
if [ -f "$SCRIPT_DIR/scripts/install-ai-tools.sh" ]; then
    bash "$SCRIPT_DIR/scripts/install-ai-tools.sh" || true
else
    echo -e "${YELLOW}[SKIP]${NC} install-ai-tools.sh not found"
fi

# ─────────────────────────────────────────────
step 10 "Verifying Installation"
bash "$SCRIPT_DIR/tests/verify-install.sh"

# ─────────────────────────────────────────────
step 11 "Updating OpenClaw"
echo "Running: openclaw update"
echo "  (This includes building native modules and may take 5-10 minutes)"
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
echo "  oa --uninstall    Remove OpenClaw on Android"
echo "  oa --help         Show all options"
echo ""
