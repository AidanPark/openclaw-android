#!/usr/bin/env bash
# update-core.sh - Lightweight updater for OpenClaw on Android (existing installations)
# Called by update.sh (thin wrapper) or oaupdate command
# Supports both Bionic (pre-1.0.0) and glibc (1.0.0+) architectures.
# Bionic installations are migrated to glibc automatically.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
OPENCLAW_DIR="$HOME/.openclaw-android"
OA_VERSION="1.0.1"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Updater v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/10] $2${NC}"
    echo "----------------------------------------"
}

# ─────────────────────────────────────────────
step 1 "Pre-flight Check"

# Check Termux
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Termux detected"

# Check existing OpenClaw installation
if ! command -v openclaw &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} openclaw command not found"
    echo "       Run the full installer first:"
    echo "       curl -sL myopenclawhub.com/install | bash"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   openclaw $(openclaw --version 2>/dev/null || echo "")"

# Migrate from old directory name (.openclaw-lite → .openclaw-android)
OLD_DIR="$HOME/.openclaw-lite"
if [ -d "$OLD_DIR" ] && [ ! -d "$OPENCLAW_DIR" ]; then
    mv "$OLD_DIR" "$OPENCLAW_DIR"
    echo -e "${GREEN}[OK]${NC}   Migrated $OLD_DIR → $OPENCLAW_DIR"
elif [ -d "$OLD_DIR" ] && [ -d "$OPENCLAW_DIR" ]; then
    # Both exist — merge old into new, then remove old
    cp -rn "$OLD_DIR"/. "$OPENCLAW_DIR"/ 2>/dev/null || true
    rm -rf "$OLD_DIR"
    echo -e "${GREEN}[OK]${NC}   Merged $OLD_DIR into $OPENCLAW_DIR"
else
    mkdir -p "$OPENCLAW_DIR"
fi

# Check curl
if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

# Detect architecture: glibc vs Bionic
IS_GLIBC=false
if [ -f "$OPENCLAW_DIR/.glibc-arch" ]; then
    IS_GLIBC=true
    echo -e "${GREEN}[OK]${NC}   Architecture: glibc"
else
    echo -e "${YELLOW}[INFO]${NC} Architecture: Bionic (will migrate to glibc)"
fi

# Note about Phantom Process Killer (Android 12+, API 31+)
SDK_INT=$(getprop ro.build.version.sdk 2>/dev/null || echo "0")
if [ "$SDK_INT" -ge 31 ] 2>/dev/null; then
    echo -e "${YELLOW}[INFO]${NC} Android 12+ detected — if background processes get killed (signal 9),"
    echo "       see: https://github.com/AidanPark/openclaw-android/blob/main/docs/disable-phantom-process-killer.md"
fi

# ─────────────────────────────────────────────
step 2 "Installing New Packages"

# Install ttyd if not already installed
if command -v ttyd &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   ttyd already installed ($(ttyd --version 2>/dev/null || echo ""))"
else
    INSTALL_TTYD=true
    if [ -t 0 ]; then
        read -rp "ttyd (web terminal) is not installed. Install it? [Y/n] " REPLY
        [[ "$REPLY" =~ ^[Nn]$ ]] && INSTALL_TTYD=false
    fi
    if [ "$INSTALL_TTYD" = true ]; then
        echo "Installing ttyd..."
        if pkg install -y ttyd; then
            echo -e "${GREEN}[OK]${NC}   ttyd installed"
        else
            echo -e "${YELLOW}[WARN]${NC} Failed to install ttyd (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping ttyd"
    fi
fi

# Install dufs if not already installed
if command -v dufs &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   dufs already installed ($(dufs --version 2>/dev/null || echo ""))"
else
    INSTALL_DUFS=true
    if [ -t 0 ]; then
        read -rp "dufs (file server) is not installed. Install it? [Y/n] " REPLY
        [[ "$REPLY" =~ ^[Nn]$ ]] && INSTALL_DUFS=false
    fi
    if [ "$INSTALL_DUFS" = true ]; then
        echo "Installing dufs..."
        if pkg install -y dufs; then
            echo -e "${GREEN}[OK]${NC}   dufs installed"
        else
            echo -e "${YELLOW}[WARN]${NC} Failed to install dufs (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping dufs"
    fi
fi

