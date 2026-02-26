#!/usr/bin/env bash
# verify-install.sh - Verify OpenClaw installation on Termux
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

check_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS=$((PASS + 1))
}

check_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL=$((FAIL + 1))
}

check_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARN=$((WARN + 1))
}

echo "=== OpenClaw on Android - Installation Verification ==="
echo ""

# 1. Node.js version
if command -v node &>/dev/null; then
    NODE_VER=$(node -v)
    NODE_MAJOR="${NODE_VER%%.*}"
    NODE_MAJOR="${NODE_MAJOR#v}"
    if [ "$NODE_MAJOR" -ge 22 ] 2>/dev/null; then
        check_pass "Node.js $NODE_VER (>= 22)"
    else
        check_fail "Node.js $NODE_VER (need >= 22)"
    fi
else
    check_fail "Node.js not found"
fi

# 2. npm available
if command -v npm &>/dev/null; then
    check_pass "npm $(npm -v)"
else
    check_fail "npm not found"
fi

# 3. openclaw command
if command -v openclaw &>/dev/null; then
    CLAW_VER=$(openclaw --version 2>/dev/null || echo "error")
    if [ "$CLAW_VER" != "error" ]; then
        check_pass "openclaw $CLAW_VER"
    else
        check_warn "openclaw found but --version failed"
    fi
else
    check_fail "openclaw command not found"
fi

# 4. Environment variables
if [ -n "${TMPDIR:-}" ]; then
    check_pass "TMPDIR=$TMPDIR"
else
    check_fail "TMPDIR not set"
fi

if [ -n "${NODE_OPTIONS:-}" ]; then
    check_pass "NODE_OPTIONS is set"
else
    check_fail "NODE_OPTIONS not set"
fi

if [ "${CONTAINER:-}" = "1" ]; then
    check_pass "CONTAINER=1 (systemd bypass)"
else
    check_warn "CONTAINER not set"
fi

# 5. Patch files
COMPAT_FILE="$HOME/.openclaw-android/patches/bionic-compat.js"
if [ -f "$COMPAT_FILE" ]; then
    check_pass "bionic-compat.js exists"
else
    check_fail "bionic-compat.js not found at $COMPAT_FILE"
fi

COMPAT_HEADER="$HOME/.openclaw-android/patches/termux-compat.h"
if [ -f "$COMPAT_HEADER" ]; then
    check_pass "termux-compat.h exists"
else
    check_fail "termux-compat.h not found at $COMPAT_HEADER"
fi

if [ -n "${CXXFLAGS:-}" ]; then
    check_pass "CXXFLAGS is set"
else
    check_warn "CXXFLAGS not set (native module builds may fail)"
fi

# 6. Directories
for DIR in "$HOME/.openclaw-android" "$HOME/.openclaw" "$PREFIX/tmp"; do
    if [ -d "$DIR" ]; then
        check_pass "Directory $DIR exists"
    else
        check_fail "Directory $DIR missing"
    fi
done

# 7. code-server
if command -v code-server &>/dev/null; then
    CS_VER=$(code-server --version 2>/dev/null | head -1 || true)
    if [ -n "$CS_VER" ]; then
        check_pass "code-server $CS_VER"
    else
        check_warn "code-server found but --version failed"
    fi
else
    check_warn "code-server not installed (non-critical)"
fi

# 8. .bashrc contains env block
if grep -qF "OpenClaw on Android" "$HOME/.bashrc" 2>/dev/null; then
    check_pass ".bashrc contains environment block"
else
    check_fail ".bashrc missing environment block"
fi

# Summary
echo ""
echo "==============================="
echo -e "  Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}, ${YELLOW}$WARN warnings${NC}"
echo "==============================="
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}Installation verification FAILED.${NC}"
    echo "Please check the errors above and re-run install.sh"
    exit 1
else
    echo -e "${GREEN}Installation verification PASSED!${NC}"
fi
