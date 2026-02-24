#!/usr/bin/env bash
# check-env.sh - Verify Termux environment before installation
set -euo pipefail

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

ERRORS=0

echo "=== OpenClaw on Android - Environment Check ==="
echo ""

# 1. Check if running in Termux
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    echo "       This script is designed for Termux on Android."
    exit 1
else
    echo -e "${GREEN}[OK]${NC}   Termux detected (PREFIX=$PREFIX)"
fi

# 2. Check architecture
ARCH=$(uname -m)
echo -n "       Architecture: $ARCH"
if [ "$ARCH" = "aarch64" ]; then
    echo -e " ${GREEN}(recommended)${NC}"
elif [ "$ARCH" = "armv7l" ] || [ "$ARCH" = "arm" ]; then
    echo -e " ${YELLOW}(supported, but aarch64 recommended)${NC}"
elif [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "i686" ]; then
    echo -e " ${YELLOW}(emulator detected)${NC}"
else
    echo -e " ${YELLOW}(unknown, may not work)${NC}"
fi

# 3. Check disk space (need at least 500MB free)
AVAILABLE_MB=$(df "$PREFIX" 2>/dev/null | awk 'NR==2 {print int($4/1024)}')
if [ -n "$AVAILABLE_MB" ] && [ "$AVAILABLE_MB" -lt 500 ]; then
    echo -e "${RED}[FAIL]${NC} Insufficient disk space: ${AVAILABLE_MB}MB available (need 500MB+)"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${GREEN}[OK]${NC}   Disk space: ${AVAILABLE_MB:-unknown}MB available"
fi

# 4. Check if already installed
if command -v openclaw &>/dev/null; then
    CURRENT_VER=$(openclaw --version 2>/dev/null || echo "unknown")
    echo -e "${YELLOW}[INFO]${NC} OpenClaw already installed (version: $CURRENT_VER)"
    echo "       Re-running install will update/repair the installation."
fi

# 5. Check if Node.js is already installed and version
if command -v node &>/dev/null; then
    NODE_VER=$(node -v 2>/dev/null || echo "unknown")
    echo -e "${GREEN}[OK]${NC}   Node.js found: $NODE_VER"
    NODE_MAJOR="${NODE_VER%%.*}"
    NODE_MAJOR="${NODE_MAJOR#v}"
    if [ "$NODE_MAJOR" -lt 22 ] 2>/dev/null; then
        echo -e "${YELLOW}[WARN]${NC} Node.js >= 22 required. Will be upgraded during install."
    fi
else
    echo -e "${YELLOW}[INFO]${NC} Node.js not found. Will be installed."
fi

# 6. Check Phantom Process Killer (Android 12+, API 31+)
SDK_INT=$(getprop ro.build.version.sdk 2>/dev/null || echo "0")
if [ "$SDK_INT" -ge 31 ] 2>/dev/null; then
    PPK_VALUE=$(/system/bin/settings get global settings_enable_monitor_phantom_procs 2>/dev/null || echo "")
    if [ "$PPK_VALUE" = "false" ]; then
        echo -e "${GREEN}[OK]${NC}   Phantom Process Killer disabled"
    else
        echo -e "${YELLOW}[WARN]${NC} Phantom Process Killer is active"
        echo "       Background processes (openclaw, sshd, etc.) may be killed by Android."
        echo "       To disable, run these commands:"
        echo ""
        echo "       1. Enable 'Wireless debugging' in Developer options"
        echo "       2. Tap 'Pair device with pairing code' and run:"
        echo "          adb pair localhost:<PORT> <PAIRING_CODE>"
        echo "       3. Connect (use the port shown on Wireless debugging main screen):"
        echo "          adb connect localhost:<PORT>"
        echo "       4. Disable Phantom Process Killer:"
        echo "          adb shell \"settings put global settings_enable_monitor_phantom_procs false\""
        echo ""
    fi
fi

echo ""
if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}Environment check failed with $ERRORS error(s).${NC}"
    exit 1
else
    echo -e "${GREEN}Environment check passed.${NC}"
fi