# Install android-tools (adb) if not already installed
if command -v adb &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   android-tools already installed"
else
    INSTALL_ADB=true
    if [ -t 0 ]; then
        read -rp "android-tools (adb) is not installed. Install it? [Y/n] " REPLY
        [[ "$REPLY" =~ ^[Nn]$ ]] && INSTALL_ADB=false
    fi
    if [ "$INSTALL_ADB" = true ]; then
        echo "Installing android-tools..."
        if pkg install -y android-tools; then
            echo -e "${GREEN}[OK]${NC}   android-tools installed"
        else
            echo -e "${YELLOW}[WARN]${NC} Failed to install android-tools (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping android-tools"
    fi
fi

# Install PyYAML if not already installed (required for .skill packaging)
if python -c "import yaml" 2>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   PyYAML already installed"
else
    echo "Installing PyYAML..."
    if pip install pyyaml -q; then
        echo -e "${GREEN}[OK]${NC}   PyYAML installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to install PyYAML (non-critical)"
    fi
fi

# ─────────────────────────────────────────────
step 3 "Downloading Latest Scripts"

# Download setup-env.sh (needed for .bashrc update)
TMPFILE=$(mktemp "$PREFIX/tmp/setup-env.XXXXXX.sh") || {
    echo -e "${RED}[FAIL]${NC} Failed to create temporary file (disk full or $PREFIX/tmp missing?)"
    exit 1
}
if curl -sfL "$REPO_BASE/scripts/setup-env.sh" -o "$TMPFILE"; then
    echo -e "${GREEN}[OK]${NC}   setup-env.sh downloaded"
else
    echo -e "${RED}[FAIL]${NC} Failed to download setup-env.sh"
    rm -f "$TMPFILE"
    exit 1
fi

# Download glibc-compat.js (replaces bionic-compat.js)
mkdir -p "$OPENCLAW_DIR/patches"
if curl -sfL "$REPO_BASE/patches/glibc-compat.js" -o "$OPENCLAW_DIR/patches/glibc-compat.js"; then
    echo -e "${GREEN}[OK]${NC}   glibc-compat.js updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to download glibc-compat.js (non-critical)"
fi

# Install spawn.h stub if missing (needed for koffi/native module builds)
if [ ! -f "$PREFIX/include/spawn.h" ]; then
    if curl -sfL "$REPO_BASE/patches/spawn.h" -o "$PREFIX/include/spawn.h"; then
        echo -e "${GREEN}[OK]${NC}   spawn.h stub installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to download spawn.h (non-critical)"
    fi
else
    echo -e "${GREEN}[OK]${NC}   spawn.h already exists"
fi

# Install systemctl stub (Termux has no systemd)
if curl -sfL "$REPO_BASE/patches/systemctl" -o "$PREFIX/bin/systemctl"; then
    chmod +x "$PREFIX/bin/systemctl"
    echo -e "${GREEN}[OK]${NC}   systemctl stub updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to update systemctl stub (non-critical)"
fi

# Download oa.sh (unified CLI) and install as oa command
if curl -sfL "$REPO_BASE/oa.sh" -o "$PREFIX/bin/oa"; then
    chmod +x "$PREFIX/bin/oa"
    echo -e "${GREEN}[OK]${NC}   oa command updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to update oa command (non-critical)"
fi

# Install oaupdate as a thin wrapper that delegates to oa --update (backward compatibility)
cat > "$PREFIX/bin/oaupdate" << 'WRAPPER'
#!/usr/bin/env bash
exec oa --update "$@"
WRAPPER
chmod +x "$PREFIX/bin/oaupdate"
echo -e "${GREEN}[OK]${NC}   oaupdate command updated (→ oa --update)"

# Download uninstall.sh (for oa --uninstall)
if curl -sfL "$REPO_BASE/uninstall.sh" -o "$OPENCLAW_DIR/uninstall.sh"; then
    chmod +x "$OPENCLAW_DIR/uninstall.sh"
    echo -e "${GREEN}[OK]${NC}   uninstall.sh updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to download uninstall.sh (non-critical)"
fi

# Download argon2-stub.js (needed for code-server)
if curl -sfL "$REPO_BASE/patches/argon2-stub.js" -o "$OPENCLAW_DIR/patches/argon2-stub.js"; then
    echo -e "${GREEN}[OK]${NC}   argon2-stub.js updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to download argon2-stub.js (non-critical)"
