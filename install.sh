#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Installer v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "This script installs OpenClaw on Termux with platform-aware architecture."
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/8] $2${NC}"
    echo "----------------------------------------"
}

step 1 "Environment Check"
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Termux wake lock enabled"
fi
bash "$SCRIPT_DIR/scripts/check-env.sh"

step 2 "Platform Selection"
SELECTED_PLATFORM="openclaw"
echo -e "${GREEN}[OK]${NC}   Platform: OpenClaw"
load_platform_config "$SELECTED_PLATFORM" "$SCRIPT_DIR"

step 3 "Optional Tools Selection (L3)"
INSTALL_TMUX=false
INSTALL_TTYD=false
INSTALL_DUFS=false
INSTALL_ANDROID_TOOLS=false
INSTALL_CODE_SERVER=false
INSTALL_OPENCODE=false
INSTALL_CLAUDE_CODE=false
INSTALL_GEMINI_CLI=false
INSTALL_CODEX_CLI=false
INSTALL_CHROMIUM=false

if ask_yn "Install tmux (terminal multiplexer)?"; then INSTALL_TMUX=true; fi
if ask_yn "Install ttyd (web terminal)?"; then INSTALL_TTYD=true; fi
if ask_yn "Install dufs (file server)?"; then INSTALL_DUFS=true; fi
if ask_yn "Install android-tools (adb)?"; then INSTALL_ANDROID_TOOLS=true; fi
if ask_yn "Install Chromium (browser automation for OpenClaw, ~400MB)?"; then INSTALL_CHROMIUM=true; fi
if ask_yn "Install code-server (browser IDE)?"; then INSTALL_CODE_SERVER=true; fi
if ask_yn "Install OpenCode (AI coding assistant)?"; then INSTALL_OPENCODE=true; fi
if ask_yn "Install Claude Code CLI?"; then INSTALL_CLAUDE_CODE=true; fi
if ask_yn "Install Gemini CLI?"; then INSTALL_GEMINI_CLI=true; fi
if ask_yn "Install Codex CLI?"; then INSTALL_CODEX_CLI=true; fi

step 4 "Core Infrastructure (L1)"
bash "$SCRIPT_DIR/scripts/install-infra-deps.sh"
bash "$SCRIPT_DIR/scripts/setup-paths.sh"

step 5 "Platform Runtime Dependencies (L2)"
[ "${PLATFORM_NEEDS_GLIBC:-false}" = true ] && bash "$SCRIPT_DIR/scripts/install-glibc.sh" || true
[ "${PLATFORM_NEEDS_NODEJS:-false}" = true ] && bash "$SCRIPT_DIR/scripts/install-nodejs.sh" || true
[ "${PLATFORM_NEEDS_BUILD_TOOLS:-false}" = true ] && bash "$SCRIPT_DIR/scripts/install-build-tools.sh" || true
[ "${PLATFORM_NEEDS_PROOT:-false}" = true ] && pkg install -y proot || true

# Source environment for current session (needed by platform install)
GLIBC_NODE_DIR="$PROJECT_DIR/node"
export PATH="$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export OA_GLIBC=1

step 6 "Platform Package Install (L2)"
bash "$SCRIPT_DIR/platforms/$SELECTED_PLATFORM/install.sh"

echo ""
echo -e "${BOLD}[6.5] Environment Variables + CLI + Marker${NC}"
echo "----------------------------------------"
bash "$SCRIPT_DIR/scripts/setup-env.sh"

PLATFORM_ENV_SCRIPT="$SCRIPT_DIR/platforms/$SELECTED_PLATFORM/env.sh"
if [ -f "$PLATFORM_ENV_SCRIPT" ]; then
    eval "$(bash "$PLATFORM_ENV_SCRIPT")"
fi

mkdir -p "$PROJECT_DIR"
echo "$SELECTED_PLATFORM" > "$PLATFORM_MARKER"

cp "$SCRIPT_DIR/oa.sh" "$PREFIX/bin/oa"
chmod +x "$PREFIX/bin/oa"
cp "$SCRIPT_DIR/update.sh" "$PREFIX/bin/oaupdate"
chmod +x "$PREFIX/bin/oaupdate"

cp "$SCRIPT_DIR/uninstall.sh" "$PROJECT_DIR/uninstall.sh"
chmod +x "$PROJECT_DIR/uninstall.sh"

mkdir -p "$PROJECT_DIR/scripts"
mkdir -p "$PROJECT_DIR/platforms"
cp "$SCRIPT_DIR/scripts/lib.sh" "$PROJECT_DIR/scripts/lib.sh"
cp "$SCRIPT_DIR/scripts/setup-env.sh" "$PROJECT_DIR/scripts/setup-env.sh"
rm -rf "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"
cp -R "$SCRIPT_DIR/platforms/$SELECTED_PLATFORM" "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"

step 7 "Install Optional Tools (L3)"
[ "$INSTALL_TMUX" = true ] && pkg install -y tmux || true
[ "$INSTALL_TTYD" = true ] && pkg install -y ttyd || true
[ "$INSTALL_DUFS" = true ] && pkg install -y dufs || true
[ "$INSTALL_ANDROID_TOOLS" = true ] && pkg install -y android-tools || true

[ "$INSTALL_CHROMIUM" = true ] && bash "$SCRIPT_DIR/scripts/install-chromium.sh" install || true

[ "$INSTALL_CODE_SERVER" = true ] && mkdir -p "$PROJECT_DIR/patches" && cp "$SCRIPT_DIR/patches/argon2-stub.js" "$PROJECT_DIR/patches/argon2-stub.js" && bash "$SCRIPT_DIR/scripts/install-code-server.sh" install || true

[ "$INSTALL_OPENCODE" = true ] && bash "$SCRIPT_DIR/scripts/install-opencode.sh" install || true

[ "$INSTALL_CLAUDE_CODE" = true ] && npm install -g @anthropic-ai/claude-code || true
[ "$INSTALL_GEMINI_CLI" = true ] && npm install -g @google/gemini-cli || true
[ "$INSTALL_CODEX_CLI" = true ] && npm install -g @openai/codex || true

step 8 "Verification"
bash "$SCRIPT_DIR/tests/verify-install.sh"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${GREEN}${BOLD}  Installation Complete!${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo -e "  $PLATFORM_NAME $($PLATFORM_VERSION_CMD 2>/dev/null || echo '')"
echo ""
echo "Next step:"
echo "  $PLATFORM_POST_INSTALL_MSG"
echo ""
