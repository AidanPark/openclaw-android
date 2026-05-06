#!/bin/sh
# =============================================================================
# install-remote.sh — OpenClaw Remote Installer
# =============================================================================
# Served at: myopenclawhub.com/install
# Usage:
#   curl -sL myopenclawhub.com/install | sh
#   curl -sL myopenclawhub.com/install | bash   (if bash available)
#
# POSIX sh compatible — works on:
#   - Android /system/bin/sh (mksh/toybox)
#   - Termux bash
#   - Linux bash/dash/sh
#
# Design:
#   - NO bashisms (no arrays, no [[ ]], no source, no $BASH_SOURCE)
#   - Detects environment: Android App sandbox vs Termux vs Linux
#   - Routes to the correct installer for each environment
#   - Handles the glibc libc.so symlink repair automatically
# =============================================================================

set -eu

# ── Colors (safe for sh) ──────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

# ── Logging ───────────────────────────────────────────────────────────────────
log()      { printf '%s\n' "$*"; }
log_ok()   { printf "${GREEN}[OK]${NC}   %s\n" "$*"; }
log_err()  { printf "${RED}[ERR]${NC}  %s\n" "$*" >&2; }
log_warn() { printf "${YELLOW}[WARN]${NC} %s\n" "$*"; }
log_step() { printf "\n${BOLD}=== %s ===${NC}\n" "$*"; }

# ── Environment detection ─────────────────────────────────────────────────────
detect_env() {
    # Android App sandbox: PREFIX is set to app filesDir/usr
    if [ -n "${APP_PACKAGE:-}" ] || [ -n "${OA_GLIBC:-}" ]; then
        echo "app"
        return
    fi

    # Termux: has termux-specific paths
    if [ -d "/data/data/com.termux/files/usr" ] || \
       [ -d "/data/user/0/com.termux/files/usr" ] || \
       command -v termux-info >/dev/null 2>&1; then
        echo "termux"
        return
    fi

    # Standard Linux
    echo "linux"
}

# ── Resolve PREFIX ────────────────────────────────────────────────────────────
resolve_prefix() {
    # If PREFIX is already set (Android App mode), use it
    if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ]; then
        echo "$PREFIX"
        return
    fi

    # Termux default
    if [ -d "/data/data/com.termux/files/usr" ]; then
        echo "/data/data/com.termux/files/usr"
        return
    fi

    # Multi-user Android (user 0 = primary)
    if [ -d "/data/user/0/com.termux/files/usr" ]; then
        echo "/data/user/0/com.termux/files/usr"
        return
    fi

    # Linux fallback
    echo "${HOME}/.local"
}

# ── Repair broken glibc symlinks ──────────────────────────────────────────────
# Fixes: CANNOT LINK EXECUTABLE "sh": libc.so >= file size: 0 >= 0
# Root cause: tar.gz extraction on Android creates zero-byte files instead of
# symlinks for libc.so -> libc.so.6 (and libm.so, libpthread.so).
repair_glibc_symlinks() {
    local glibc_lib="$1"
    [ -d "$glibc_lib" ] || return 0

    log_step "Repairing glibc symlinks"

    for pair in "libc.so:libc.so.6" "libm.so:libm.so.6" "libpthread.so:libpthread.so.0" "libdl.so:libdl.so.2" "librt.so:librt.so.1"; do
        local link_name="${pair%%:*}"
        local target="${pair##*:}"
        local link_path="$glibc_lib/$link_name"
        local target_path="$glibc_lib/$target"

        # Only repair if the real versioned file exists
        [ -f "$target_path" ] || continue
        [ -s "$target_path" ] || continue  # skip if target is also zero-byte

        if [ -f "$link_path" ] && [ ! -s "$link_path" ]; then
            # Zero-byte file — broken symlink extracted as empty file
            log_warn "Repairing broken $link_name (zero-byte) -> $target"
            rm -f "$link_path"
            ln -sf "$target" "$link_path" && log_ok "Repaired: $link_name -> $target" || \
                log_warn "Could not create symlink $link_name (non-fatal)"
        elif [ ! -e "$link_path" ]; then
            # Missing entirely
            ln -sf "$target" "$link_path" && log_ok "Created: $link_name -> $target" || \
                log_warn "Could not create symlink $link_name (non-fatal)"
        fi
    done
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    log ""
    log "${BOLD}============================================${NC}"
    log "${BOLD}  OpenClaw Installer${NC}"
    log "${BOLD}============================================${NC}"
    log ""

    ENV_TYPE=$(detect_env)
    PREFIX=$(resolve_prefix)
    HOME="${HOME:-/data/local/tmp}"

    log "  Environment : $ENV_TYPE"
    log "  PREFIX      : $PREFIX"
    log "  HOME        : $HOME"
    log "  Shell       : $SHELL ($(readlink /proc/$$/exe 2>/dev/null || echo 'unknown'))"
    log ""

    # ── Step 1: Repair glibc symlinks if already extracted ────────────────────
    GLIBC_LIB="$PREFIX/glibc/lib"
    if [ -d "$GLIBC_LIB" ]; then
        repair_glibc_symlinks "$GLIBC_LIB"
    fi

    # ── Step 2: Route to correct installer ───────────────────────────────────
    case "$ENV_TYPE" in
        app)
            install_app_mode
            ;;
        termux)
            install_termux_mode
            ;;
        linux)
            install_linux_mode
            ;;
    esac
}

