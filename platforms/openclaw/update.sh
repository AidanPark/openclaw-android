#!/usr/bin/env bash
# openclaw/update.sh - Update OpenClaw npm package
# Implements version check + reapply patches + marker update
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"

export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

echo "=== Updating OpenClaw Platform ==="
echo ""

# Ensure build tools are available
pkg install -y libvips binutils 2>/dev/null || true
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
fi

# ── Version Check ──
CURRENT_VER=$(npm list -g openclaw --depth=0 2>/dev/null | grep 'openclaw@' | sed 's/.*openclaw@//' | tr -d '[:space:]') || true
LATEST_VER=$(npm view openclaw version 2>/dev/null || echo "") || true

# If versions match, skip update entirely
if [ -n "$CURRENT_VER" ] && [ -n "$LATEST_VER" ] && [ "$CURRENT_VER" = "$LATEST_VER" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER is already the latest"
    echo "No update needed"
    exit 0
fi

OPENCLAW_UPDATED=false

if [ -n "$CURRENT_VER" ] && [ -n "$LATEST_VER" ] && [ "$CURRENT_VER" != "$LATEST_VER" ]; then
    echo "Updating openclaw npm package... ($CURRENT_VER → $LATEST_VER)"
    echo "  (This may take several minutes depending on network speed)"
    if npm install -g openclaw@latest --no-fund --no-audit --ignore-scripts; then
        echo -e "${GREEN}[OK]${NC}   openclaw $LATEST_VER updated"
        OPENCLAW_UPDATED=true
    else
        echo -e "${YELLOW}[WARN]${NC} Package update failed (non-critical)"
        echo "       Retry manually: npm install -g openclaw@latest"
    fi
elif [ -z "$CURRENT_VER" ]; then
    # Not installed - install fresh
    echo "Installing openclaw npm package..."
    if npm install -g openclaw@latest --no-fund --no-audit --ignore-scripts; then
        CURRENT_VER=$(npm list -g openclaw --depth=0 2>/dev/null | grep 'openclaw@' | sed 's/.*openclaw@//' | tr -d '[:space:]')
        echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER installed"
        OPENCLAW_UPDATED=true
    else
        echo -e "${YELLOW}[WARN]${NC} Package install failed (non-critical)"
    fi
fi

# Reinstall dependencies without --ignore-scripts to restore optional/channel
# deps (e.g. @buape/carbon, grammy) that were skipped above.
# Native build failures (sharp, node-gyp) are non-fatal here (|| true).
# Restore optional/channel deps that --ignore-scripts skips.
# Uses npm_config_ignore_scripts=true so that the internal npm install
# doesn't trigger sharp's native build (which fails on Termux).
# The postinstall-bundled-plugins.mjs installs pure JS channel deps only.
OPENCLAW_DIR="$(npm root -g)/openclaw"
if [ -d "$OPENCLAW_DIR" ]; then
    if ! node -e "require('$OPENCLAW_DIR/node_modules/@buape/carbon')" 2>/dev/null; then
        echo "Restoring optional dependencies..."
        (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
    fi
fi

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

if [ "$OPENCLAW_UPDATED" = true ]; then
    bash "$SCRIPT_DIR/patches/openclaw-build-sharp.sh" || true
else
    echo -e "${GREEN}[SKIP]${NC} openclaw $CURRENT_VER unchanged \u2014 sharp rebuild not needed"
fi

if command -v clawdhub &>/dev/null; then
    CLAWDHUB_CURRENT_VER=$(npm list -g clawdhub 2>/dev/null | grep 'clawdhub@' | sed 's/.*clawdhub@//' | tr -d '[:space:]')
    CLAWDHUB_LATEST_VER=$(npm view clawdhub version 2>/dev/null || echo "")
    if [ -n "$CLAWDHUB_CURRENT_VER" ] && [ -n "$CLAWDHUB_LATEST_VER" ] && [ "$CLAWDHUB_CURRENT_VER" = "$CLAWDHUB_LATEST_VER" ]; then
        echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_CURRENT_VER is already the latest"
    elif [ -n "$CLAWDHUB_LATEST_VER" ]; then
        echo "Updating clawdhub... ($CLAWDHUB_CURRENT_VER → $CLAWDHUB_LATEST_VER)"
        if npm install -g clawdhub@latest --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_LATEST_VER updated"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub update failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Could not check clawdhub latest version"
    fi
else
    if ask_yn "clawdhub (skill manager) is not installed. Install it?"; then
        echo "Installing clawdhub..."
        if npm install -g clawdhub --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub installed"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping clawdhub"
    fi
fi

CLAWHUB_DIR="$(npm root -g)/clawdhub"
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "Installing undici dependency for clawdhub..."
    if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
        echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
    else
        echo -e "${YELLOW}[WARN]${NC} undici installation failed"
    fi
else
    UNDICI_VER=$(cd "$CLAWHUB_DIR" && node -e "console.log(require('undici/package.json').version)" 2>/dev/null || echo "")
    echo -e "${GREEN}[OK]${NC}   undici ${UNDICI_VER:-available}"
fi

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
    if rmdir "$OLD_SKILLS_DIR" 2>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   Removed empty ~/skills/"
    else
        echo -e "${YELLOW}[WARN]${NC} ~/skills/ not empty after migration — check manually"
    fi
fi

python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true

# ── Update installed.json marker ──
# Write marker only if openclaw is actually installed
OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
OC_BIN="$PREFIX/bin/openclaw"
if [ -f "$OC_MJS" ] || [ -f "$OC_BIN" ]; then
    # Get final version after update
    INSTALLED_VER=$(npm list -g openclaw --depth=0 2>/dev/null | grep 'openclaw@' | sed 's/.*openclaw@//' | tr -d '[:space:]') || true
    if [ -z "$INSTALLED_VER" ]; then
        INSTALLED_VER="unknown"
    fi

    # Write marker file
    mkdir -p "$PROJECT_DIR"
    cat > "$PROJECT_DIR/installed.json" << EOF
{
  "installed": true,
  "version": "$INSTALLED_VER",
  "updated_at": "$(date -Iseconds)"
}
EOF
    echo -e "${GREEN}[OK]${NC}   installed.json updated (version: $INSTALLED_VER)"
else
    echo -e "${YELLOW}[WARN]${NC}   openclaw not found, skipping marker update"
fi

# ── Final verification ──
echo ""
echo "=== Verification ==="
FINAL_VER=$(openclaw --version 2>/dev/null || echo "NOT FOUND")
if [ "$FINAL_VER" != "NOT FOUND" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw --version: $FINAL_VER"
else
    echo -e "${RED}[FAIL]${NC}   openclaw --version not responding"
fi