fi

# Download install-code-server.sh
CS_TMPFILE=""
if CS_TMPFILE=$(mktemp "$PREFIX/tmp/install-code-server.XXXXXX.sh" 2>/dev/null); then
    if curl -sfL "$REPO_BASE/scripts/install-code-server.sh" -o "$CS_TMPFILE"; then
        echo -e "${GREEN}[OK]${NC}   install-code-server.sh downloaded"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to download install-code-server.sh (non-critical)"
        rm -f "$CS_TMPFILE"
        CS_TMPFILE=""
    fi
else
    echo -e "${YELLOW}[WARN]${NC} Failed to create temporary file for install-code-server.sh (non-critical)"
fi

# Download build-sharp.sh
SHARP_TMPFILE=""
if SHARP_TMPFILE=$(mktemp "$PREFIX/tmp/build-sharp.XXXXXX.sh" 2>/dev/null); then
    if curl -sfL "$REPO_BASE/scripts/build-sharp.sh" -o "$SHARP_TMPFILE"; then
        echo -e "${GREEN}[OK]${NC}   build-sharp.sh downloaded"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to download build-sharp.sh (non-critical)"
        rm -f "$SHARP_TMPFILE"
        SHARP_TMPFILE=""
    fi
else
    echo -e "${YELLOW}[WARN]${NC} Failed to create temporary file for build-sharp.sh (non-critical)"
fi

# Download install-glibc-env.sh (for migration or reinstall)
GLIBC_ENV_TMPFILE=""
if GLIBC_ENV_TMPFILE=$(mktemp "$PREFIX/tmp/install-glibc-env.XXXXXX.sh" 2>/dev/null); then
    if curl -sfL "$REPO_BASE/scripts/install-glibc-env.sh" -o "$GLIBC_ENV_TMPFILE"; then
        chmod +x "$GLIBC_ENV_TMPFILE"
        echo -e "${GREEN}[OK]${NC}   install-glibc-env.sh downloaded"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to download install-glibc-env.sh"
        rm -f "$GLIBC_ENV_TMPFILE"
        GLIBC_ENV_TMPFILE=""
    fi
else
    echo -e "${YELLOW}[WARN]${NC} Failed to create temporary file for install-glibc-env.sh"
fi

# Download install-opencode.sh
OPENCODE_TMPFILE=""
if OPENCODE_TMPFILE=$(mktemp "$PREFIX/tmp/install-opencode.XXXXXX.sh" 2>/dev/null); then
    if curl -sfL "$REPO_BASE/scripts/install-opencode.sh" -o "$OPENCODE_TMPFILE"; then
        chmod +x "$OPENCODE_TMPFILE"
        echo -e "${GREEN}[OK]${NC}   install-opencode.sh downloaded"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to download install-opencode.sh (non-critical)"
        rm -f "$OPENCODE_TMPFILE"
        OPENCODE_TMPFILE=""
    fi
else
    echo -e "${YELLOW}[WARN]${NC} Failed to create temporary file for install-opencode.sh (non-critical)"
fi

# ─────────────────────────────────────────────
step 4 "Updating Environment Variables"

GLIBC_NODE_DIR="$OPENCLAW_DIR/node"

if [ "$IS_GLIBC" = true ]; then
    # Already on glibc — refresh env vars immediately
    bash "$TMPFILE"
    rm -f "$TMPFILE"

    # Re-export for current session (setup-env.sh runs as subprocess, exports don't propagate)
    export PATH="$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
    export TMPDIR="$PREFIX/tmp"
    export TMP="$TMPDIR"
    export TEMP="$TMPDIR"
    export CONTAINER=1
    export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
    export OA_GLIBC=1
else
    # Bionic → defer .bashrc update until glibc migration succeeds (Step 4.5)
    # If we update .bashrc now and glibc install fails, NODE_OPTIONS="-r bionic-compat.js"
    # will be removed while the Bionic node still needs it for os.cpus()/networkInterfaces().
    echo -e "${YELLOW}[DEFER]${NC} .bashrc update deferred until glibc migration completes"

    # Export only safe vars that work with both architectures
    export TMPDIR="$PREFIX/tmp"
    export TMP="$TMPDIR"
    export TEMP="$TMPDIR"
    export CONTAINER=1
    export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
