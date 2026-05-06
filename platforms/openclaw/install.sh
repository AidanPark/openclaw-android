#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/lib.sh"

echo "=== Installing OpenClaw Platform Package ==="
echo ""

export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true

mkdir -p "$PROJECT_DIR/patches"
cp "$SCRIPT_DIR/../../patches/glibc-compat.js" "$PROJECT_DIR/patches/glibc-compat.js"

cp "$SCRIPT_DIR/../../patches/systemctl" "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"

# ── Pre-install network verification ──
echo "Verifying network connectivity..."
_NPM_REGISTRY="${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}"
if ! curl -fsSL --connect-timeout 10 "$_NPM_REGISTRY" >/dev/null 2>&1; then
    echo -e "${YELLOW}[WARN]${NC} Primary registry unreachable, trying mirrors..."
    # Try npmmirror as fallback
    if curl -fsSL --connect-timeout 10 "https://registry.npmmirror.com/" >/dev/null 2>&1; then
        export NPM_CONFIG_REGISTRY="https://registry.npmmirror.com/"
        echo -e "${GREEN}[OK]${NC}   Using npmmirror registry"
    else
        echo -e "${RED}[FAIL]${NC} No registry reachable. Check network/DNS."
        exit 1
    fi
fi

# ── SSL certificates verification ──
_CERT_FILE="$PREFIX/etc/tls/cert.pem"
if [ ! -s "$_CERT_FILE" ] || ! grep -q "BEGIN CERTIFICATE" "$_CERT_FILE" 2>/dev/null; then
    echo -e "${YELLOW}[WARN]${NC} SSL certificates corrupted or missing"
    # Try to regenerate from Android system certs
    if [ -d "/system/etc/security/cacerts" ]; then
        mkdir -p "$PREFIX/etc/tls"
        cat /system/etc/security/cacerts/*.0 > "$_CERT_FILE" 2>/dev/null || true
        echo -e "${GREEN}[OK]${NC}   Regenerated certificates from system"
    fi
fi
unset _CERT_FILE _NPM_REGISTRY

# Clean up existing installation for smooth reinstall
if npm list -g openclaw &>/dev/null 2>&1 || [ -d "$PREFIX/lib/node_modules/openclaw" ]; then
    echo "Existing installation detected — cleaning up for reinstall..."
    npm uninstall -g openclaw 2>/dev/null || true
    rm -rf "$PREFIX/lib/node_modules/openclaw" 2>/dev/null || true
    npm uninstall -g clawdhub 2>/dev/null || true
    rm -rf "$PREFIX/lib/node_modules/clawdhub" 2>/dev/null || true
    rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Previous installation cleaned"
fi

# ── Robust npm install with retry logic ──
echo "Running: npm install -g openclaw@latest --ignore-scripts"
echo "This may take several minutes..."
echo ""

_NPM_RETRIES=3
_NPM_RETRY_DELAY=5
_NPM_SUCCESS=false

for _try in $(seq 1 $_NPM_RETRIES); do
    echo "  Attempt $_try/$_NPM_RETRIES..."
    if npm install -g openclaw@latest --ignore-scripts 2>&1; then
        _NPM_SUCCESS=true
        break
    else
        if [ "$_try" -lt "$_NPM_RETRIES" ]; then
            echo -e "  ${YELLOW}[WARN]${NC} Install failed, retrying in ${_NPM_RETRY_DELAY}s..."
            sleep $_NPM_RETRY_DELAY
            _NPM_RETRY_DELAY=$((_NPM_RETRY_DELAY * 2))
            # Clean cache before retry
            rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
        fi
    fi
done

if [ "$_NPM_SUCCESS" != "true" ]; then
    echo -e "${RED}[FAIL]${NC} npm install failed after $_NPM_RETRIES attempts"
    echo "  Last npm error output:"
    npm install -g openclaw@latest --ignore-scripts 2>&1 | tail -20 || true
    echo ""
    echo "  Diagnostic info:"
    echo "    - Node version: $(node --version 2>&1 || echo 'NOT FOUND')"
    echo "    - NPM version: $(npm --version 2>&1 || echo 'NOT FOUND')"
    echo "    - Registry: ${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}"
    echo "    - SSL certs: $([ -s "$PREFIX/etc/tls/cert.pem" ] && echo 'OK' || echo 'MISSING')"
    exit 1
fi

echo ""
echo -e "${GREEN}[OK]${NC}   OpenClaw installed"

# Restore optional/channel deps that --ignore-scripts skips.
# Uses npm_config_ignore_scripts=true so sharp's native build doesn't block.
OPENCLAW_DIR="$(npm root -g)/openclaw"
if [ -d "$OPENCLAW_DIR" ]; then
    echo "Restoring optional dependencies..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

echo ""
echo "Installing clawdhub (skill manager)..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "${GREEN}[OK]${NC}   clawdhub installed"
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "Installing undici dependency for clawdhub..."
        if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
            echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
        else
            echo -e "${YELLOW}[WARN]${NC} undici installation failed (clawdhub may not work)"
        fi
    fi
else
    echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
    echo "       Retry manually: npm i -g clawdhub"
fi

mkdir -p "$HOME/.openclaw"

echo ""
echo "Running: openclaw update"
echo "  (This includes building native modules and may take 5-10 minutes)"
echo ""
openclaw update || true
