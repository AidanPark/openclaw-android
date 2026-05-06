#!/bin/sh
# =============================================================================
# run-openclaw.sh — OpenClaw Gateway Launcher
# =============================================================================
# Launches the OpenClaw gateway process with the correct glibc environment.
#
# Design constraints:
#   - NO internet required
#   - NO Termux dependency
#   - Uses /system/bin/sh (always available on Android 7+)
#   - All paths resolved from APP_FILES_DIR (app sandbox)
#
# Called by: OpenClawService / JsBridge.launchGateway()
# Environment: set by EnvironmentBuilder.buildEnvironment()
#
# Required env vars:
#   PREFIX         — $APP_FILES_DIR/usr
#   HOME           — $APP_FILES_DIR/home
#   TMPDIR         — $APP_FILES_DIR/tmp
#   LD_LIBRARY_PATH — $PREFIX/glibc/lib:$PREFIX/lib
#   SSL_CERT_FILE  — $PREFIX/etc/tls/cert.pem
# =============================================================================

set -eu

# ── Resolve paths ─────────────────────────────────────────────────────────────
: "${PREFIX:?PREFIX not set — run via EnvironmentBuilder}"
: "${HOME:?HOME not set}"
TMPDIR="${TMPDIR:-${PREFIX}/../tmp}"

OCA_DIR="${HOME}/.openclaw-android"
BIN_DIR="${OCA_DIR}/bin"
NODE_DIR="${OCA_DIR}/node"
NODE_REAL="${NODE_DIR}/bin/node.real"
GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
GLIBC_LIB="${PREFIX}/glibc/lib"
OC_MJS="${PREFIX}/lib/node_modules/openclaw/openclaw.mjs"
CERT_BUNDLE="${PREFIX}/etc/tls/cert.pem"
LOG_DIR="${OCA_DIR}/logs"
MARKER_INSTALLED="${OCA_DIR}/installed.json"

# ── Logging ───────────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR" "$TMPDIR"
LOG_FILE="${LOG_DIR}/openclaw-$(date '+%Y%m%d-%H%M%S' 2>/dev/null || echo 'run').log"
touch "$LOG_FILE" 2>/dev/null || LOG_FILE="/dev/null"

log() {
    local ts
    ts="$(date '+%H:%M:%S' 2>/dev/null || echo '??:??:??')"
    local msg="[${ts}] $*"
    echo "$msg"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null || true
}

log_err() { log "ERROR: $*" >&2; }

# ── Step 1: Validate installation ─────────────────────────────────────────────
log "=== OpenClaw run-openclaw.sh starting ==="
log "  PREFIX  = ${PREFIX}"
log "  HOME    = ${HOME}"
log "  BIN_DIR = ${BIN_DIR}"

if [ ! -f "$MARKER_INSTALLED" ]; then
    log_err "OpenClaw not installed (missing: $MARKER_INSTALLED)"
    log_err "Run post-setup.sh first."
    exit 1
fi

if [ ! -f "$OC_MJS" ]; then
    log_err "openclaw.mjs not found: $OC_MJS"
    log_err "Reinstall OpenClaw."
    exit 1
fi

if [ ! -x "$GLIBC_LDSO" ]; then
    log_err "glibc linker not found or not executable: $GLIBC_LDSO"
    log_err "Re-run post-setup.sh."
    exit 1
fi

if [ ! -x "$NODE_REAL" ]; then
    log_err "Node.js binary not found: $NODE_REAL"
    log_err "Re-run post-setup.sh."
    exit 1
fi

log "  Validation passed"

# ── Step 2: Configure DNS ─────────────────────────────────────────────────────
log "Configuring DNS..."
DNS_CONTENT="nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 8.8.4.4"