fi

# ─────────────────────────────────────────────
# Step 4.5: Migrate from Bionic to glibc (if needed)
if [ "$IS_GLIBC" = false ]; then
    echo ""
    echo -e "${BOLD}[MIGRATE] Bionic → glibc Architecture${NC}"
    echo "----------------------------------------"
    echo ""
    echo "Your installation uses the old Bionic architecture."
    echo "Migrating to glibc for better compatibility..."
    echo ""

    # Install glibc environment (pacman, glibc-runner, Node.js)
    if [ -n "$GLIBC_ENV_TMPFILE" ]; then
        if bash "$GLIBC_ENV_TMPFILE"; then
            echo -e "${GREEN}[OK]${NC}   glibc environment installed"
            IS_GLIBC=true
        else
            echo -e "${RED}[FAIL]${NC} glibc environment installation failed"
            echo "       The update will continue with Bionic architecture."
            echo "       Re-run 'oa --update' to retry migration."
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} install-glibc-env.sh not available — skipping migration"
    fi

    # Clean up old Bionic-specific files and apply deferred .bashrc update
    if [ "$IS_GLIBC" = true ]; then
        echo ""
        echo "Cleaning up old Bionic files..."

        # Remove old NODE_OPTIONS (bionic-compat.js is no longer needed)
        unset NODE_OPTIONS 2>/dev/null || true
        unset CXXFLAGS 2>/dev/null || true
        unset GYP_DEFINES 2>/dev/null || true
        unset CFLAGS 2>/dev/null || true
        unset CPATH 2>/dev/null || true

        # NOW safe to update .bashrc with glibc env vars (glibc node is installed)
        if [ -f "$TMPFILE" ]; then
            bash "$TMPFILE"
            rm -f "$TMPFILE"
            echo -e "${GREEN}[OK]${NC}   .bashrc updated with glibc environment"
        fi

        # Re-export glibc env for current session
        export PATH="$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
        export OA_GLIBC=1

        echo -e "${GREEN}[OK]${NC}   Bionic → glibc migration complete"
    else
        # Migration failed — keep old Bionic .bashrc intact for safety
        rm -f "$TMPFILE"
        echo -e "${YELLOW}[INFO]${NC} Keeping existing .bashrc (Bionic environment preserved)"
    fi
fi

# Clean up glibc env tmpfile
[ -n "${GLIBC_ENV_TMPFILE:-}" ] && rm -f "$GLIBC_ENV_TMPFILE"

# ─────────────────────────────────────────────
step 5 "Updating OpenClaw Package"

# Install build dependencies required for sharp's native compilation.
# This must happen before npm install so that libvips headers are
# available when node-gyp compiles sharp as a dependency of openclaw.
if dpkg -s libvips &>/dev/null && dpkg -s binutils &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   libvips and binutils already installed"
else
    echo "Installing build dependencies..."
    if pkg install -y libvips binutils; then
        echo -e "${GREEN}[OK]${NC}   libvips and binutils ready"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to install build dependencies"
        echo "       Image processing (sharp) may not compile correctly"
    fi
fi

# Create ar symlink if missing (Termux provides llvm-ar but not ar)
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
    echo -e "${GREEN}[OK]${NC}   Created ar → llvm-ar symlink"
fi

# Set CPATH for native module builds (sharp needs glib-2.0 headers)
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

# Compare installed vs latest version to skip unnecessary npm install
# Use npm list (not openclaw --version) to ensure format matches npm view
CURRENT_VER=$(npm list -g openclaw 2>/dev/null | grep 'openclaw@' | sed 's/.*openclaw@//' | tr -d '[:space:]')
LATEST_VER=$(npm view openclaw version 2>/dev/null || echo "")

