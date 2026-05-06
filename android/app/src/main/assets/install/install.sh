#!/system/bin/sh
# =============================================================================
# install.sh — OpenClaw Android Local Installer
# =============================================================================
# Bundled in assets/install/ and copied to filesDir/install/ at runtime.
#
# This script is designed to run INSIDE the app sandbox, not in a standard
# Termux environment. All paths are derived from the environment variables
# injected by TerminalManager.buildEnvBlock() / EnvironmentBuilder.
#
# Required env vars (injected by TerminalManager before this script runs):
#   PREFIX   — app sandbox usr dir (e.g. /data/user/0/com.openclaw.android.debug/files/usr)
#   HOME     — app sandbox home dir
#   TMPDIR   — filesDir/tmp
#   PATH     — $PREFIX/bin:$PREFIX/bin/applets:/system/bin:/bin
#
# Why this script exists instead of curl | bash:
#   The remote install.sh at myopenclawhub.com assumes a standard Termux
#   environment (PREFIX=/data/data/com.termux/files/usr). Running it without
#   the correct PREFIX causes apt/dpkg to look for lock files at the Termux
#   path, which is inaccessible from this app's sandbox.
#
#   This local script uses the env vars already set by TerminalManager, so
#   apt/dpkg always find their lock files at:
#     $PREFIX/var/lib/dpkg/lock-frontend  ← correct, accessible
# =============================================================================

set -eu

# ── Validate environment ──────────────────────────────────────────────────────
: "${PREFIX:?PREFIX not set. Run this script via the OpenClaw terminal.}"
: "${HOME:?HOME not set.}"
: "${TMPDIR:=${PREFIX}/../tmp}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${HOME}/.openclaw-android/logs/install-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$(dirname "$LOG_FILE")" "$TMPDIR"

log() {
    local ts
    ts="$(date '+%H:%M:%S' 2>/dev/null || echo '??:??:??')"
    echo "[${ts}] $*" | tee -a "$LOG_FILE"
}

log_ok()   { log "[OK]   $*"; }
log_err()  { log "[ERR]  $*" >&2; }
log_warn() { log "[WARN] $*"; }

log "=== OpenClaw Android Installer ==="
log "  PREFIX  = $PREFIX"
log "  HOME    = $HOME"
log "  TMPDIR  = $TMPDIR"
log "  PATH    = $PATH"

# ── Verify bootstrap is ready ─────────────────────────────────────────────────
if [ ! -x "$PREFIX/bin/bash" ] && [ ! -x "$PREFIX/bin/sh" ]; then
    log_err "Bootstrap not installed. No shell found at $PREFIX/bin/"
    log_err "Complete the bootstrap setup first."
    exit 1
fi

# ── Verify apt is available ───────────────────────────────────────────────────
APT_BIN="$PREFIX/bin/apt-get"
if [ ! -x "$APT_BIN" ]; then
    log_warn "apt-get not found at $APT_BIN"
    log_warn "Trying pkg as fallback..."
    APT_BIN="$PREFIX/bin/pkg"
fi

# ── Ensure dpkg/apt directories exist ────────────────────────────────────────
# Skip if already exists and has correct permissions to save time
for dir in \
    "$PREFIX/var/lib/dpkg" \
    "$PREFIX/var/lib/dpkg/info" \
    "$PREFIX/var/lib/dpkg/updates" \
    "$PREFIX/var/lib/dpkg/parts" \
    "$PREFIX/var/lib/apt/lists" \
    "$PREFIX/var/lib/apt/lists/partial" \
    "$PREFIX/var/cache/apt/archives/partial" \
    "$PREFIX/var/log/apt"; do
    if [ ! -d "$dir" ]; then
        mkdir -p "$dir"
        chmod 755 "$dir"
    fi
done

# Ensure dpkg status file exists
[ -f "$PREFIX/var/lib/dpkg/status" ] || touch "$PREFIX/var/lib/dpkg/status"
[ -f "$PREFIX/var/lib/dpkg/available" ] || touch "$PREFIX/var/lib/dpkg/available"

log_ok "dpkg/apt directories verified"

