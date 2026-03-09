#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$HOME/.openclaw-android"

if [ -f "$HOME/.openclaw-android/scripts/lib.sh" ]; then
    # shellcheck source=/dev/null
    source "$HOME/.openclaw-android/scripts/lib.sh"
else
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BOLD='\033[1m'
    NC='\033[0m'
    PLATFORM_MARKER="$PROJECT_DIR/.platform"
    BASHRC_MARKER_START="# >>> OpenClaw on Android >>>"
    BASHRC_MARKER_END="# <<< OpenClaw on Android <<<"

    ask_yn() {
        local prompt="$1"
        local reply
        read -rp "$prompt [Y/n] " reply < /dev/tty
        [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
        return 0
    }

    detect_platform() {
        if [ -f "$PLATFORM_MARKER" ]; then
            cat "$PLATFORM_MARKER"
            return 0
        fi
        return 1
    }
fi

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Uninstaller${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

reply=""
read -rp "This will remove the installation. Continue? [y/N] " reply < /dev/tty
if [[ ! "$reply" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

step() {
    echo ""
    echo -e "${BOLD}[$1/7] $2${NC}"
    echo "----------------------------------------"
}

step 1 "Platform uninstall"
PLATFORM=$(detect_platform 2>/dev/null || true)
if [ -z "$PLATFORM" ]; then
    echo -e "${YELLOW}[SKIP]${NC} Platform not detected"
else
    PLATFORM_UNINSTALL="$PROJECT_DIR/platforms/$PLATFORM/uninstall.sh"
    if [ -f "$PLATFORM_UNINSTALL" ]; then
        bash "$PLATFORM_UNINSTALL"
    else
        echo -e "${YELLOW}[SKIP]${NC} Platform uninstall script not found: $PLATFORM_UNINSTALL"
    fi
fi

step 2 "code-server"
if pgrep -f "code-server" &>/dev/null; then
    pkill -f "code-server" || true
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

rmdir "$HOME/.local/bin" 2>/dev/null || true
rmdir "$HOME/.local/lib" 2>/dev/null || true
rmdir "$HOME/.local" 2>/dev/null || true

step 3 "Chromium"
if command -v chromium-browser &>/dev/null || command -v chromium &>/dev/null; then
    pkg uninstall -y chromium 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Removed Chromium"
else
    echo -e "${YELLOW}[SKIP]${NC} Chromium not installed"
fi

step 4 "oa and oaupdate commands"
if [ -f "${PREFIX:-}/bin/oa" ]; then
    rm -f "${PREFIX:-}/bin/oa"
    echo -e "${GREEN}[OK]${NC}   Removed ${PREFIX:-}/bin/oa"
else
    echo -e "${YELLOW}[SKIP]${NC} ${PREFIX:-}/bin/oa not found"
fi

if [ -f "${PREFIX:-}/bin/oaupdate" ]; then
    rm -f "${PREFIX:-}/bin/oaupdate"
    echo -e "${GREEN}[OK]${NC}   Removed ${PREFIX:-}/bin/oaupdate"
else
    echo -e "${YELLOW}[SKIP]${NC} ${PREFIX:-}/bin/oaupdate not found"
fi

step 5 "glibc components"
if command -v pacman &>/dev/null && pacman -Q glibc-runner &>/dev/null; then
    pacman -R glibc-runner --noconfirm || true
    echo -e "${GREEN}[OK]${NC}   Removed glibc-runner package"
else
    echo -e "${YELLOW}[SKIP]${NC} glibc-runner not installed"
fi

step 6 "shell configuration"
BASHRC="$HOME/.bashrc"
if [ -f "$BASHRC" ] && grep -qF "$BASHRC_MARKER_START" "$BASHRC"; then
    sed -i "/${BASHRC_MARKER_START//\//\\/}/,/${BASHRC_MARKER_END//\//\\/}/d" "$BASHRC"
    sed -i '/^$/{ N; /^\n$/d }' "$BASHRC"
    echo -e "${GREEN}[OK]${NC}   Removed environment block from $BASHRC"
else
    echo -e "${YELLOW}[SKIP]${NC} No environment block found in $BASHRC"
fi

step 7 "installation directory"

if [ -d "$PROJECT_DIR" ]; then
    if ask_yn "Remove installation directory (~/.openclaw-android)? Includes Node.js, patches, configs."; then
        rm -rf "$PROJECT_DIR"
        echo -e "${GREEN}[OK]${NC}   Removed $PROJECT_DIR"
    else
        echo -e "${YELLOW}[KEEP]${NC} Keeping $PROJECT_DIR"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} $PROJECT_DIR not found"
fi

echo ""
echo -e "${GREEN}${BOLD}Uninstall complete.${NC}"
echo "Restart your Termux session to clear environment variables."
echo ""