OPENCLAW_UPDATED=false
if [ -n "$CURRENT_VER" ] && [ -n "$LATEST_VER" ] && [ "$CURRENT_VER" = "$LATEST_VER" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER is already the latest"
else
echo "Updating openclaw npm package... ($CURRENT_VER → $LATEST_VER)"
echo "  (This may take several minutes depending on network speed)"
if npm install -g openclaw@latest --no-fund --no-audit --ignore-scripts; then
        echo -e "${GREEN}[OK]${NC}   openclaw package updated"
        OPENCLAW_UPDATED=true
    else
        echo -e "${YELLOW}[WARN]${NC} Package update failed (non-critical)"
        echo "       Retry manually: npm install -g openclaw@latest"
    fi
fi

# ─────────────────────────────────────────────
step 6 "Building sharp (image processing)"

if [ "$OPENCLAW_UPDATED" = false ]; then
    echo -e "${GREEN}[SKIP]${NC} openclaw unchanged — sharp rebuild not needed"
    [ -n "$SHARP_TMPFILE" ] && rm -f "$SHARP_TMPFILE"
elif [ -n "$SHARP_TMPFILE" ]; then
    bash "$SHARP_TMPFILE"
    rm -f "$SHARP_TMPFILE"
else
    echo -e "${YELLOW}[SKIP]${NC} build-sharp.sh was not downloaded"
fi

# ─────────────────────────────────────────────
step 7 "Updating clawhub (skill manager)"

if command -v clawhub &>/dev/null || command -v clawdhub &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   clawhub already installed"
else
    INSTALL_CLAWHUB=true
    if [ -t 0 ]; then
        read -rp "clawhub (skill manager) is not installed. Install it? [Y/n] " REPLY
        [[ "$REPLY" =~ ^[Nn]$ ]] && INSTALL_CLAWHUB=false
    fi
    if [ "$INSTALL_CLAWHUB" = true ]; then
        echo "Installing clawhub..."
        if npm install -g clawdhub --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawhub installed"
        else
            echo -e "${YELLOW}[WARN]${NC} clawhub installation failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping clawhub"
    fi
fi

# Node.js v24+ on Termux doesn't bundle undici; clawhub needs it
CLAWHUB_DIR="$(npm root -g)/clawdhub"
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "Installing undici dependency for clawhub..."
    if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
        echo -e "${GREEN}[OK]${NC}   undici installed for clawhub"
    else
        echo -e "${YELLOW}[WARN]${NC} undici installation failed"
    fi
else
    echo -e "${GREEN}[OK]${NC}   undici already available"
fi

# Migrate skills installed to wrong path before CLAWDHUB_WORKDIR was set
# Previous versions of clawhub defaulted to ~/skills/ instead of ~/.openclaw/workspace/skills/
OLD_SKILLS_DIR="$HOME/skills"
CORRECT_SKILLS_DIR="$HOME/.openclaw/workspace/skills"
if [ -d "$OLD_SKILLS_DIR" ] && [ "$(ls -A "$OLD_SKILLS_DIR" 2>/dev/null)" ]; then
    echo ""
    echo "Migrating skills from ~/skills/ to ~/.openclaw/workspace/skills/..."
    mkdir -p "$CORRECT_SKILLS_DIR"
    for skill in "$OLD_SKILLS_DIR"/*/; do
        [ -d "$skill" ] || continue
        skill_name=$(basename "$skill")
        if [ ! -d "$CORRECT_SKILLS_DIR/$skill_name" ]; then
            if mv "$skill" "$CORRECT_SKILLS_DIR/$skill_name" 2>/dev/null; then
                echo -e "  ${GREEN}[OK]${NC}   Migrated $skill_name"
            else
                echo -e "  ${YELLOW}[WARN]${NC} Failed to migrate $skill_name"
            fi
        else
            echo -e "  ${YELLOW}[SKIP]${NC} $skill_name already exists in correct location"
        fi
    done
    # Remove old directory if empty
    if rmdir "$OLD_SKILLS_DIR" 2>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   Removed empty ~/skills/"
    else
        echo -e "${YELLOW}[WARN]${NC} ~/skills/ not empty after migration — check manually"
    fi
fi

# ─────────────────────────────────────────────
step 8 "Updating code-server (IDE)"

if [ -n "$CS_TMPFILE" ]; then
    CS_INSTALLED=false
    command -v code-server &>/dev/null && CS_INSTALLED=true

    if [ "$CS_INSTALLED" = true ]; then
        # Already installed — update
        if bash "$CS_TMPFILE" update; then
            echo -e "${GREEN}[OK]${NC}   code-server update step complete"
        else
            echo -e "${YELLOW}[WARN]${NC} code-server update failed (non-critical)"
        fi
    else
        # Not installed — ask user
        INSTALL_CS=false
        if [ -t 0 ]; then
            read -rp "code-server (browser IDE) is not installed. Install it? [Y/n] " REPLY
            [[ ! "$REPLY" =~ ^[Nn]$ ]] && INSTALL_CS=true
        else
            INSTALL_CS=true  # auto-install in non-interactive mode
        fi
        if [ "$INSTALL_CS" = true ]; then
            echo "  (This may take a few minutes — downloading ~121MB)"
            if bash "$CS_TMPFILE" install; then
                echo -e "${GREEN}[OK]${NC}   code-server installed"
            else
                echo -e "${YELLOW}[WARN]${NC} code-server installation failed (non-critical)"
            fi
        else
            echo -e "${YELLOW}[SKIP]${NC} Skipping code-server"
        fi
    fi
    rm -f "$CS_TMPFILE"
else
    echo -e "${YELLOW}[SKIP]${NC} install-code-server.sh was not downloaded"
fi

# ─────────────────────────────────────────────
step 9 "Updating AI CLI Tools"

# Helper: check and update a single AI CLI tool (version-aware)
update_ai_tool() {
    local cmd="$1"
    local pkg="$2"
    local label="$3"

    if ! command -v "$cmd" &>/dev/null; then
        return 1
    fi

    local current_ver latest_ver
    current_ver=$(npm list -g "$pkg" 2>/dev/null | grep "${pkg##*/}@" | sed 's/.*@//' | tr -d '[:space:]')
    latest_ver=$(npm view "$pkg" version 2>/dev/null || echo "")

    if [ -n "$current_ver" ] && [ -n "$latest_ver" ] && [ "$current_ver" = "$latest_ver" ]; then
        echo -e "${GREEN}[OK]${NC}   $label $current_ver is already the latest"
    elif [ -n "$latest_ver" ]; then
        echo "Updating $label... ($current_ver → $latest_ver)"
        echo "  (This may take a few minutes depending on network speed)"
        if npm install -g "$pkg@latest" --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   $label updated"
        else
            echo -e "${YELLOW}[WARN]${NC} $label update failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Could not check $label latest version"
    fi
    return 0
}

