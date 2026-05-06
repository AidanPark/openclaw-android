#!/usr/bin/env bash
# update.sh — OpenClaw on Android updater entry point
# Served at: curl -sL myopenclawhub.com/update | bash
# Also available at: curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/update.sh | bash
#   or:  oaupdate  (after initial install)
set -euo pipefail

RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Mirror constants (kept in sync with scripts/lib.sh)
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
REPO_BASE_MIRRORS=(
    "https://ghfast.top/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://ghproxy.net/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://mirror.ghproxy.com/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
)
REPO_BASE="$REPO_BASE_ORIGIN"
LOGFILE="$HOME/.openclaw-android/update.log"

# Ensure curl is available
if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

# Detect reachable REPO_BASE (origin first, then mirrors) — kept in sync with lib.sh
resolve_repo_base() {
    if curl -sI --connect-timeout 3 "$REPO_BASE_ORIGIN/oa.sh" >/dev/null 2>&1; then
        REPO_BASE="$REPO_BASE_ORIGIN"; return 0
    fi
    for mirror in "${REPO_BASE_MIRRORS[@]}"; do
        if curl -sI --connect-timeout 3 "$mirror/oa.sh" >/dev/null 2>&1; then
            echo -e "${YELLOW}[MIRROR]${NC} Using mirror: ${mirror%%/oa.sh*}"
            REPO_BASE="$mirror"; return 0
        fi
    done
    REPO_BASE="$REPO_BASE_ORIGIN"
    return 1
}
resolve_repo_base || true

# Prepare log directory
mkdir -p "$HOME/.openclaw-android"

# Download update-core.sh
TMPFILE=$(mktemp "${TMPDIR:-${PREFIX:-/tmp}/tmp}/update-core.XXXXXX") || \
    TMPFILE=$(mktemp "/tmp/update-core.XXXXXX")
trap 'rm -f "$TMPFILE"' EXIT

if ! curl -sfL "$REPO_BASE/update-core.sh" -o "$TMPFILE"; then
    echo -e "${RED}[FAIL]${NC} Failed to download update-core.sh"
    exit 1
fi

# Execute and save output to log
bash "$TMPFILE" 2>&1 | tee "$LOGFILE"

echo ""
echo -e "${YELLOW}Log saved to $LOGFILE${NC}"
