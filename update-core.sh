#!/usr/bin/env bash
# update-core.sh - Lightweight updater for OpenClaw on Android (existing installations)
# Called by update.sh (thin wrapper) or oaupdate command
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

REPO_BASE="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
OPENCLAW_DIR="$HOME/.openclaw-android"
OA_VERSION="0.8"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Updater v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/7] $2${NC}"
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

# ─────────────────────────────────────────────
step 2 "Installing New Packages"

# Install ttyd if not already installed
if command -v ttyd &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   ttyd already installed ($(ttyd --version 2>/dev/null || echo ""))"
else
    echo "Installing ttyd..."
    if pkg install -y ttyd; then
        echo -e "${GREEN}[OK]${NC}   ttyd installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to install ttyd (non-critical)"
    fi
fi

# Install dufs if not already installed
if command -v dufs &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   dufs already installed ($(dufs --version 2>/dev/null || echo ""))"
else
    echo "Installing dufs..."
    if pkg install -y dufs; then
        echo -e "${GREEN}[OK]${NC}   dufs installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Failed to install dufs (non-critical)"
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

# Download bionic-compat.js (patches may have been updated)
mkdir -p "$OPENCLAW_DIR/patches"
if curl -sfL "$REPO_BASE/patches/bionic-compat.js" -o "$OPENCLAW_DIR/patches/bionic-compat.js"; then
    echo -e "${GREEN}[OK]${NC}   bionic-compat.js updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to download bionic-compat.js (non-critical)"
fi

# Download termux-compat.h (native build compatibility)
if curl -sfL "$REPO_BASE/patches/termux-compat.h" -o "$OPENCLAW_DIR/patches/termux-compat.h"; then
    echo -e "${GREEN}[OK]${NC}   termux-compat.h updated"
else
    echo -e "${YELLOW}[WARN]${NC} Failed to download termux-compat.h (non-critical)"
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

# ─────────────────────────────────────────────
step 4 "Updating Environment Variables"

# Run setup-env.sh to refresh .bashrc block
bash "$TMPFILE"
rm -f "$TMPFILE"

# Re-export for current session (setup-env.sh runs as subprocess, exports don't propagate)
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export NODE_OPTIONS="-r $HOME/.openclaw-android/patches/bionic-compat.js"
export CONTAINER=1
export CFLAGS="-Wno-error=implicit-function-declaration"
export CXXFLAGS="-include $HOME/.openclaw-android/patches/termux-compat.h"
export GYP_DEFINES="OS=linux android_ndk_path=$PREFIX"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"

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

# CXXFLAGS, GYP_DEFINES, and CPATH were exported in step 4.
# npm runs as a child process of this script and inherits those
# env vars, so sharp's node-gyp build succeeds here — unlike in
# 'openclaw update', which spawns npm without these env vars set.

# Compare installed vs latest version to skip unnecessary npm install
CURRENT_VER=$(openclaw --version 2>/dev/null || echo "")
LATEST_VER=$(npm view openclaw version 2>/dev/null || echo "")

OPENCLAW_UPDATED=false
if [ -n "$CURRENT_VER" ] && [ -n "$LATEST_VER" ] && [ "$CURRENT_VER" = "$LATEST_VER" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER is already the latest"
else
    echo "Updating openclaw npm package... ($CURRENT_VER → $LATEST_VER)"
    if npm install -g openclaw@latest --no-fund --no-audit; then
        echo -e "${GREEN}[OK]${NC}   openclaw package updated"
        OPENCLAW_UPDATED=true
    else
        echo -e "${YELLOW}[WARN]${NC} Package update failed (non-critical)"
        echo "       Retry manually: npm install -g openclaw@latest"
    fi
fi

# ─────────────────────────────────────────────
step 6 "Updating clawhub (skill manager)"

if command -v clawhub &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   clawhub already installed"
else
    echo "Installing clawhub..."
    if npm install -g clawdhub --no-fund --no-audit; then
        echo -e "${GREEN}[OK]${NC}   clawhub installed"
    else
        echo -e "${YELLOW}[WARN]${NC} clawhub installation failed (non-critical)"
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
step 7 "Building sharp (image processing)"

if [ "$OPENCLAW_UPDATED" = false ]; then
    echo -e "${GREEN}[SKIP]${NC} openclaw unchanged — sharp rebuild not needed"
    [ -n "$SHARP_TMPFILE" ] && rm -f "$SHARP_TMPFILE"
elif [ -n "$SHARP_TMPFILE" ]; then
    bash "$SHARP_TMPFILE"
    rm -f "$SHARP_TMPFILE"
else
    echo -e "${YELLOW}[SKIP]${NC} build-sharp.sh was not downloaded"
fi

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