AI_FOUND=false
update_ai_tool "claude" "@anthropic-ai/claude-code" "Claude Code" && AI_FOUND=true
update_ai_tool "gemini" "@google/gemini-cli" "Gemini CLI" && AI_FOUND=true
update_ai_tool "codex" "@openai/codex" "Codex CLI" && AI_FOUND=true

if [ "$AI_FOUND" = false ]; then
    if [ -t 0 ]; then
        echo "No AI CLI tools are installed."
        echo "Available: Claude Code (Anthropic), Gemini CLI (Google), Codex CLI (OpenAI)"
        read -rp "Install AI CLI tools? [y/N] " REPLY
        if [[ "$REPLY" =~ ^[Yy]$ ]]; then
            AI_TOOLS_TMPFILE=$(mktemp "$PREFIX/tmp/install-ai-tools.XXXXXX.sh") || true
            if [ -n "$AI_TOOLS_TMPFILE" ] && curl -sfL "$REPO_BASE/scripts/install-ai-tools.sh" -o "$AI_TOOLS_TMPFILE"; then
                bash "$AI_TOOLS_TMPFILE"
                rm -f "$AI_TOOLS_TMPFILE"
            else
                echo -e "${YELLOW}[WARN]${NC} Failed to download AI tools installer"
                rm -f "${AI_TOOLS_TMPFILE:-}"
            fi
        else
            echo -e "${YELLOW}[SKIP]${NC} Skipping AI CLI tools"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} No AI CLI tools installed"
    fi
fi

# ─────────────────────────────────────────────
step 10 "Updating OpenCode + oh-my-opencode"

# Detect what's already installed
OPENCODE_INSTALLED=false
OMO_INSTALLED=false
[ -f "$PREFIX/bin/opencode" ] && OPENCODE_INSTALLED=true
[ -f "$PREFIX/bin/oh-my-opencode" ] && OMO_INSTALLED=true

