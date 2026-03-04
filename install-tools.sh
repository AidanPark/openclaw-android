#!/usr/bin/env bash
# =============================================================================
# install-tools.sh — 번들 제공 도구 설치
#
# oa --install 로 실행. 초기 설치 시 설치하지 않은 도구를 나중에 설치할 수 있다.
# 이미 설치된 도구는 [INSTALLED]로 표시하고 건너뛴다.
# =============================================================================
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$HOME/.openclaw-android"
PLATFORM_MARKER="$PROJECT_DIR/.platform"
OA_VERSION="1.0.4"
REPO_TARBALL="https://github.com/AidanPark/openclaw-android/archive/refs/heads/main.tar.gz"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Install Tools${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# --- Pre-checks ---
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi

if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

if [ -f "$PROJECT_DIR/scripts/lib.sh" ]; then
    source "$PROJECT_DIR/scripts/lib.sh"
fi

if ! declare -f ask_yn &>/dev/null; then
    ask_yn() {
        local prompt="$1"
        local reply
        read -rp "$prompt [Y/n] " reply < /dev/tty
        [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
        return 0
    }
fi

IS_GLIBC=false
if [ -f "$PROJECT_DIR/.glibc-arch" ]; then
    IS_GLIBC=true
fi

# --- Detect installed tools ---
echo -e "${BOLD}Checking installed tools...${NC}"
echo ""

declare -A TOOL_STATUS

check_tool() {
    local name="$1"
    local cmd="$2"
    if command -v "$cmd" &>/dev/null; then
        TOOL_STATUS["$name"]="installed"
        echo -e "  ${GREEN}[INSTALLED]${NC} $name"
    else
        TOOL_STATUS["$name"]="not_installed"
        echo -e "  ${YELLOW}[NOT INSTALLED]${NC} $name"
    fi
}

check_tool "tmux" "tmux"
check_tool "ttyd" "ttyd"
check_tool "dufs" "dufs"
check_tool "android-tools" "adb"
check_tool "code-server" "code-server"
if [ "$IS_GLIBC" = true ]; then
    check_tool "OpenCode" "opencode"
fi
check_tool "Claude Code" "claude"
check_tool "Gemini CLI" "gemini"
check_tool "Codex CLI" "codex"

echo ""

# --- Check if anything to install ---
HAS_UNINSTALLED=false
for status in "${TOOL_STATUS[@]}"; do
    if [ "$status" = "not_installed" ]; then
        HAS_UNINSTALLED=true
        break
    fi
done

if [ "$HAS_UNINSTALLED" = false ]; then
    echo -e "${GREEN}All available tools are already installed.${NC}"
    echo ""
    exit 0
fi

# --- Collect selections ---
echo -e "${BOLD}Select tools to install:${NC}"
echo ""

INSTALL_TMUX=false
INSTALL_TTYD=false
INSTALL_DUFS=false
INSTALL_ANDROID_TOOLS=false
INSTALL_CODE_SERVER=false
INSTALL_OPENCODE=false
INSTALL_CLAUDE_CODE=false
INSTALL_GEMINI_CLI=false
INSTALL_CODEX_CLI=false

[ "${TOOL_STATUS[tmux]}" = "not_installed" ] && ask_yn "  Install tmux (terminal multiplexer)?" && INSTALL_TMUX=true || true
[ "${TOOL_STATUS[ttyd]}" = "not_installed" ] && ask_yn "  Install ttyd (web terminal)?" && INSTALL_TTYD=true || true
[ "${TOOL_STATUS[dufs]}" = "not_installed" ] && ask_yn "  Install dufs (file server)?" && INSTALL_DUFS=true || true
[ "${TOOL_STATUS[android-tools]}" = "not_installed" ] && ask_yn "  Install android-tools (adb)?" && INSTALL_ANDROID_TOOLS=true || true
[ "${TOOL_STATUS[code-server]}" = "not_installed" ] && ask_yn "  Install code-server (browser IDE)?" && INSTALL_CODE_SERVER=true || true
if [ "$IS_GLIBC" = true ] && [ "${TOOL_STATUS[OpenCode]}" = "not_installed" ]; then
    ask_yn "  Install OpenCode (AI coding assistant)?" && INSTALL_OPENCODE=true || true
fi
[ "${TOOL_STATUS[Claude Code]}" = "not_installed" ] && ask_yn "  Install Claude Code CLI?" && INSTALL_CLAUDE_CODE=true || true
[ "${TOOL_STATUS[Gemini CLI]}" = "not_installed" ] && ask_yn "  Install Gemini CLI?" && INSTALL_GEMINI_CLI=true || true
[ "${TOOL_STATUS[Codex CLI]}" = "not_installed" ] && ask_yn "  Install Codex CLI?" && INSTALL_CODEX_CLI=true || true

# --- Check if anything selected ---
ANYTHING_SELECTED=false
for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_ANDROID_TOOLS \
           INSTALL_CODE_SERVER INSTALL_OPENCODE INSTALL_CLAUDE_CODE \
           INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
    if [ "${!var}" = true ]; then
        ANYTHING_SELECTED=true
        break
    fi
done

if [ "$ANYTHING_SELECTED" = false ]; then
    echo ""
    echo "No tools selected."
    exit 0
fi

# --- Download scripts (needed for code-server and OpenCode) ---
NEEDS_TARBALL=false
if [ "$INSTALL_CODE_SERVER" = true ] || [ "$INSTALL_OPENCODE" = true ]; then
    NEEDS_TARBALL=true
fi

if [ "$NEEDS_TARBALL" = true ]; then
    echo ""
    echo "Downloading install scripts..."
    mkdir -p "$PREFIX/tmp"
    RELEASE_TMP=$(mktemp -d "$PREFIX/tmp/oa-install.XXXXXX") || {
        echo -e "${RED}[FAIL]${NC} Failed to create temp directory"
        exit 1
    }
    trap 'rm -rf "$RELEASE_TMP"' EXIT

    if curl -sfL "$REPO_TARBALL" | tar xz -C "$RELEASE_TMP" --strip-components=1; then
        echo -e "${GREEN}[OK]${NC}   Downloaded install scripts"
    else
        echo -e "${RED}[FAIL]${NC} Failed to download scripts"
        exit 1
    fi
fi

# --- Install selected tools ---
echo ""
echo -e "${BOLD}Installing selected tools...${NC}"
echo ""

[ "$INSTALL_TMUX" = true ] && echo "Installing tmux..." && pkg install -y tmux && echo -e "${GREEN}[OK]${NC}   tmux installed" || true
[ "$INSTALL_TTYD" = true ] && echo "Installing ttyd..." && pkg install -y ttyd && echo -e "${GREEN}[OK]${NC}   ttyd installed" || true
[ "$INSTALL_DUFS" = true ] && echo "Installing dufs..." && pkg install -y dufs && echo -e "${GREEN}[OK]${NC}   dufs installed" || true
[ "$INSTALL_ANDROID_TOOLS" = true ] && echo "Installing android-tools..." && pkg install -y android-tools && echo -e "${GREEN}[OK]${NC}   android-tools installed" || true

if [ "$INSTALL_CODE_SERVER" = true ]; then
    mkdir -p "$PROJECT_DIR/patches"
    cp "$RELEASE_TMP/patches/argon2-stub.js" "$PROJECT_DIR/patches/argon2-stub.js"
    if bash "$RELEASE_TMP/scripts/install-code-server.sh" install; then
        echo -e "${GREEN}[OK]${NC}   code-server installed"
    else
        echo -e "${YELLOW}[WARN]${NC} code-server installation failed (non-critical)"
    fi
fi

if [ "$INSTALL_OPENCODE" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-opencode.sh"; then
        echo -e "${GREEN}[OK]${NC}   OpenCode installed"
    else
        echo -e "${YELLOW}[WARN]${NC} OpenCode installation failed (non-critical)"
    fi
fi

[ "$INSTALL_CLAUDE_CODE" = true ] && echo "Installing Claude Code..." && npm install -g @anthropic-ai/claude-code && echo -e "${GREEN}[OK]${NC}   Claude Code installed" || true
[ "$INSTALL_GEMINI_CLI" = true ] && echo "Installing Gemini CLI..." && npm install -g @google/gemini-cli && echo -e "${GREEN}[OK]${NC}   Gemini CLI installed" || true
[ "$INSTALL_CODEX_CLI" = true ] && echo "Installing Codex CLI..." && npm install -g @openai/codex && echo -e "${GREEN}[OK]${NC}   Codex CLI installed" || true

echo ""
echo -e "${GREEN}${BOLD}  Installation Complete!${NC}"
echo ""
