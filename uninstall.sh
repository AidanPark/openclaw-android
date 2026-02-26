#!/usr/bin/env bash
# uninstall.sh - Remove OpenClaw on Android from Termux
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Uninstaller${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# Confirm
read -rp "This will remove OpenClaw and all related config. Continue? [y/N] " REPLY
if [[ ! "$REPLY" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

echo ""

# 1. Uninstall OpenClaw npm package
echo "Removing OpenClaw npm package..."
if command -v openclaw &>/dev/null; then
    npm uninstall -g openclaw 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   openclaw package removed"
else
    echo -e "${YELLOW}[SKIP]${NC} openclaw not installed"
fi

# 2. Remove code-server
echo ""
echo "Removing code-server..."
# Stop code-server if running
if pgrep -f "code-server" &>/dev/null; then
    pkill -f "code-server" 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Stopped running code-server"
fi

if ls "$HOME/.local/lib"/code-server-* &>/dev/null 2>&1; then
    rm -rf "$HOME/.local/lib"/code-server-*
    echo -e "${GREEN}[OK]${NC}   Removed code-server from ~/.local/lib"
else
    echo -e "${YELLOW}[SKIP]${NC} code-server not found in ~/.local/lib"
fi

if [ -f "$HOME/.local/bin/code-server" ] || [ -L "$HOME/.local/bin/code-server" ]; then
    rm -f "$HOME/.local/bin/code-server"
    echo -e "${GREEN}[OK]${NC}   Removed ~/.local/bin/code-server"
else
    echo -e "${YELLOW}[SKIP]${NC} ~/.local/bin/code-server not found"
fi

# Clean up empty directories
rmdir "$HOME/.local/bin" 2>/dev/null || true
rmdir "$HOME/.local/lib" 2>/dev/null || true
rmdir "$HOME/.local" 2>/dev/null || true

# 3. Remove oa and oaupdate commands
if [ -f "$PREFIX/bin/oa" ]; then
    rm -f "$PREFIX/bin/oa"
    echo -e "${GREEN}[OK]${NC}   Removed $PREFIX/bin/oa"
else
    echo -e "${YELLOW}[SKIP]${NC} $PREFIX/bin/oa not found"
fi

if [ -f "$PREFIX/bin/oaupdate" ]; then
    rm -f "$PREFIX/bin/oaupdate"
    echo -e "${GREEN}[OK]${NC}   Removed $PREFIX/bin/oaupdate"
else
    echo -e "${YELLOW}[SKIP]${NC} $PREFIX/bin/oaupdate not found"
fi

# 4. Remove openclaw-android directory
if [ -d "$HOME/.openclaw-android" ]; then
    rm -rf "$HOME/.openclaw-android"
    echo -e "${GREEN}[OK]${NC}   Removed $HOME/.openclaw-android"
else
    echo -e "${YELLOW}[SKIP]${NC} $HOME/.openclaw-android not found"
fi

# 5. Remove environment block from .bashrc
BASHRC="$HOME/.bashrc"
MARKER_START="# >>> OpenClaw on Android >>>"
MARKER_END="# <<< OpenClaw on Android <<<"

if [ -f "$BASHRC" ] && grep -qF "$MARKER_START" "$BASHRC"; then
    sed -i "/${MARKER_START//\//\\/}/,/${MARKER_END//\//\\/}/d" "$BASHRC"
    # Collapse consecutive blank lines left behind (preserve intentional single blank lines)
    sed -i '/^$/{ N; /^\n$/d }' "$BASHRC"
    echo -e "${GREEN}[OK]${NC}   Removed environment block from $BASHRC"
else
    echo -e "${YELLOW}[SKIP]${NC} No environment block found in $BASHRC"
fi

# 6. Clean up temp directory
if [ -d "$PREFIX/tmp/openclaw" ]; then
    rm -rf "$PREFIX/tmp/openclaw"
    echo -e "${GREEN}[OK]${NC}   Removed $PREFIX/tmp/openclaw"
fi

# 7. Optionally remove openclaw data
echo ""
if [ -d "$HOME/.openclaw" ]; then
    read -rp "Remove OpenClaw data directory ($HOME/.openclaw)? [y/N] " REPLY
    if [[ "$REPLY" =~ ^[Yy]$ ]]; then
        rm -rf "$HOME/.openclaw"
        echo -e "${GREEN}[OK]${NC}   Removed $HOME/.openclaw"
    else
        echo -e "${YELLOW}[KEEP]${NC} Keeping $HOME/.openclaw"
    fi
fi

echo ""
echo -e "${GREEN}${BOLD}Uninstall complete.${NC}"
echo "Restart your Termux session to clear environment variables."
echo ""
