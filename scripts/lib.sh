#!/usr/bin/env bash
# lib.sh — Shared function library for all orchestrators
# Usage: source "$SCRIPT_DIR/scripts/lib.sh"  (from repo)
#        source "$PROJECT_DIR/scripts/lib.sh"  (from installed copy)

# ── Color constants ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

# ── Project constants ──
PROJECT_DIR="$HOME/.openclaw-android"
PLATFORM_MARKER="$PROJECT_DIR/.platform"
REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"

BASHRC_MARKER_START="# >>> OpenClaw on Android >>>"
BASHRC_MARKER_END="# <<< OpenClaw on Android <<<"
OA_VERSION="1.0.5"

# ── Platform detection ──
# 1. Explicit marker file (new install and after first update)
# 2. Legacy detection (v1.0.2 and below, one-time)
# 3. Detection failure
detect_platform() {
    if [ -f "$PLATFORM_MARKER" ]; then
        cat "$PLATFORM_MARKER"
        return 0
    fi
    if command -v openclaw &>/dev/null; then
        echo "openclaw"
        mkdir -p "$(dirname "$PLATFORM_MARKER")"
        echo "openclaw" > "$PLATFORM_MARKER"
        return 0
    fi
    echo ""
    return 1
}

# ── Platform name validation ──
validate_platform_name() {
    local name="$1"
    if [ -z "$name" ]; then
        echo -e "${RED}[FAIL]${NC} Platform name is empty"
        return 1
    fi
    # Only lowercase alphanumeric + hyphens/underscores allowed
    if [[ ! "$name" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
        echo -e "${RED}[FAIL]${NC} Invalid platform name: $name"
        return 1
    fi
    return 0
}

# ── User confirmation prompt ──
# Reads from /dev/tty so it works even in curl|bash mode.
# Termux always has /dev/tty — no fallback for tty-less environments.
ask_yn() {
    local prompt="$1"
    local reply
    read -rp "$prompt [Y/n] " reply < /dev/tty
    [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
    return 0
}

# ── Load platform config.env ──
# $1: platform name, $2: base directory (parent of platforms/)
load_platform_config() {
    local platform="$1"
    local base_dir="$2"
    local config_path="$base_dir/platforms/$platform/config.env"

    validate_platform_name "$platform" || return 1

    if [ ! -f "$config_path" ]; then
        echo -e "${RED}[FAIL]${NC} Platform config not found: $config_path"
        return 1
    fi
    source "$config_path"
    return 0
}