for resolv_path in \
    "${PREFIX}/etc/resolv.conf" \
    "${PREFIX}/glibc/etc/resolv.conf"; do
    dir="$(dirname "$resolv_path")"
    mkdir -p "$dir" 2>/dev/null || true
    if [ ! -f "$resolv_path" ] || [ ! -s "$resolv_path" ] || ! grep -q "nameserver" "$resolv_path" 2>/dev/null; then
        printf '%s\n' "$DNS_CONTENT" > "$resolv_path" 2>/dev/null || true
        log "  Written: $resolv_path"
    fi
done

# ── Step 3: Configure SSL ─────────────────────────────────────────────────────
log "Configuring SSL..."

# Rebuild cert bundle from Android system certs if bundle is empty
if [ ! -s "$CERT_BUNDLE" ]; then
    log "  Cert bundle empty — rebuilding from Android system certs..."
    mkdir -p "$(dirname "$CERT_BUNDLE")"
    if [ -d "/system/etc/security/cacerts" ]; then
        _cert_count=0
        for _cert_file in /system/etc/security/cacerts/*.0; do
            [ -f "$_cert_file" ] || continue
            if head -c 27 "$_cert_file" 2>/dev/null | grep -q "BEGIN CERTIFICATE"; then
                cat "$_cert_file" >> "$CERT_BUNDLE" 2>/dev/null || true
                _cert_count=$((_cert_count + 1))
            fi
        done
        log "  Rebuilt cert bundle: $_cert_count PEM certs"
    fi
fi

if [ -s "$CERT_BUNDLE" ]; then
    export SSL_CERT_FILE="$CERT_BUNDLE"
    export CURL_CA_BUNDLE="$CERT_BUNDLE"
    export GIT_SSL_CAINFO="$CERT_BUNDLE"
    log "  SSL_CERT_FILE = $CERT_BUNDLE"
else
    log "  WARNING: No CA certificates available — HTTPS may fail"
fi

# ── Step 4: Configure environment ────────────────────────────────────────────
log "Configuring environment..."

# Ensure LD_LIBRARY_PATH includes glibc libs
export LD_LIBRARY_PATH="${GLIBC_LIB}:${PREFIX}/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Unset LD_PRELOAD — prevents bionic libtermux-exec.so from crashing glibc
unset LD_PRELOAD 2>/dev/null || true

# Set wrapper path for glibc-compat.js process.execPath fix
export _OA_WRAPPER_PATH="${BIN_DIR}/node"

# Load glibc-compat.js shim
_OA_COMPAT="${OCA_DIR}/patches/glibc-compat.js"
if [ -f "$_OA_COMPAT" ]; then
    case "${NODE_OPTIONS:-}" in
        *"$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }-r $_OA_COMPAT" ;;
    esac
    log "  glibc-compat.js loaded"
fi

# OpenClaw flags
export OA_GLIBC=1
export CONTAINER=1
export OPENCLAW_DISABLE_BONJOUR="${OPENCLAW_DISABLE_BONJOUR:-1}"

# Git configuration
export GIT_CONFIG_NOSYSTEM=1
export GIT_EXEC_PATH="${PREFIX}/libexec/git-core"
export GIT_TEMPLATE_DIR="${PREFIX}/share/git-core/templates"

log "  LD_LIBRARY_PATH = $LD_LIBRARY_PATH"
log "  NODE_OPTIONS    = ${NODE_OPTIONS:-<empty>}"

# ── Step 5: Launch OpenClaw ───────────────────────────────────────────────────
log "Launching OpenClaw..."
log "  Command: $GLIBC_LDSO --library-path $GLIBC_LIB $NODE_REAL $OC_MJS"
log "  Log: $LOG_FILE"
log ""

# Exec replaces this shell process — no subshell overhead.
# Note: piping to tee would break exec (creates a subshell).
# Logging is handled by the caller (OpenClawService) via stdout capture.
exec "$GLIBC_LDSO" \
    --library-path "$GLIBC_LIB" \
    "$NODE_REAL" \
    "$OC_MJS" \
    "$@"
