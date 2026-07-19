# Playwright Install Option Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Playwright as an optional tool in `oa --install`, with automatic Chromium dependency resolution, environment variable setup, and usage guide.

**Architecture:** New `scripts/install-playwright.sh` follows the existing delegated-script pattern (like `install-chromium.sh`). `install-tools.sh` gets a new menu entry right after Chromium. The install script checks Chromium dependency, installs `playwright-core` via npm, sets Playwright environment variables in `.bashrc`, and prints a usage guide.

**Tech Stack:** Bash, npm (playwright-core), Termux environment

---

## Task 1: Create `scripts/install-playwright.sh`

**Files:**
- Create: `scripts/install-playwright.sh`

- [ ] **Step 1: Create the install script**

```bash
#!/usr/bin/env bash
# install-playwright.sh - Install Playwright for browser automation
# Usage: bash install-playwright.sh [install|update]
#
# What it does:
#   1. Ensure Chromium is installed (dependency)
#   2. Install playwright-core via npm global
#   3. Set Playwright environment variables in .bashrc
#   4. Print usage guide
#
# This script is WARN-level: failure does not abort the parent installer.
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

MODE="${1:-install}"

# ── Helper ────────────────────────────────────

fail_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    exit 0
}

# ── Detect Chromium binary path ───────────────

detect_chromium_bin() {
    for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
        if [ -x "$bin" ]; then
            echo "$bin"
            return 0
        fi
    done
    return 1
}

# ── Pre-checks ────────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    fail_warn "Not running in Termux (\$PREFIX not set)"
fi

if ! command -v npm &>/dev/null; then
    fail_warn "npm not found — Node.js is required for Playwright"
fi

# ── Step 1: Ensure Chromium is installed ──────

if ! CHROMIUM_BIN=$(detect_chromium_bin); then
    echo "Chromium is required for Playwright. Installing..."
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    if [ -f "$SCRIPT_DIR/install-chromium.sh" ]; then
        if ! bash "$SCRIPT_DIR/install-chromium.sh" install; then
            fail_warn "Chromium installation failed — cannot proceed with Playwright"
        fi
    else
        fail_warn "install-chromium.sh not found — install Chromium first"
    fi

    if ! CHROMIUM_BIN=$(detect_chromium_bin); then
        fail_warn "Chromium binary not found after installation"
    fi
fi

echo -e "${GREEN}[OK]${NC}   Chromium found: $CHROMIUM_BIN"

# ── Step 2: Install playwright-core ───────────

if [ "$MODE" = "install" ]; then
    # Check if already installed
    if npm list -g playwright-core &>/dev/null; then
        echo -e "${GREEN}[SKIP]${NC} playwright-core already installed"
    else
        echo "Installing playwright-core..."
        if ! npm install -g playwright-core; then
            fail_warn "Failed to install playwright-core"
        fi
        echo -e "${GREEN}[OK]${NC}   playwright-core installed"
    fi
elif [ "$MODE" = "update" ]; then
    echo "Updating playwright-core..."
    if ! npm install -g playwright-core@latest; then
        fail_warn "Failed to update playwright-core"
    fi
    echo -e "${GREEN}[OK]${NC}   playwright-core updated"
fi

# ── Step 3: Set environment variables ─────────

BASHRC="$HOME/.bashrc"
PW_MARKER_START="# >>> Playwright >>>"
PW_MARKER_END="# <<< Playwright <<<"

PW_BLOCK="${PW_MARKER_START}
export PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=\"$CHROMIUM_BIN\"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
${PW_MARKER_END}"

touch "$BASHRC"
if grep -qF "$PW_MARKER_START" "$BASHRC"; then
    sed -i "/${PW_MARKER_START//\//\\/}/,/${PW_MARKER_END//\//\\/}/d" "$BASHRC"
fi
echo "" >> "$BASHRC"
echo "$PW_BLOCK" >> "$BASHRC"

echo -e "${GREEN}[OK]${NC}   Environment variables set in .bashrc"
echo "       PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$CHROMIUM_BIN"
echo "       PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"

# ── Step 4: Usage guide ──────────────────────

echo ""
echo -e "${BOLD}  Playwright is ready!${NC}"
echo ""
echo "  To use in your project:"
echo ""
echo "    npm install playwright-core    # add to your project"
echo ""
echo "  Example code:"
echo ""
echo "    const { chromium } = require('playwright-core');"
echo ""
echo "    const browser = await chromium.launch();"
echo "    const page = await browser.newPage();"
echo "    await page.goto('https://example.com');"
echo "    await page.screenshot({ path: 'screenshot.png' });"
echo "    await browser.close();"
echo ""
echo -e "  ${YELLOW}[NOTE]${NC} Environment variables are set. No need to specify"
echo "         executablePath or --no-sandbox manually."
echo ""
echo "  To apply environment variables in current session:"
echo "    source ~/.bashrc"
echo ""
```

