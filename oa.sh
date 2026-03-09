#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$HOME/.openclaw-android"

if [ -f "$HOME/.openclaw-android/scripts/lib.sh" ]; then
    # shellcheck source=/dev/null
    source "$HOME/.openclaw-android/scripts/lib.sh"
else
    OA_VERSION="1.0.5"
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BOLD='\033[1m'
    NC='\033[0m'
    REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    PLATFORM_MARKER="$PROJECT_DIR/.platform"

    detect_platform() {
        if [ -f "$PLATFORM_MARKER" ]; then
            cat "$PLATFORM_MARKER"
            return 0
        fi
        return 1
    }
fi

show_help() {
    echo ""
    echo -e "${BOLD}oa${NC} — OpenClaw on Android CLI v${OA_VERSION}"
    echo ""
    echo "Usage: oa [option]"
    echo ""
    echo "Options:"
    echo "  --update       Update OpenClaw and Android patches"
    echo "  --install      Install optional tools (tmux, code-server, AI CLIs, etc.)"
    echo "  --uninstall    Remove OpenClaw on Android"
    echo "  --status       Show installation status and all components"
    echo "  --version, -v  Show version"
    echo "  --help, -h     Show this help message"
    echo ""
}

show_version() {
    echo "oa v${OA_VERSION} (OpenClaw on Android)"

    local latest
    latest=$(curl -sfL --max-time 3 "$REPO_BASE/scripts/lib.sh" 2>/dev/null \
        | grep -m1 '^OA_VERSION=' | cut -d'"' -f2) || true

    if [ -n "${latest:-}" ]; then
        if [ "$latest" = "$OA_VERSION" ]; then
            echo -e "  ${GREEN}Up to date${NC}"
        else
            echo -e "  ${YELLOW}v${latest} available${NC} - run: oa --update"
        fi
    fi
}

cmd_update() {
    if ! command -v curl &>/dev/null; then
        echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
        exit 1
    fi

    mkdir -p "$PROJECT_DIR"
    local LOGFILE="$PROJECT_DIR/update.log"

    local TMPFILE
    TMPFILE=$(mktemp "${PREFIX:-/tmp}/tmp/update-core.XXXXXX.sh" 2>/dev/null) \
        || TMPFILE=$(mktemp /tmp/update-core.XXXXXX.sh)

    if ! curl -sfL "$REPO_BASE/update-core.sh" -o "$TMPFILE"; then
        rm -f "$TMPFILE"
        echo -e "${RED}[FAIL]${NC} Failed to download update-core.sh"
        exit 1
    fi

    bash "$TMPFILE" 2>&1 | tee "$LOGFILE"
    rm -f "$TMPFILE"

    echo ""
    echo -e "${YELLOW}Log saved to $LOGFILE${NC}"
}

cmd_uninstall() {
    local UNINSTALL_SCRIPT="$PROJECT_DIR/uninstall.sh"

    if [ ! -f "$UNINSTALL_SCRIPT" ]; then
        echo -e "${RED}[FAIL]${NC} Uninstall script not found at $UNINSTALL_SCRIPT"
        echo ""
        echo "You can download it manually:"
        echo "  curl -sL $REPO_BASE/uninstall.sh -o $UNINSTALL_SCRIPT && chmod +x $UNINSTALL_SCRIPT"
        exit 1
    fi

    bash "$UNINSTALL_SCRIPT"
}

cmd_status() {
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}  OpenClaw on Android — Status${NC}"
    echo -e "${BOLD}========================================${NC}"

    echo ""
    echo -e "${BOLD}Version${NC}"
    echo "  oa:          v${OA_VERSION}"

    local PLATFORM
    PLATFORM=$(detect_platform 2>/dev/null) || PLATFORM=""
    if [ -n "$PLATFORM" ]; then
        echo "  Platform:    $PLATFORM"
    else
        echo -e "  Platform:    ${RED}not detected${NC}"
    fi

    echo ""
    echo -e "${BOLD}Environment${NC}"
    echo "  PREFIX:            ${PREFIX:-not set}"
    echo "  TMPDIR:            ${TMPDIR:-not set}"

    echo ""
    echo -e "${BOLD}Paths${NC}"
    local CHECK_DIRS=("$PROJECT_DIR" "${PREFIX:-}/tmp")
    for dir in "${CHECK_DIRS[@]}"; do
        if [ -d "$dir" ]; then
            echo -e "  ${GREEN}[OK]${NC}   $dir"
        else
            echo -e "  ${RED}[MISS]${NC} $dir"
        fi
    done

    echo ""
    echo -e "${BOLD}Configuration${NC}"
    if grep -qF "OpenClaw on Android" "$HOME/.bashrc" 2>/dev/null; then
        echo -e "  ${GREEN}[OK]${NC}   .bashrc environment block present"
    else
        echo -e "  ${RED}[MISS]${NC} .bashrc environment block not found"
    fi

    local STATUS_SCRIPT="$PROJECT_DIR/platforms/$PLATFORM/status.sh"
    if [ -n "$PLATFORM" ] && [ -f "$STATUS_SCRIPT" ]; then
        bash "$STATUS_SCRIPT"
    fi

    echo ""
}

cmd_install() {
    if ! command -v curl &>/dev/null; then
        echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
        exit 1
    fi

    local TMPFILE
    TMPFILE=$(mktemp "${PREFIX:-/tmp}/tmp/install-tools.XXXXXX.sh" 2>/dev/null) \
        || TMPFILE=$(mktemp /tmp/install-tools.XXXXXX.sh)

    if ! curl -sfL "$REPO_BASE/install-tools.sh" -o "$TMPFILE"; then
        rm -f "$TMPFILE"
        echo -e "${RED}[FAIL]${NC} Failed to download install-tools.sh"
        exit 1
    fi

    bash "$TMPFILE"
    rm -f "$TMPFILE"
}

case "${1:-}" in
    --update)
        cmd_update
        ;;
    --install)
        cmd_install
        ;;
    --uninstall)
        cmd_uninstall
        ;;
    --status)
        cmd_status
        ;;
    --version|-v)
        show_version
        ;;
    --help|-h|"")
        show_help
        ;;
    *)
        echo -e "${RED}Unknown option: $1${NC}"
        echo ""
        show_help
        exit 1
        ;;
esac
