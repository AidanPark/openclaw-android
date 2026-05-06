#!/system/bin/sh
# run.sh — OpenClaw runtime launcher
#
# This script is the single entry point for running OpenClaw gateway.
# It validates the environment, configures DNS/SSL, and launches OpenClaw
# via the glibc-wrapped Node.js.
#
# Called by: OpenClawService / JsBridge.launchGateway()
# Environment: set by EnvironmentBuilder.buildEnvironment()
#
# Required env vars (set by EnvironmentBuilder):
#   PREFIX, HOME, TMPDIR, PATH, LD_LIBRARY_PATH
#   SSL_CERT_FILE, CURL_CA_BUNDLE, RESOLV_CONF
#   OA_GLIBC=1, CONTAINER=1

set -eu

# ── Validate required variables ───────────────────────────────────────────────
: "${PREFIX:?PREFIX not set — run via EnvironmentBuilder}"
: "${HOME:?HOME not set}"
: "${TMPDIR:=${PREFIX}/../tmp}"

OCA_DIR="${HOME}/.openclaw-android"
OCA_BIN="${OCA_DIR}/bin"
NODE_DIR="${OCA_DIR}/node"
GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
GLIBC_LIB="${PREFIX}/glibc/lib"
OC_MJS="${PREFIX}/lib/node_modules/openclaw/openclaw.mjs"
LOG_DIR="${OCA_DIR}/logs"
LOG_FILE="${LOG_DIR}/openclaw-$(date +%Y%m%d-%H%M%S).log"
MARKER_INSTALLED="${OCA_DIR}/installed.json"

mkdir -p "$LOG_DIR" "$TMPDIR"

# ── Logging ───────────────────────────────────────────────────────────────────
log() {
    local ts
    ts="$(date '+%Y-%m-%d %H:%M:%S')"
    echo "[${ts}] $*" | tee -a "$LOG_FILE"
}

log_err() {
    log "ERROR: $*" >&2
}

# ── Step 1: Validate installation ─────────────────────────────────────────────
log "=== OpenClaw run.sh starting ==="
log "  PREFIX  = ${PREFIX}"
log "  HOME    = ${HOME}"
log "  OCA_BIN = ${OCA_BIN}"

if [ ! -f "$MARKER_INSTALLED" ]; then
    log_err "OpenClaw not installed (missing ${MARKER_INSTALLED})"
    log_err "Run the installer first."
    exit 1
fi

if [ ! -f "$OC_MJS" ]; then
    log_err "openclaw.mjs not found: ${OC_MJS}"
    log_err "Reinstall OpenClaw."
    exit 1
fi

if [ ! -x "$GLIBC_LDSO" ]; then
    log_err "glibc linker not found or not executable: ${GLIBC_LDSO}"
    log_err "Reinstall the runtime."
    exit 1
fi

NODE_REAL="${NODE_DIR}/bin/node.real"
if [ ! -x "$NODE_REAL" ]; then
    log_err "Node.js binary not found: ${NODE_REAL}"
    log_err "Reinstall Node.js."
    exit 1
fi

log "  Validation passed"

# ── Step 2: DNS ───────────────────────────────────────────────────────────────
log "Configuring DNS..."
for resolv_path in \
    "${PREFIX}/etc/resolv.conf" \
    "${PREFIX}/glibc/etc/resolv.conf"; do
    if [ ! -s "$resolv_path" ] || ! grep -q "nameserver" "$resolv_path" 2>/dev/null; then
        mkdir -p "$(dirname "$resolv_path")"
        printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > "$resolv_path"
        log "  wrote ${resolv_path}"
    fi
done
export RESOLV_CONF="${PREFIX}/etc/resolv.conf"

# ── Step 3: SSL certificates ──────────────────────────────────────────────────
log "Activating SSL certificates..."
CERT_BUNDLE="${PREFIX}/etc/tls/cert.pem"
CERT_DIR="${PREFIX}/etc/tls/certs"
mkdir -p "${PREFIX}/etc/tls"

if [ ! -s "$CERT_BUNDLE" ]; then
    if [ -d "$CERT_DIR" ] && ls "${CERT_DIR}"/*.pem >/dev/null 2>&1; then
        cat "${CERT_DIR}"/*.pem > "$CERT_BUNDLE"
        log "  cert.pem built from ${CERT_DIR}"
    elif [ -d "/system/etc/security/cacerts" ]; then
        cat /system/etc/security/cacerts/*.0 > "$CERT_BUNDLE" 2>/dev/null || true
        log "  cert.pem built from Android system certs"
    else
        log "  WARN: no CA certificates found — HTTPS may fail"
    fi
fi

export SSL_CERT_FILE="$CERT_BUNDLE"
export CURL_CA_BUNDLE="$CERT_BUNDLE"
export GIT_SSL_CAINFO="$CERT_BUNDLE"

# ── Step 4: Unset LD_PRELOAD ──────────────────────────────────────────────────
# bionic libtermux-exec.so must NOT be loaded into the glibc node process.
# It causes "Could not find a PHDR" crash when ld.so tries to load it.
if [ -n "${LD_PRELOAD:-}" ]; then
    log "  Unsetting LD_PRELOAD (was: ${LD_PRELOAD})"
    unset LD_PRELOAD
fi

# ── Step 5: Configure glibc-compat.js ────────────────────────────────────────
COMPAT_JS="${OCA_DIR}/patches/glibc-compat.js"
if [ -f "$COMPAT_JS" ]; then
    case "${NODE_OPTIONS:-}" in
        *"$COMPAT_JS"*) ;;
        *) export NODE_OPTIONS="${NODE_OPTIONS:+${NODE_OPTIONS} }-r ${COMPAT_JS}" ;;
    esac
    log "  glibc-compat.js loaded via NODE_OPTIONS"
fi

export _OA_WRAPPER_PATH="${OCA_BIN}/node"
export OA_GLIBC=1
export CONTAINER=1

# ── Step 6: Launch OpenClaw ───────────────────────────────────────────────────
log "Launching OpenClaw gateway..."
log "  Command: ${GLIBC_LDSO} --library-path ${GLIBC_LIB} ${NODE_REAL} ${OC_MJS} gateway --host 0.0.0.0"
log "  Log: ${LOG_FILE}"
log "==="

# Rotate old logs (keep last 5)
ls -t "${LOG_DIR}"/openclaw-*.log 2>/dev/null | tail -n +6 | xargs rm -f 2>/dev/null || true

exec "${GLIBC_LDSO}" \
    --library-path "${GLIBC_LIB}" \
    "${NODE_REAL}" \
    "${OC_MJS}" \
    gateway \
    --host 0.0.0.0 \
    "$@"