Write this to `scripts/install-playwright.sh`.

- [ ] **Step 2: Make the script executable**

Run: `chmod +x scripts/install-playwright.sh`

---

## Task 2: Add Playwright to `install-tools.sh`

**Files:**
- Modify: `install-tools.sh:79` (tool detection)
- Modify: `install-tools.sh:118` (flag declaration)
- Modify: `install-tools.sh:124` (user prompt — after Chromium)
- Modify: `install-tools.sh:135-137` (anything_selected check)
- Modify: `install-tools.sh:152` (NEEDS_TARBALL condition)
- Modify: `install-tools.sh:202-208` (installation phase — after Chromium)

- [ ] **Step 1: Add Playwright to tool detection**

After line 79 (`check_tool "Chromium" "chromium-browser"`), detect playwright-core via npm:

```bash
# Playwright detection — check via npm since it's a global npm package
if npm list -g playwright-core &>/dev/null 2>&1; then
    TOOL_STATUS["Playwright"]="installed"
    echo -e "  ${GREEN}[INSTALLED]${NC} Playwright"
else
    TOOL_STATUS["Playwright"]="not_installed"
    echo -e "  ${YELLOW}[NOT INSTALLED]${NC} Playwright"
fi
```

- [ ] **Step 2: Add flag declaration**

After `INSTALL_CHROMIUM=false` (line 118), add:

```bash
INSTALL_PLAYWRIGHT=false
```

- [ ] **Step 3: Add user prompt after Chromium prompt**

After the Chromium prompt line (line 124), add:

```bash
if [ "${TOOL_STATUS[Playwright]}" = "not_installed" ] && ask_yn "  Install Playwright (browser automation library, requires Chromium)?"; then INSTALL_PLAYWRIGHT=true; fi
```

- [ ] **Step 4: Add to anything_selected check**

Add `INSTALL_PLAYWRIGHT` to the for loop on line 135-137:

```bash
for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_ANDROID_TOOLS \
           INSTALL_CHROMIUM INSTALL_PLAYWRIGHT INSTALL_CODE_SERVER INSTALL_OPENCODE INSTALL_CLAUDE_CODE \
           INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
```

- [ ] **Step 5: Add NEEDS_TARBALL condition**

On line 152, add `INSTALL_PLAYWRIGHT` to the condition:

```bash
if [ "$INSTALL_CODE_SERVER" = true ] || [ "$INSTALL_OPENCODE" = true ] || [ "$INSTALL_CHROMIUM" = true ] || [ "$INSTALL_PLAYWRIGHT" = true ]; then
```

- [ ] **Step 6: Add installation block after Chromium**

After the Chromium installation block (after line 208), add:

```bash
if [ "$INSTALL_PLAYWRIGHT" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-playwright.sh" install; then
        echo -e "${GREEN}[OK]${NC}   Playwright installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Playwright installation failed (non-critical)"
    fi
fi
```

---

## Task 3: Verify

- [ ] **Step 1: Syntax check install-playwright.sh**

Run: `bash -n scripts/install-playwright.sh`
Expected: no output (no syntax errors)

- [ ] **Step 2: Syntax check install-tools.sh**

Run: `bash -n install-tools.sh`
Expected: no output (no syntax errors)

- [ ] **Step 3: Verify Playwright detection logic handles missing npm gracefully**

Run: `grep -n "npm list -g playwright-core" install-tools.sh`
Expected: the detection line appears in the tool detection section