# ── App mode installer (Android App sandbox) ──────────────────────────────────
install_app_mode() {
    log_step "Android App Mode Installation"

    : "${PREFIX:?PREFIX not set}"
    : "${HOME:?HOME not set}"

    OCA_DIR="$HOME/.openclaw-android"
    BIN_DIR="$OCA_DIR/bin"
    NODE_DIR="$OCA_DIR/node"
    GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
    TMPDIR="${TMPDIR:-$PREFIX/../tmp}"

    mkdir -p "$OCA_DIR" "$BIN_DIR" "$NODE_DIR" "$TMPDIR"

    # Repair glibc symlinks first (fixes libc.so zero-byte issue)
    repair_glibc_symlinks "$PREFIX/glibc/lib"

    # Verify glibc linker
    if [ ! -x "$GLIBC_LDSO" ]; then
        log_err "glibc linker not found: $GLIBC_LDSO"
        log_err "Run the app setup first to extract the payload."
        exit 1
    fi
    log_ok "glibc linker: $GLIBC_LDSO"

    # Install Node.js if missing
    install_nodejs_app

    # Install OpenClaw
    install_openclaw_app

    # Write marker
    write_marker

    log ""
    log_ok "Installation complete!"
    log "  Run: openclaw gateway"
}

install_nodejs_app() {
    NODE_VERSION="22.22.0"
    NODE_REAL="$NODE_DIR/bin/node.real"
    NODE_WRAPPER="$BIN_DIR/node"

    if [ -x "$NODE_WRAPPER" ] && "$NODE_WRAPPER" --version >/dev/null 2>&1; then
        log_ok "Node.js already installed: $("$NODE_WRAPPER" --version 2>/dev/null)"
        return 0
    fi

    log_step "Installing Node.js v$NODE_VERSION"

    # Download official linux-arm64 binary
    NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
    NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}.tar.gz"

    log "  Downloading Node.js (~25MB)..."
    if ! curl -fsSL --connect-timeout 30 --retry 3 --max-time 300 \
        "$NODE_URL" -o "$TMPDIR/node.tar.gz"; then
        log_err "Failed to download Node.js"
        exit 1
    fi

    log "  Extracting..."
    mkdir -p "$NODE_DIR"
    tar -xzf "$TMPDIR/node.tar.gz" -C "$NODE_DIR" --strip-components=1
    rm -f "$TMPDIR/node.tar.gz"

    # Move original binary -> node.real
    if [ -f "$NODE_DIR/bin/node" ] && [ ! -L "$NODE_DIR/bin/node" ]; then
        mv "$NODE_DIR/bin/node" "$NODE_REAL"
    fi

    # Create glibc wrapper (uses /system/bin/sh — always available)
    cat > "$NODE_WRAPPER" << WRAPPER
#!/system/bin/sh
unset LD_PRELOAD
export LD_LIBRARY_PATH="${PREFIX}/glibc/lib:${PREFIX}/lib:\${LD_LIBRARY_PATH:-}"
_OA_COMPAT="\${HOME}/.openclaw-android/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT"
fi
exec "${GLIBC_LDSO}" --library-path "${PREFIX}/glibc/lib" "${NODE_REAL}" "\$@"
WRAPPER
    chmod +x "$NODE_WRAPPER"

    # Create npm wrapper
    NPM_CLI="$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js"
    if [ -f "$NPM_CLI" ]; then
        cat > "$BIN_DIR/npm" << NPMWRAP
