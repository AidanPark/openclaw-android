#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"

echo "=== Removing OpenClaw Platform ==="
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/7] $2${NC}"
    echo "----------------------------------------"
}

step 1 "OpenClaw npm package"
if command -v npm &>/dev/null; then
    if npm list -g openclaw &>/dev/null; then
        npm uninstall -g openclaw
        echo -e "${GREEN}[OK]${NC}   openclaw package removed"
    else
        echo -e "${YELLOW}[SKIP]${NC} openclaw not installed"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} npm not found"
fi

step 2 "clawdhub npm package"
if command -v npm &>/dev/null; then
    if npm list -g clawdhub &>/dev/null; then
        npm uninstall -g clawdhub
        echo -e "${GREEN}[OK]${NC}   clawdhub package removed"
    else
        echo -e "${YELLOW}[SKIP]${NC} clawdhub not installed"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} npm not found"
fi

step 3 "OpenCode"
OPENCODE_INSTALLED=false
command -v opencode &>/dev/null && OPENCODE_INSTALLED=true

if [ "$OPENCODE_INSTALLED" = true ]; then
    if ask_yn "Remove OpenCode (AI coding assistant)?"; then
        if pgrep -f "ld.so.opencode" &>/dev/null; then
            pkill -f "ld.so.opencode" || true
            echo -e "${GREEN}[OK]${NC}   Stopped running OpenCode"
        fi
        [ -f "$HOME/.openclaw-android/bin/ld.so.opencode" ] && rm -f "$HOME/.openclaw-android/bin/ld.so.opencode" && echo -e "${GREEN}[OK]${NC}   Removed ld.so.opencode"
        # Clean up legacy tmp location
        [ -f "$PREFIX/tmp/ld.so.opencode" ] && rm -f "$PREFIX/tmp/ld.so.opencode"
        [ -f "$PREFIX/bin/opencode" ] && rm -f "$PREFIX/bin/opencode" && echo -e "${GREEN}[OK]${NC}   Removed opencode wrapper"
        [ -d "$HOME/.config/opencode" ] && rm -rf "$HOME/.config/opencode" && echo -e "${GREEN}[OK]${NC}   Removed ~/.config/opencode"
    else
        echo -e "${YELLOW}[KEEP]${NC} Keeping OpenCode"
    fi
fi

step 4 "Bun cleanup"
if [ ! -f "$PREFIX/bin/opencode" ] && [ -d "$HOME/.bun" ]; then
    rm -rf "$HOME/.bun"
    echo -e "${GREEN}[OK]${NC}   Removed ~/.bun"
else
    echo -e "${YELLOW}[SKIP]${NC} Bun is still required or not installed"
fi

step 5 "OpenClaw temporary files"
if [ -d "${PREFIX:-}/tmp/openclaw" ]; then
    rm -rf "${PREFIX:-}/tmp/openclaw"
    echo -e "${GREEN}[OK]${NC}   Removed ${PREFIX:-}/tmp/openclaw"
else
    echo -e "${YELLOW}[SKIP]${NC} ${PREFIX:-}/tmp/openclaw not found"
fi

step 6 "OpenClaw data"
if [ -d "$HOME/.openclaw" ]; then
    reply=""
    read -rp "Remove OpenClaw data directory (~/.openclaw)? [y/N] " reply < /dev/tty
    if [[ "$reply" =~ ^[Yy]$ ]]; then
        rm -rf "$HOME/.openclaw"
        echo -e "${GREEN}[OK]${NC}   Removed ~/.openclaw"
    else
        echo -e "${YELLOW}[KEEP]${NC} Keeping ~/.openclaw"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} ~/.openclaw not found"
fi

step 7 "AI CLI tools"
AI_TOOLS_FOUND=()
AI_TOOL_LABELS=()

if command -v claude &>/dev/null; then
    AI_TOOLS_FOUND+=("@anthropic-ai/claude-code")
    AI_TOOL_LABELS+=("Claude Code")
fi
if command -v gemini &>/dev/null; then
    AI_TOOLS_FOUND+=("@google/gemini-cli")
    AI_TOOL_LABELS+=("Gemini CLI")
fi
if command -v codex &>/dev/null; then
    AI_TOOLS_FOUND+=("@mmmbuto/codex-cli-termux")
    AI_TOOL_LABELS+=("Codex CLI (Termux)")
fi

if [ ${#AI_TOOLS_FOUND[@]} -eq 0 ]; then
    echo -e "${YELLOW}[SKIP]${NC} No AI CLI tools detected"
else
    echo "Installed AI CLI tools detected:"
    for label in "${AI_TOOL_LABELS[@]}"; do
        echo "  - $label"
    done

    reply=""
    read -rp "Remove these AI CLI tools? [y/N] " reply < /dev/tty
    if [[ "$reply" =~ ^[Yy]$ ]]; then
        for pkg in "${AI_TOOLS_FOUND[@]}"; do
            if npm uninstall -g "$pkg"; then
                echo -e "${GREEN}[OK]${NC}   Removed $pkg"
            else
                echo -e "${YELLOW}[WARN]${NC} Failed to remove $pkg"
            fi
        done
    else
        echo -e "${YELLOW}[KEEP]${NC} Keeping AI CLI tools"
    fi
fi
