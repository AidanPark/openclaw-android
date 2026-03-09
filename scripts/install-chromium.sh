#!/usr/bin/env bash
# install-chromium.sh - Install Chromium for OpenClaw browser automation
# Usage: bash install-chromium.sh [install|update]
#
# What it does:
#   1. Install x11-repo (Termux X11 packages repository)
#   2. Install chromium package
#   3. Configure OpenClaw browser settings in openclaw.json
#   4. Verify installation
#
# Browser automation allows OpenClaw to control a headless Chromium browser
# for web scraping, screenshots, and automated browsing tasks.
#
# This script is WARN-level: failure does not abort the parent installer.
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

MODE="${1:-install}"

# ── Helper ────────────────────────────────────

fail_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    exit 0
}

# ── Detect Chromium binary path ───────────────

detect_chromium_bin() {
    for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
        if [ -x "$bin" ]; then
            echo "$bin"
            return 0
        fi
    done
    return 1
}

# ── Pre-checks ────────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    fail_warn "Not running in Termux (\$PREFIX not set)"
fi

# ── Check current installation ────────────────

SKIP_PKG_INSTALL=false
if CHROMIUM_BIN=$(detect_chromium_bin); then
    if [ "$MODE" = "install" ]; then
        echo -e "${GREEN}[SKIP]${NC} Chromium already installed ($CHROMIUM_BIN)"
        SKIP_PKG_INSTALL=true
    fi
fi

# ── Step 1: Install x11-repo + Chromium ───────

if [ "$SKIP_PKG_INSTALL" = false ]; then
    echo "Installing x11-repo (Termux X11 packages)..."
    if ! pkg install -y x11-repo; then
        fail_warn "Failed to install x11-repo"
    fi
    echo -e "${GREEN}[OK]${NC}   x11-repo installed"

    echo "Installing Chromium..."
    echo "  (This is a large package (~400MB) — may take several minutes)"
    if ! pkg install -y chromium; then
        fail_warn "Failed to install Chromium"
    fi
    echo -e "${GREEN}[OK]${NC}   Chromium installed"
fi

# ── Step 2: Detect binary path ────────────────

if ! CHROMIUM_BIN=$(detect_chromium_bin); then
    fail_warn "Chromium binary not found after installation"
fi

# ── Step 3: Configure OpenClaw browser settings

echo "Configuring OpenClaw browser settings..."

if command -v node &>/dev/null; then
    export CHROMIUM_BIN
    if node << 'NODESCRIPT'
const fs = require('fs');
const path = require('path');

const configDir = path.join(process.env.HOME, '.openclaw');
const configPath = path.join(configDir, 'openclaw.json');

let config = {};
try {
    config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
} catch {
    // File doesn't exist or invalid — start fresh
}

if (!config.browser) config.browser = {};
config.browser.executablePath = process.env.CHROMIUM_BIN;
if (config.browser.headless === undefined) config.browser.headless = true;
if (config.browser.noSandbox === undefined) config.browser.noSandbox = true;

fs.mkdirSync(configDir, { recursive: true });
fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + '\n');
console.log('  Written to ' + configPath);
NODESCRIPT
    then
        echo -e "${GREEN}[OK]${NC}   openclaw.json browser settings configured"
    else
        echo -e "${YELLOW}[WARN]${NC} Could not update openclaw.json automatically"
        echo "       Add this to ~/.openclaw/openclaw.json manually:"
        echo "       \"browser\": {\"executablePath\": \"$CHROMIUM_BIN\", \"headless\": true, \"noSandbox\": true}"
    fi
else
    echo -e "${YELLOW}[INFO]${NC} Node.js not available — manual browser configuration needed"
    echo "       After running 'openclaw onboard', add to ~/.openclaw/openclaw.json:"
    echo "       \"browser\": {\"executablePath\": \"$CHROMIUM_BIN\", \"headless\": true, \"noSandbox\": true}"
fi

# ── Step 4: Verify ────────────────────────────

echo ""
if [ -x "$CHROMIUM_BIN" ]; then
    CHROMIUM_VER=$("$CHROMIUM_BIN" --version 2>/dev/null || echo "unknown version")
    echo -e "${GREEN}[OK]${NC}   $CHROMIUM_VER"
    echo "       Binary: $CHROMIUM_BIN"
    echo ""
    echo -e "${YELLOW}[NOTE]${NC} Chromium uses ~300-500MB RAM at runtime."
    echo "       Devices with less than 4GB RAM may experience slowdowns."
else
    fail_warn "Chromium verification failed — binary not executable"
fi

# ── Step 5: Ensure image processing works ────
#
# Browser screenshots require sharp for image optimization before sending
# to Discord/Slack. Run build-sharp.sh to enable it (idempotent — skips
# if sharp is already working).

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/build-sharp.sh" ]; then
    echo ""
    bash "$SCRIPT_DIR/build-sharp.sh" || true
elif [ -f "$HOME/.openclaw-android/scripts/build-sharp.sh" ]; then
    echo ""
    bash "$HOME/.openclaw-android/scripts/build-sharp.sh" || true
fi