#!/system/bin/sh
exec "${NODE_WRAPPER}" "${NPM_CLI}" "\$@"
NPMWRAP
        chmod +x "$BIN_DIR/npm"
    fi

    # Verify
    if "$NODE_WRAPPER" --version >/dev/null 2>&1; then
        log_ok "Node.js $("$NODE_WRAPPER" --version) installed"
    else
        log_err "Node.js verification failed"
        exit 1
    fi
}

install_openclaw_app() {
    OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"

    if [ -f "$OC_MJS" ]; then
        log_ok "OpenClaw already installed"
        repair_openclaw_wrapper
        return 0
    fi

    log_step "Installing OpenClaw"

    NPM_BIN="$BIN_DIR/npm"
    if [ ! -x "$NPM_BIN" ]; then
        NPM_BIN="$NODE_DIR/bin/npm"
    fi

    # Resolve npm registry
    NPM_REGISTRY="https://registry.npmjs.org/"
    if ! curl -fsSL --connect-timeout 5 "$NPM_REGISTRY" >/dev/null 2>&1; then
        if curl -fsSL --connect-timeout 5 "https://registry.npmmirror.com/" >/dev/null 2>&1; then
            NPM_REGISTRY="https://registry.npmmirror.com/"
            log_warn "Using npm mirror: $NPM_REGISTRY"
        fi
    fi
    export NPM_CONFIG_REGISTRY="$NPM_REGISTRY"

    # Install with retry
    _retries=3
    _delay=5
    _ok=false
    _try=0
    while [ "$_try" -lt "$_retries" ]; do
        _try=$((_try + 1))
        log "  Attempt $_try/$_retries..."
        if "$NPM_BIN" install -g openclaw@latest --ignore-scripts 2>&1; then
            _ok=true
            break
        fi
        if [ "$_try" -lt "$_retries" ]; then
            log_warn "Failed, retrying in ${_delay}s..."
            sleep "$_delay"
            _delay=$((_delay * 2))
        fi
    done

    if [ "$_ok" != "true" ]; then
        log_err "OpenClaw installation failed after $_retries attempts"
        exit 1
    fi

    repair_openclaw_wrapper
    log_ok "OpenClaw installed"
}

repair_openclaw_wrapper() {
    OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    OC_BIN="$PREFIX/bin/openclaw"

    [ -f "$OC_MJS" ] || return 0

    # Always rewrite wrapper to use glibc node (not /usr/bin/env node)
    rm -f "$OC_BIN"
    printf '#!/system/bin/sh\nexec "%s" "%s" "$@"\n' "$BIN_DIR/node" "$OC_MJS" > "$OC_BIN"
    chmod +x "$OC_BIN"
    log_ok "openclaw wrapper -> $BIN_DIR/node"
}

write_marker() {
    OCA_DIR="$HOME/.openclaw-android"
    mkdir -p "$OCA_DIR"
    _ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo 'unknown')
    cat > "$OCA_DIR/installed.json" << MARKER
{
  "installed": true,
  "source": "curl-install",
  "installedAt": "${_ts}",
  "prefix": "${PREFIX}",
  "home": "${HOME}"
}
MARKER
    log_ok "Marker written: $OCA_DIR/installed.json"
}

# ── Termux mode installer ─────────────────────────────────────────────────────
install_termux_mode() {
    log_step "Termux Mode Installation"

    # In Termux, we can use bash and the full install.sh
    REPO="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"

    if command -v bash >/dev/null 2>&1; then
        log "  Downloading full installer..."
        curl -fsSL "$REPO/install.sh" -o "$TMPDIR/install-full.sh"
        chmod +x "$TMPDIR/install-full.sh"
        bash "$TMPDIR/install-full.sh"
    else
        log_warn "bash not found, using sh-compatible install"
        install_app_mode
    fi
}

# ── Linux mode installer ──────────────────────────────────────────────────────
install_linux_mode() {
    log_step "Linux Mode Installation"
    log_err "Linux installation not yet supported via this script."
    log "  Please visit: https://github.com/AidanPark/openclaw-android"
    exit 1
}

# ── Entry point ───────────────────────────────────────────────────────────────
main "$@"
