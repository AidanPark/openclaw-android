#!/usr/bin/env bash
# update.sh - Lightweight updater for OpenClaw on Android (existing installations)
# Usage: curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/update.sh | bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
OPENCLAW_DIR="$HOME/.openclaw-android"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Updater${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/4] $2${NC}"
    echo "----------------------------------------"
}

# ─────────────────────────────────────────────
step 1 "Pre-flight Check"

# Check Termux
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Termux detected"

# Check existing OpenClaw installation
if ! command -v openclaw &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} openclaw command not found"
    echo "       Run the full installer first:"
    echo "       curl -sL $REPO_BASE/bootstrap.sh | bash"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   openclaw $(openclaw --version 2>/dev/null || echo "")"

# Create directory if it doesn't exist (older installations may not have it)
mkdir -p "$OPENCLAW_DIR"

# Check curl
if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

# ─────────────────────────────────────────────
step 2 "Installing New Packages"

# Install socat if not present
if command -v socat &>/dev/null; then
    echo -e "${YELLOW}[SKIP]${NC} socat already installed"
else
    echo "Installing socat..."
    pkg install -y socat
    echo -e "${GREEN}[OK]${NC}   socat installed"
fi

# ─────────────────────────────────────────────
step 3 "Downloading New Scripts"

# Download gateway-start.sh
mkdir -p "$OPENCLAW_DIR/scripts"
if curl -sfL "$REPO_BASE/scripts/gateway-start.sh" -o "$OPENCLAW_DIR/scripts/gateway-start.sh"; then
    chmod +x "$OPENCLAW_DIR/scripts/gateway-start.sh"
    echo -e "${GREEN}[OK]${NC}   gateway-start.sh updated"
else
    echo -e "${RED}[FAIL]${NC} Failed to download gateway-start.sh"
    exit 1
fi

# Download setup-env.sh (needed for .bashrc update)
TMPFILE=$(mktemp "$PREFIX/tmp/setup-env.XXXXXX.sh")
if curl -sfL "$REPO_BASE/scripts/setup-env.sh" -o "$TMPFILE"; then
    echo -e "${GREEN}[OK]${NC}   setup-env.sh downloaded"
else
    echo -e "${RED}[FAIL]${NC} Failed to download setup-env.sh"
    rm -f "$TMPFILE"
    exit 1
fi

# Download update.sh itself for future use
if curl -sfL "$REPO_BASE/update.sh" -o "$OPENCLAW_DIR/update.sh"; then
    chmod +x "$OPENCLAW_DIR/update.sh"
    echo -e "${GREEN}[OK]${NC}   update.sh saved to $OPENCLAW_DIR/"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to save update.sh (non-critical)"
fi

# ─────────────────────────────────────────────
step 4 "Updating Environment Variables"

# Run setup-env.sh to refresh .bashrc block
bash "$TMPFILE"
rm -f "$TMPFILE"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${GREEN}${BOLD}  Update Complete!${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "New features:"
echo "  - oca-gateway: Start gateway with LAN dashboard access"
echo ""
echo -e "${YELLOW}Run this to apply changes to the current session:${NC}"
echo ""
echo "  source ~/.bashrc"
echo ""