if [ "$OPENCODE_INSTALLED" = true ]; then
    # Compare installed vs latest version to skip unnecessary reinstall
    CURRENT_OC_VER=$(opencode --version 2>/dev/null || echo "")
    LATEST_OC_VER=$(npm view opencode-ai version 2>/dev/null || echo "")

    OC_NEEDS_UPDATE=false
    if [ -n "$CURRENT_OC_VER" ] && [ -n "$LATEST_OC_VER" ]; then
        if [ "$CURRENT_OC_VER" != "$LATEST_OC_VER" ]; then
            OC_NEEDS_UPDATE=true
            echo "OpenCode update available: $CURRENT_OC_VER → $LATEST_OC_VER"
        else
            echo -e "${GREEN}[OK]${NC}   OpenCode $CURRENT_OC_VER is already the latest"
        fi
    elif [ -z "$LATEST_OC_VER" ]; then
        echo -e "${YELLOW}[WARN]${NC} Could not check latest OpenCode version"
    fi

    # Determine omo flag
    OPENCODE_FLAGS=""
    NEEDS_OMO_INSTALL=false
    if [ "$OMO_INSTALLED" = false ]; then
        # omo not installed — ask user in interactive mode
        if [ -t 0 ]; then
            read -rp "oh-my-opencode is not installed. Install it? [y/N] " REPLY
            if [[ "$REPLY" =~ ^[Yy]$ ]]; then
                NEEDS_OMO_INSTALL=true
            else
                OPENCODE_FLAGS="--no-omo"
            fi
        else
            OPENCODE_FLAGS="--no-omo"
        fi
    fi

    # Only run install script if there's work to do
    if [ "$OC_NEEDS_UPDATE" = true ] || [ "$NEEDS_OMO_INSTALL" = true ]; then
        if [ "$IS_GLIBC" = true ] && [ -n "${OPENCODE_TMPFILE:-}" ]; then
            echo "  (This may take a few minutes for package download and binary processing)"
            if bash "$OPENCODE_TMPFILE" $OPENCODE_FLAGS; then
                echo -e "${GREEN}[OK]${NC}   OpenCode update complete"
            else
                echo -e "${YELLOW}[WARN]${NC} OpenCode update failed (non-critical)"
            fi
        elif [ "$IS_GLIBC" = false ]; then
            echo -e "${YELLOW}[SKIP]${NC} OpenCode requires glibc architecture"
        else
            echo -e "${YELLOW}[SKIP]${NC} install-opencode.sh was not downloaded"
        fi
    fi
else
    # Not installed → ask if user wants to install (only in interactive mode)
    if [ -t 0 ]; then
        echo ""
        read -rp "OpenCode is not installed. Install it? [y/N] " REPLY
        if [[ "$REPLY" =~ ^[Yy]$ ]]; then
            OPENCODE_FLAGS=""
            read -rp "Also install oh-my-opencode (plugin framework)? [Y/n] " REPLY
            [[ "$REPLY" =~ ^[Nn]$ ]] && OPENCODE_FLAGS="--no-omo"

            if [ "$IS_GLIBC" = true ] && [ -n "${OPENCODE_TMPFILE:-}" ]; then
                echo "  (This may take a few minutes)"
                if bash "$OPENCODE_TMPFILE" $OPENCODE_FLAGS; then
                    echo -e "${GREEN}[OK]${NC}   OpenCode installed"
                else
                    echo -e "${YELLOW}[WARN]${NC} OpenCode installation failed (non-critical)"
                fi
            elif [ "$IS_GLIBC" = false ]; then
                echo -e "${YELLOW}[SKIP]${NC} OpenCode requires glibc architecture"
            else
                echo -e "${YELLOW}[SKIP]${NC} install-opencode.sh was not downloaded"
            fi
        else
            echo -e "${YELLOW}[SKIP]${NC} Skipping OpenCode"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} OpenCode not installed (non-interactive mode)"
    fi
fi

[ -n "${OPENCODE_TMPFILE:-}" ] && rm -f "$OPENCODE_TMPFILE"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${GREEN}${BOLD}  Update Complete!${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# Show OpenClaw update status
openclaw update status 2>/dev/null || true

echo ""
echo -e "${BOLD}Manage with the 'oa' command:${NC}"
echo "  oa --update       Update OpenClaw and patches"
echo "  oa --status       Show installation status"
echo "  oa --uninstall    Remove OpenClaw on Android"
echo "  oa --help         Show all options"
echo ""
echo -e "${YELLOW}Run this to apply changes to the current session:${NC}"
echo ""
echo "  source ~/.bashrc"
echo ""