# ── Configure apt.conf ──────────────────────────────────────────────────────
# Write apt.conf with absolute paths to prevent the duplicated-path bug.
# Bug: if Dir::State::status is relative, apt resolves it as:
#   Dir + Dir::State + Dir::State::status
#   = "$PREFIX/" + "var/lib/apt/" + "var/lib/dpkg/status"
#   = "$PREFIX/var/lib/apt/var/lib/dpkg/status"  ← WRONG (duplicated path)
# Fix: use an absolute path for Dir::State::status.
APT_CONF="$PREFIX/etc/apt/apt.conf"
mkdir -p "$(dirname "$APT_CONF")"
cat > "$APT_CONF" << APTCONF
Dir "$PREFIX/";
Dir::State "var/lib/apt/";
Dir::State::status "$PREFIX/var/lib/dpkg/status";
Dir::Cache "var/cache/apt/";
Dir::Log "var/log/apt/";
Dir::Etc "etc/apt/";
Dir::Etc::SourceList "etc/apt/sources.list";
Dir::Etc::SourceParts "";
Dir::Bin::dpkg "$PREFIX/bin/dpkg";
Dir::Bin::Methods "$PREFIX/lib/apt/methods/";
Dir::Bin::apt-key "$PREFIX/bin/apt-key";
Dpkg::Options:: "--force-configure-any";
Dpkg::Options:: "--force-bad-path";
Dpkg::Options:: "--instdir=$PREFIX";
Dpkg::Options:: "--admindir=$PREFIX/var/lib/dpkg";
Acquire::AllowInsecureRepositories "true";
APT::Get::AllowUnauthenticated "true";
APTCONF

export APT_CONFIG="$APT_CONF"
export DPKG_ADMINDIR="$PREFIX/var/lib/dpkg"
export DPKG_ROOT="$PREFIX"
log_ok "apt.conf written: $APT_CONF"

# ── Install OpenClaw via pkg/apt ──────────────────────────────────────────────
log "Installing OpenClaw dependencies..."

