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
if ask_yn "Install Codex CLI (Termux)?"; then INSTALL_CODEX_CLI=true; fi

step 4 "Core Infrastructure (L1)"
bash "$SCRIPT_DIR/scripts/install-infra-deps.sh"
bash "$SCRIPT_DIR/scripts/setup-paths.sh"

step 5 "Platform Runtime Dependencies (L2)"
if [ "${PLATFORM_NEEDS_GLIBC:-false}" = true ]; then bash "$SCRIPT_DIR/scripts/install-glibc.sh"; fi
if [ "${PLATFORM_NEEDS_NODEJS:-false}" = true ]; then bash "$SCRIPT_DIR/scripts/install-nodejs.sh"; fi
if [ "${PLATFORM_NEEDS_BUILD_TOOLS:-false}" = true ]; then bash "$SCRIPT_DIR/scripts/install-build-tools.sh"; fi
if [ "${PLATFORM_NEEDS_PROOT:-false}" = true ]; then pkg install -y proot; fi

# Source environment for current session (needed by platform install)
GLIBC_BIN_DIR="$PROJECT_DIR/bin"
GLIBC_NODE_DIR="$PROJECT_DIR/node"
export PATH="$GLIBC_BIN_DIR:$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export OA_GLIBC=1

# Auto-detect npm registry (session-scoped via NPM_CONFIG_REGISTRY env var).
# Does NOT write to ~/.npmrc — see CHANGELOG v1.0.24.
command -v resolve_npm_registry >/dev/null 2>&1 && resolve_npm_registry || true

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

# Copy post-setup.sh so ~/.bashrc auto-run hook can find it
cp "$SCRIPT_DIR/post-setup.sh" "$PROJECT_DIR/post-setup.sh"
chmod +x "$PROJECT_DIR/post-setup.sh"

mkdir -p "$PROJECT_DIR/scripts"
mkdir -p "$PROJECT_DIR/platforms"
cp "$SCRIPT_DIR/scripts/lib.sh" "$PROJECT_DIR/scripts/lib.sh"
cp "$SCRIPT_DIR/scripts/setup-env.sh" "$PROJECT_DIR/scripts/setup-env.sh"
cp "$SCRIPT_DIR/scripts/backup.sh" "$PROJECT_DIR/scripts/backup.sh"
rm -rf "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"
cp -R "$SCRIPT_DIR/platforms/$SELECTED_PLATFORM" "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"

step 7 "Install Optional Tools (L3)"
if [ "$INSTALL_TMUX" = true ]; then pkg install -y tmux; fi
if [ "$INSTALL_TTYD" = true ]; then pkg install -y ttyd; fi
if [ "$INSTALL_DUFS" = true ]; then pkg install -y dufs; fi
if [ "$INSTALL_ANDROID_TOOLS" = true ]; then pkg install -y android-tools; fi

if [ "$INSTALL_CHROMIUM" = true ]; then bash "$SCRIPT_DIR/scripts/install-chromium.sh" install; fi

if [ "$INSTALL_CODE_SERVER" = true ]; then mkdir -p "$PROJECT_DIR/patches" && cp "$SCRIPT_DIR/patches/argon2-stub.js" "$PROJECT_DIR/patches/argon2-stub.js" && bash "$SCRIPT_DIR/scripts/install-code-server.sh" install; fi

if [ "$INSTALL_OPENCODE" = true ]; then bash "$SCRIPT_DIR/scripts/install-opencode.sh" install; fi

if [ "$INSTALL_CLAUDE_CODE" = true ]; then npm install -g @anthropic-ai/claude-code; fi
if [ "$INSTALL_GEMINI_CLI" = true ]; then npm install -g @google/gemini-cli; fi
if [ "$INSTALL_CODEX_CLI" = true ]; then
    npm install -g @mmmbuto/codex-cli-termux
    # Create codex CLI wrapper (DioNanos fork launcher fix)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "$_codex_pkg/codex.bin" ]; then
        [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
        printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
            "$PREFIX" "$_codex_pkg" > "$_codex_bin"
        chmod +x "$_codex_bin"
    fi
fi

command -v fix_npm_global_shebangs >/dev/null 2>&1 && fix_npm_global_shebangs || true

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
