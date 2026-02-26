#!/usr/bin/env bash
# oa - Unified CLI for OpenClaw on Android
# Installed to $PREFIX/bin/oa
set -euo pipefail

OA_VERSION="0.8.1"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
OPENCLAW_DIR="$HOME/.openclaw-android"

# ── Help ──────────────────────────────────────

show_help() {
    echo ""
    echo -e "${BOLD}oa${NC} — OpenClaw on Android CLI v${OA_VERSION}"
    echo ""
    echo "Usage: oa [option]"
    echo ""
    echo "Options:"
    echo "  ide            Start code-server (browser IDE)"
    echo "  ide --stop     Stop code-server"
    echo "  ide --status   Check if code-server is running"
    echo "  --update       Update OpenClaw and Android patches"
    echo "  --uninstall    Remove OpenClaw on Android"
    echo "  --status       Show installation status"
    echo "  --version, -v  Show version"
    echo "  --help, -h     Show this help message"
    echo ""
}

# ── Version ───────────────────────────────────

show_version() {
    echo "oa v${OA_VERSION} (OpenClaw on Android)"

    # Check latest version from GitHub (short timeout to avoid hanging)
    local latest
    latest=$(curl -sfL --max-time 3 "$REPO_BASE/oa.sh" 2>/dev/null \
        | grep -m1 '^OA_VERSION=' | cut -d'"' -f2) || true

    if [ -n "${latest:-}" ]; then
        if [ "$latest" = "$OA_VERSION" ]; then
            echo -e "  ${GREEN}Up to date${NC}"
        else
            echo -e "  ${YELLOW}v${latest} available${NC} — run: oa --update"
        fi
    fi
}

# ── IDE (code-server) ─────────────────────────

cmd_ide() {
    local subcmd="${1:-start}"

    case "$subcmd" in
        --stop)
            if pgrep -f "code-server" &>/dev/null; then
                pkill -f "code-server"
                echo -e "${GREEN}[OK]${NC}   code-server stopped"
            else
                echo "code-server is not running"
            fi
            ;;
        --status)
            if pgrep -f "code-server" &>/dev/null; then
                echo -e "${GREEN}[OK]${NC}   code-server is running"
                echo "  URL: http://localhost:8080"
            else
                echo "code-server is not running"
                echo "  Start with: oa ide"
            fi
            ;;
        start|"")
            if ! command -v code-server &>/dev/null; then
                echo -e "${RED}[FAIL]${NC} code-server not found"
                echo "  Run 'oa --update' to install it"
                exit 1
            fi
            echo "Starting code-server..."
            echo "  URL: http://localhost:8080"
            echo "  Press Ctrl+C to stop"
            echo ""
            exec code-server --auth none --bind-addr 0.0.0.0:8080 "$HOME/.openclaw"
            ;;
        *)
            echo -e "${RED}Unknown ide option: $subcmd${NC}"
            echo "Usage: oa ide [--stop|--status]"
            exit 1
            ;;
    esac
}

# ── Update ────────────────────────────────────

cmd_update() {
    if ! command -v curl &>/dev/null; then
        echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
        exit 1
    fi

    mkdir -p "$OPENCLAW_DIR"
    local LOGFILE="$OPENCLAW_DIR/update.log"

    local TMPFILE
    TMPFILE=$(mktemp "${PREFIX:-/tmp}/tmp/update-core.XXXXXX.sh" 2>/dev/null) \
        || TMPFILE=$(mktemp /tmp/update-core.XXXXXX.sh)
    trap 'rm -f "$TMPFILE"' EXIT

    if ! curl -sfL "$REPO_BASE/update-core.sh" -o "$TMPFILE"; then
        echo -e "${RED}[FAIL]${NC} Failed to download update-core.sh"
        exit 1
    fi

    bash "$TMPFILE" 2>&1 | tee "$LOGFILE"

    echo ""
    echo -e "${YELLOW}Log saved to $LOGFILE${NC}"
}

# ── Uninstall ─────────────────────────────────

cmd_uninstall() {
    local UNINSTALL_SCRIPT="$OPENCLAW_DIR/uninstall.sh"

    if [ ! -f "$UNINSTALL_SCRIPT" ]; then
        echo -e "${RED}[FAIL]${NC} Uninstall script not found at $UNINSTALL_SCRIPT"
        echo ""
        echo "You can download it manually:"
        echo "  curl -sL $REPO_BASE/uninstall.sh -o $UNINSTALL_SCRIPT && chmod +x $UNINSTALL_SCRIPT"
        exit 1
    fi

    bash "$UNINSTALL_SCRIPT"
}

# ── Status ────────────────────────────────────