# Update package lists and handle mirrors if failed
if [ -x "$PREFIX/bin/pkg" ]; then
    # Skip online package updates if we are in a glibc-based offline environment
    if [ -f "$HOME/.openclaw-android/.glibc-arch" ] || [ -f "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ]; then
        log "Offline glibc environment detected — skipping pkg update"
    else
        log "Running: pkg update"
        if ! pkg update -y 2>&1 | tee -a "$LOG_FILE"; then
            log_warn "pkg update failed, attempting to change mirror to Tsinghua..."
            if [ -f "$PREFIX/etc/apt/sources.list" ]; then
                sed -i 's@^\(deb.*\)https://packages.termux.dev/apt/termux-main@\1https://mirrors.tuna.tsinghua.edu.cn/termux/termux-main@' "$PREFIX/etc/apt/sources.list" || true
                pkg update -y 2>&1 | tee -a "$LOG_FILE" || log_warn "pkg update still had errors (continuing)"
            fi
        fi
    fi
elif [ -x "$APT_BIN" ]; then
    log "Running: apt-get update"
    DEBIAN_FRONTEND=noninteractive "$APT_BIN" update 2>&1 | tee -a "$LOG_FILE" || \
        log_warn "apt-get update had errors (continuing)"
fi

# Install Node.js and Git if not already present
NODE_VERSION="v22.13.1"
NODE_DIR="$HOME/.openclaw-android/node"
NODE_BIN="$NODE_DIR/bin/node"

if [ ! -x "$NODE_BIN" ]; then
    log "Node.js no encontrado. Descargando binario oficial linux-arm64..."
    mkdir -p "$NODE_DIR" "$TMPDIR"

    set +e
    curl -fsSL --connect-timeout 30 --retry 3 \
        "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.gz" \
        -o "$TMPDIR/node.tar.gz"
    _CURL_EXIT=$?
    set -e

    if [ "$_CURL_EXIT" -ne 0 ]; then
        log_err "Fallo al descargar Node.js. Verifica conexión a internet."
        exit 1
    fi

    tar -xzf "$TMPDIR/node.tar.gz" -C "$NODE_DIR" --strip-components=1
    rm -f "$TMPDIR/node.tar.gz"

    # Crear wrapper glibc para node
    NODE_REAL="$NODE_DIR/bin/node"
    NODE_WRAPPER="$HOME/.openclaw-android/bin/node"
    mkdir -p "$(dirname "$NODE_WRAPPER")"

    cat > "$NODE_WRAPPER" << WRAPPER
#!/system/bin/sh
export LD_LIBRARY_PATH="$PREFIX/glibc/lib:$PREFIX/lib:\$LD_LIBRARY_PATH"
unset LD_PRELOAD
exec "$NODE_REAL" "\$@"
WRAPPER
    chmod +x "$NODE_WRAPPER"

    log_ok "Node.js $NODE_VERSION instalado en $NODE_DIR"
fi

# Instalar git via pkg si no existe
if ! command -v git >/dev/null 2>&1; then
    log "Instalando git..."
    set +e
    if [ -x "$PREFIX/bin/pkg" ]; then
        pkg install -y git 2>&1 | tee -a "$LOG_FILE" || log_warn "git no pudo instalarse"
    fi
    set -e
fi

# Create openclaw-update CLI script
UPDATE_SCRIPT="$PREFIX/bin/openclaw-update"
cat > "$UPDATE_SCRIPT" << 'EOF'
#!/system/bin/sh
echo "=== OpenClaw Updater ==="
echo "Actualizando OpenClaw a la última versión..."
if command -v npm >/dev/null 2>&1; then
    npm install -g openclaw
    echo "¡Actualización completada!"
else
    echo "Error: npm no encontrado."
    exit 1
fi
EOF
chmod +x "$UPDATE_SCRIPT"
log_ok "openclaw-update CLI command created"

# ── Install OpenClaw via npm with retry logic ────────────────────────────────
NPM_BIN="$HOME/.openclaw-android/node/bin/npm"
if [ ! -x "$NPM_BIN" ]; then
    NPM_BIN="$PREFIX/bin/npm"
fi

# Incremental check: Skip npm install if OpenClaw is already there
OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
if [ -f "$OC_MJS" ]; then
    log_ok "OpenClaw already installed at $OC_MJS. Skipping npm install."
else
    # Verify npm registry connectivity before installing

    _NPM_RETRIES=3
    _NPM_RETRY_DELAY=5
    _NPM_SUCCESS=false

    for _try in $(seq 1 $_NPM_RETRIES); do
        log "  Attempt $_try/$_NPM_RETRIES..."
        if "$NPM_BIN" install -g openclaw --ignore-scripts 2>&1 | tee -a "$LOG_FILE"; then
            _NPM_SUCCESS=true
            break
        else
            if [ "$_try" -lt "$_NPM_RETRIES" ]; then
                log_warn "Install failed, retrying in ${_NPM_RETRY_DELAY}s..."
                sleep $_NPM_RETRY_DELAY
                _NPM_RETRY_DELAY=$((_NPM_RETRY_DELAY * 2))
                # Clean npm cache before retry
                rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
            fi
        fi
    done

    if [ "$_NPM_SUCCESS" != "true" ]; then
        log_err "npm install failed after $_NPM_RETRIES attempts"
        log_err "Last npm output (last 20 lines):"
        "$NPM_BIN" install -g openclaw --ignore-scripts 2>&1 | tail -20 | tee -a "$LOG_FILE" || true
        log_err "Diagnostic info:"
        log_err "  - Node: $("$NPM_BIN" --version 2>&1 || echo 'NOT FOUND')"
        log_err "  - Registry: ${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}"
        log_err "  - SSL certs: $([ -s "$PREFIX/etc/tls/cert.pem" ] && echo 'OK' || echo 'MISSING')"
    fi

    # Verify installation succeeded
    OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    if [ ! -f "$OC_MJS" ]; then
        log_err "npm install completed but openclaw.mjs not found at $OC_MJS"
        log_err "Check npm output above for errors."
        exit 1
    fi
    log_ok "OpenClaw installed via npm"
else
    log_warn "npm not found — attempting direct download"
    # Fallback: download and run the remote installer with correct env already set
    if command -v curl >/dev/null 2>&1; then
        log "Downloading remote installer..."
        curl -fsSL https://myopenclawhub.com/install | bash 2>&1 | tee -a "$LOG_FILE"
    else
        log_err "Neither npm nor curl available. Cannot install OpenClaw."
        exit 1
    fi
fi
fi

# ── Write installation marker ─────────────────────────────────────────────────
OCA_DIR="$HOME/.openclaw-android"
mkdir -p "$OCA_DIR"
cat > "$OCA_DIR/installed.json" << MARKER
{
  "installed": true,
  "source": "local-install-sh",
  "installedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo 'unknown')",
  "prefix": "$PREFIX",
  "home": "$HOME"
}
MARKER

log_ok "Installation marker written: $OCA_DIR/installed.json"
log ""
log "=== OpenClaw installation complete! ==="
log "Run 'openclaw-start.sh' or type 'openclaw gateway' to start."