cmd_status() {
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}  OpenClaw on Android — Status${NC}"
    echo -e "${BOLD}========================================${NC}"

    echo ""
    echo -e "${BOLD}Version${NC}"
    echo "  oa:          v${OA_VERSION}"

    if command -v openclaw &>/dev/null; then
        echo "  OpenClaw:    $(openclaw --version 2>/dev/null || echo 'error')"
    else
        echo -e "  OpenClaw:    ${RED}not installed${NC}"
    fi

    if command -v node &>/dev/null; then
        echo "  Node.js:     $(node -v 2>/dev/null)"
    else
        echo -e "  Node.js:     ${RED}not installed${NC}"
    fi

    if command -v npm &>/dev/null; then
        echo "  npm:         $(npm -v 2>/dev/null)"
    else
        echo -e "  npm:         ${RED}not installed${NC}"
    fi

    if command -v clawhub &>/dev/null; then
        echo "  clawhub:     $(clawhub --version 2>/dev/null || echo 'installed')"
    else
        echo -e "  clawhub:     ${YELLOW}not installed${NC}"
    fi

    if command -v code-server &>/dev/null; then
        local cs_ver
        cs_ver=$(code-server --version 2>/dev/null | head -1 || true)
        local cs_status="stopped"
        if pgrep -f "code-server" &>/dev/null; then
            cs_status="running"
        fi
        echo "  code-server: ${cs_ver:-installed} ($cs_status)"
    else
        echo -e "  code-server: ${YELLOW}not installed${NC}"
    fi

    echo ""
    echo -e "${BOLD}Environment${NC}"
    echo "  PREFIX:            ${PREFIX:-not set}"
    echo "  TMPDIR:            ${TMPDIR:-not set}"
    echo "  NODE_OPTIONS:      $([ -n "${NODE_OPTIONS:-}" ] && echo "set" || echo "not set")"
    echo "  CONTAINER:         ${CONTAINER:-not set}"
    echo "  CLAWDHUB_WORKDIR:  ${CLAWDHUB_WORKDIR:-not set}"

    echo ""
    echo -e "${BOLD}Paths${NC}"
    local CHECK_DIRS=("$OPENCLAW_DIR" "$HOME/.openclaw" "${PREFIX:-}/tmp")
    for dir in "${CHECK_DIRS[@]}"; do
        if [ -d "$dir" ]; then
            echo -e "  ${GREEN}[OK]${NC}   $dir"
        else
            echo -e "  ${RED}[MISS]${NC} $dir"
        fi
    done

    echo ""
    echo -e "${BOLD}Patches${NC}"
    local CHECK_FILES=(
        "$OPENCLAW_DIR/patches/bionic-compat.js"
        "$OPENCLAW_DIR/patches/termux-compat.h"
        "${PREFIX:-}/include/spawn.h"
    )
    for file in "${CHECK_FILES[@]}"; do
        if [ -f "$file" ]; then
            echo -e "  ${GREEN}[OK]${NC}   $(basename "$file")"
        else
            echo -e "  ${RED}[MISS]${NC} $(basename "$file")"
        fi
    done

    echo ""
    echo -e "${BOLD}Configuration${NC}"
    if grep -qF "OpenClaw on Android" "$HOME/.bashrc" 2>/dev/null; then
        echo -e "  ${GREEN}[OK]${NC}   .bashrc environment block present"
    else
        echo -e "  ${RED}[MISS]${NC} .bashrc environment block not found"
    fi

    echo ""
    echo -e "${BOLD}Disk${NC}"
    if [ -d "$OPENCLAW_DIR" ]; then
        echo "  ~/.openclaw-android:  $(du -sh "$OPENCLAW_DIR" 2>/dev/null | cut -f1)"
    fi
    if [ -d "$HOME/.openclaw" ]; then
        echo "  ~/.openclaw:          $(du -sh "$HOME/.openclaw" 2>/dev/null | cut -f1)"
    fi
    local AVAIL_MB
    AVAIL_MB=$(df "${PREFIX:-/}" 2>/dev/null | awk 'NR==2 {print int($4/1024)}') || true
    echo "  Available:            ${AVAIL_MB:-unknown}MB"

    echo ""
    echo -e "${BOLD}Skills${NC}"
    local SKILLS_DIR="${CLAWDHUB_WORKDIR:-$HOME/.openclaw/workspace}/skills"
    if [ -d "$SKILLS_DIR" ]; then
        local count
        count=$(find "$SKILLS_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l) || true
        echo "  Installed: $count"
        echo "  Path:      $SKILLS_DIR"
    else
        echo "  No skills directory found"
    fi

    echo ""
}

# ── Main dispatch ─────────────────────────────

case "${1:-}" in
    ide)
        shift
        cmd_ide "${1:-start}"
        ;;
    --update)
        cmd_update
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
