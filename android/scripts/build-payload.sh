#!/usr/bin/env bash
# =============================================================================
# build-payload.sh — OpenClaw Android Payload Builder
# =============================================================================
# Run this script INSIDE Termux (aarch64) to produce a self-contained payload
# directory that can be bundled into the APK as assets/payload/.
#
# Output: ./payload/
#   ├── glibc-aarch64.tar.xz      (glibc runtime: ld.so, libc, libstdc++, etc.)
#   ├── certs/                    (CA certificate bundle)
#   │   └── cert.pem
#   ├── bin/                      (node wrapper, npm wrapper, npx wrapper)
#   │   ├── node
#   │   ├── npm
#   │   └── npx
#   ├── lib/                      (node.real binary + node_modules)
#   │   ├── node/
#   │   │   ├── bin/node.real
#   │   │   └── lib/node_modules/
#   │   └── openclaw/             (openclaw package)
#   ├── patches/
#   │   └── glibc-compat.js
#   ├── post-setup.sh
#   ├── run-openclaw.sh
#   └── PAYLOAD_CHECKSUM.sha256
#
# Usage (in Termux):
#   chmod +x build-payload.sh
#   ./build-payload.sh
#
# Requirements (auto-installed if missing):
#   pkg install nodejs glibc ca-certificates git xz-utils
# =============================================================================

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
NODE_VERSION="22.22.0"
GLIBC_VERSION="2.42-0"
GCC_LIBS_VERSION="14.2.1-1"
PAYLOAD_DIR="$(pwd)/payload"
WORK_DIR="$(pwd)/.build-work"
LOG_FILE="$(pwd)/build-payload.log"

# Termux paths (builder environment only)
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"

# Pacman mirrors for glibc packages
PACMAN_MIRRORS=(
    "https://service.termux-pacman.dev/gpkg/aarch64"
    "https://packages.termux.dev/pacman/glibc-aarch64"
    "https://mirror.termux-pacman.dev/gpkg/aarch64"
)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ── Logging ───────────────────────────────────────────────────────────────────
log()     { local ts; ts="$(date '+%H:%M:%S')"; echo -e "[${ts}] $*" | tee -a "$LOG_FILE"; }
log_ok()  { log "${GREEN}✓${NC} $*"; }
log_err() { log "${RED}✗${NC} $*" >&2; }
log_warn(){ log "${YELLOW}⚠${NC} $*"; }
log_step(){ log "${BLUE}▸${NC} $*"; }

# ── Prerequisite check ────────────────────────────────────────────────────────
check_prerequisites() {
    log_step "Checking prerequisites..."

    # Must run in Termux
    if [ ! -d "$TERMUX_PREFIX" ]; then
        log_err "This script must run inside Termux (PREFIX not found: $TERMUX_PREFIX)"
        exit 1
    fi

    # Must be aarch64
    local arch
    arch="$(uname -m)"
    if [ "$arch" != "aarch64" ]; then
        log_err "Must run on aarch64 device (got: $arch)"
        exit 1
    fi

    # Install required tools
    local missing=()
    for tool in node npm curl tar xz sha256sum; do
        command -v "$tool" &>/dev/null || missing+=("$tool")
    done

    if [ ${#missing[@]} -gt 0 ]; then
        log_warn "Installing missing tools: ${missing[*]}"
        pkg install -y nodejs xz-utils 2>/dev/null || true
    fi

    # Install glibc if not present
    if [ ! -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ]; then
        log_warn "glibc not found — installing via pkg..."
        pkg install -y glibc 2>/dev/null || \
        pkg install -y glibc-repo 2>/dev/null && pkg install -y glibc 2>/dev/null || true
    fi

    # Install ca-certificates if not present
    if [ ! -f "$TERMUX_PREFIX/etc/tls/cert.pem" ]; then
        log_warn "ca-certificates not found — installing..."
        pkg install -y ca-certificates 2>/dev/null || true
    fi

    log_ok "Prerequisites satisfied"
}

# ── Directory setup ───────────────────────────────────────────────────────────
setup_dirs() {
    log_step "Setting up build directories..."
    rm -rf "$PAYLOAD_DIR" "$WORK_DIR"
    mkdir -p \
        "$PAYLOAD_DIR/certs" \
        "$PAYLOAD_DIR/bin" \
        "$PAYLOAD_DIR/lib/node" \
        "$PAYLOAD_DIR/patches" \
        "$WORK_DIR/glibc-extract" \
        "$WORK_DIR/pkgs"
    log_ok "Directories created: $PAYLOAD_DIR"
}

# ── glibc archive verification ───────────────────────────────────────────────
# Three-layer check: (1) non-empty file, (2) xz integrity, (3) linker present.
# Captures tar output into a variable to avoid SIGPIPE false positives with
# pipefail. Safe on Linux, Termux, and CI/CD environments.
_verify_glibc_archive() {
    local archive="$1"

    # Layer 1: file exists and is non-empty
    if [ ! -s "$archive" ]; then
        log_err "glibc archive is empty or missing: $archive"
        return 1
    fi

    # Layer 2: xz structural integrity (fast — does not decompress)
    if command -v xz >/dev/null 2>&1; then
        if ! xz -t "$archive" 2>/dev/null; then
            log_err "glibc archive failed xz integrity check (file is corrupt)"
            return 1
        fi
    fi

    # Layer 3: linker present inside the archive
    # Capture output first — avoids SIGPIPE when grep -q exits early,
    # which with set -o pipefail would produce a false pipeline failure.
    local _content
    _content=$(tar -tJf "$archive" 2>/dev/null || true)

    if [ -z "$_content" ]; then
        log_err "glibc archive could not be listed (tar returned no output)"
        return 1
    fi

    if ! echo "$_content" | grep -q "ld-linux"; then
        log_err "glibc archive verification failed — ld-linux linker not found"
        log_err "Archive contents (first 10 entries):"
        echo "$_content" | head -10 | while IFS= read -r _line; do
            log_err "  $_line"
        done
        return 1
    fi

    local _lib_count
    _lib_count=$(echo "$_content" | grep -c '\.so' || true)
    log_ok "glibc archive verified: ${_lib_count} shared libs, linker present"
    return 0
}

# ── Step 1: Bundle glibc ──────────────────────────────────────────────────────
bundle_glibc() {
    log_step "[1/6] Bundling glibc runtime..."

    local GLIBC_SRC="$TERMUX_PREFIX/glibc"
    if [ ! -d "$GLIBC_SRC/lib" ]; then
        log_err "glibc not found at $GLIBC_SRC"
        log_err "Install it first: pkg install glibc"
        log_err "Or: pkg install glibc-repo && pkg install glibc"
        exit 1
    fi

    # Collect essential glibc files
    local GLIBC_STAGE="$WORK_DIR/glibc-stage"
    mkdir -p "$GLIBC_STAGE/lib" "$GLIBC_STAGE/etc"

    # Core linker and libraries
    local REQUIRED_LIBS=(
        "ld-linux-aarch64.so.1"
        "libc.so.6"
        "libm.so.6"
        "libpthread.so.0"
        "libdl.so.2"
        "librt.so.1"
        "libresolv.so.2"
        "libnss_dns.so.2"
        "libnss_files.so.2"
        "libutil.so.1"
    )

    # Optional but useful
    local OPTIONAL_LIBS=(
        "libstdc++.so.6"
        "libgcc_s.so.1"
        "libz.so.1"
        "libcap.so.2"
        "libcrypto.so.3"
        "libssl.so.3"
    )

    local copied=0
    for lib in "${REQUIRED_LIBS[@]}"; do
        local src="$GLIBC_SRC/lib/$lib"
        if [ -f "$src" ] || [ -L "$src" ]; then
            cp -aL "$src" "$GLIBC_STAGE/lib/" 2>/dev/null || cp -a "$src" "$GLIBC_STAGE/lib/"
            copied=$((copied + 1))
        else
            log_warn "Required lib not found: $lib (may be embedded in libc)"
        fi
    done

    for lib in "${OPTIONAL_LIBS[@]}"; do
        local src="$GLIBC_SRC/lib/$lib"
        if [ -f "$src" ] || [ -L "$src" ]; then
            cp -aL "$src" "$GLIBC_STAGE/lib/" 2>/dev/null || cp -a "$src" "$GLIBC_STAGE/lib/"
        fi
    done

    # Also copy any versioned symlinks (libstdc++.so.6.0.XX etc.)
    for f in "$GLIBC_SRC/lib"/lib*.so.*; do
        [ -f "$f" ] || [ -L "$f" ] || continue
        local bn
        bn="$(basename "$f")"
        [ -f "$GLIBC_STAGE/lib/$bn" ] || cp -a "$f" "$GLIBC_STAGE/lib/" 2>/dev/null || true
    done

    # Copy glibc etc/ (resolv.conf template, hosts, nsswitch.conf)
    for f in resolv.conf hosts nsswitch.conf; do
        [ -f "$GLIBC_SRC/etc/$f" ] && cp "$GLIBC_SRC/etc/$f" "$GLIBC_STAGE/etc/" 2>/dev/null || true
    done

    # Create nsswitch.conf if missing (needed for DNS resolution)
    if [ ! -f "$GLIBC_STAGE/etc/nsswitch.conf" ]; then
        cat > "$GLIBC_STAGE/etc/nsswitch.conf" << 'EOF'
passwd:     files
group:      files
shadow:     files
hosts:      files dns
networks:   files
protocols:  files
services:   files
EOF
    fi

    # Create hosts template
    if [ ! -f "$GLIBC_STAGE/etc/hosts" ]; then
        cat > "$GLIBC_STAGE/etc/hosts" << 'EOF'
127.0.0.1 localhost localhost.localdomain
::1       localhost ip6-localhost ip6-loopback
EOF
    fi

    # Verify linker is present and executable
    if [ ! -f "$GLIBC_STAGE/lib/ld-linux-aarch64.so.1" ]; then
        log_err "ld-linux-aarch64.so.1 not found in glibc stage"
        exit 1
    fi
    chmod +x "$GLIBC_STAGE/lib/ld-linux-aarch64.so.1"

    # ── FIX: 16KB alignment & Bash link error ──
    # Prune Koffi binaries that cause 16KB alignment issues
    find "$GLIBC_STAGE/lib" -name "*koffi*" -delete 2>/dev/null || true

    # Bundle essential Bionic support libs that Termux binaries (like bash) need
    log "  Bundling Bionic support libs for bash..."
    [ -f "$TERMUX_PREFIX/lib/libandroid-support.so" ] && cp "$TERMUX_PREFIX/lib/libandroid-support.so" "$GLIBC_STAGE/lib/"
    [ -f "$TERMUX_PREFIX/lib/libiconv.so" ] && cp "$TERMUX_PREFIX/lib/libiconv.so" "$GLIBC_STAGE/lib/"

    # Pack into tar.xz with placeholder-friendly structure
    # Structure inside archive: glibc/lib/*, glibc/etc/*
    local GLIBC_WRAP="$WORK_DIR/glibc-wrap"
    mkdir -p "$GLIBC_WRAP/glibc"
    cp -a "$GLIBC_STAGE/lib" "$GLIBC_WRAP/glibc/"
    cp -a "$GLIBC_STAGE/etc" "$GLIBC_WRAP/glibc/"

    log "  Compressing glibc (~$(du -sh "$GLIBC_WRAP" | cut -f1))..."
    # Note: --owner/--group removed — require root; Termux runs as unprivileged user
    tar -cJf "$PAYLOAD_DIR/glibc-aarch64.tar.xz" \
        -C "$GLIBC_WRAP" \
        glibc/

    # Verify archive
    # IMPORTANT: do NOT pipe tar directly into grep when set -o pipefail is active.
    # grep -q exits on first match, causing tar to receive SIGPIPE (exit 141),
    # which pipefail interprets as a pipeline failure — a false positive.
    # Fix: capture the full listing first, then grep the variable.
    if ! _verify_glibc_archive "$PAYLOAD_DIR/glibc-aarch64.tar.xz"; then
        exit 1
    fi

    log_ok "glibc bundled: $(du -sh "$PAYLOAD_DIR/glibc-aarch64.tar.xz" | cut -f1)"
}

# ── Step 2: Bundle CA certificates ───────────────────────────────────────────
bundle_certs() {
    log_step "[2/6] Bundling CA certificates..."

    local CERT_BUNDLE=""

    # Try Termux cert bundle first
    for candidate in \
        "$TERMUX_PREFIX/etc/tls/cert.pem" \
        "$TERMUX_PREFIX/etc/ssl/certs/ca-certificates.crt" \
        "/etc/ssl/certs/ca-certificates.crt" \
        "/system/etc/security/cacerts"; do

        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            CERT_BUNDLE="$candidate"
            break
        elif [ -d "$candidate" ]; then
            # Android system certs directory
            cat "$candidate"/*.0 > "$PAYLOAD_DIR/certs/cert.pem" 2>/dev/null || true
            if [ -s "$PAYLOAD_DIR/certs/cert.pem" ]; then
                log_ok "CA certs from Android system: $(wc -l < "$PAYLOAD_DIR/certs/cert.pem") lines"
                return 0
            fi
        fi
    done

    # Build bundle from individual certs
    if [ -z "$CERT_BUNDLE" ] && [ -d "$TERMUX_PREFIX/etc/tls/certs" ]; then
        cat "$TERMUX_PREFIX/etc/tls/certs"/*.pem > "$PAYLOAD_DIR/certs/cert.pem" 2>/dev/null || true
        CERT_BUNDLE="$PAYLOAD_DIR/certs/cert.pem"
    fi

    if [ -n "$CERT_BUNDLE" ] && [ -f "$CERT_BUNDLE" ]; then
        cp "$CERT_BUNDLE" "$PAYLOAD_DIR/certs/cert.pem"
        local cert_count
        cert_count=$(grep -c "BEGIN CERTIFICATE" "$PAYLOAD_DIR/certs/cert.pem" 2>/dev/null || echo "0")
        if [ "$cert_count" -eq 0 ]; then
            log_warn "Cert bundle copied but contains 0 PEM certificates — HTTPS may fail at runtime"
        else
            log_ok "CA certificates bundled: $cert_count certificates"
        fi
    else
        log_warn "No CA certificates found — SSL will use Android system certs at runtime"
        # Create empty placeholder — post-setup.sh will populate from /system/etc/security/cacerts
        touch "$PAYLOAD_DIR/certs/cert.pem"
    fi
}

# ── Step 3: Bundle Node.js ────────────────────────────────────────────────────
bundle_nodejs() {
    log_step "[3/6] Bundling Node.js v${NODE_VERSION}..."

    local OCA_DIR="$TERMUX_HOME/.openclaw-android"
    local NODE_DIR="$OCA_DIR/node"
    local NODE_REAL="$NODE_DIR/bin/node.real"

    # Find node.real (the actual glibc ELF binary)
    if [ ! -f "$NODE_REAL" ]; then
        # Try to find it
        NODE_REAL=$(find "$TERMUX_HOME" -name "node.real" -type f 2>/dev/null | head -1 || true)
        if [ -z "$NODE_REAL" ]; then
            log_warn "node.real not found — downloading Node.js v${NODE_VERSION}..."
            _download_nodejs
            NODE_REAL="$WORK_DIR/node-download/bin/node.real"
        fi
    fi

    if [ ! -f "$NODE_REAL" ]; then
        log_err "Cannot find or download node.real"
        exit 1
    fi

    # Verify it's a glibc ELF binary
    local elf_magic
    elf_magic=$(head -c 4 "$NODE_REAL" | od -An -tx1 | tr -d ' \n')
    if [ "$elf_magic" != "7f454c46" ]; then
        log_err "node.real is not an ELF binary: $NODE_REAL"
        exit 1
    fi

    # Copy node.real
    mkdir -p "$PAYLOAD_DIR/lib/node/bin"
    cp "$NODE_REAL" "$PAYLOAD_DIR/lib/node/bin/node.real"
    chmod +x "$PAYLOAD_DIR/lib/node/bin/node.real"

    # Copy node_modules (npm, npx)
    local NODE_MODULES_SRC=""
    for candidate in \
        "$NODE_DIR/lib/node_modules" \
        "$TERMUX_HOME/.openclaw-android/node/lib/node_modules" \
        "$(dirname "$(dirname "$NODE_REAL")")/lib/node_modules"; do
        if [ -d "$candidate/npm" ]; then
            NODE_MODULES_SRC="$candidate"
            break
        fi
    done

    if [ -n "$NODE_MODULES_SRC" ]; then
        mkdir -p "$PAYLOAD_DIR/lib/node/lib"
        cp -a "$NODE_MODULES_SRC" "$PAYLOAD_DIR/lib/node/lib/"
        log_ok "node_modules copied from $NODE_MODULES_SRC"
    else
        log_warn "npm node_modules not found — npm/npx wrappers will be limited"
    fi

    log_ok "Node.js bundled: $(du -sh "$PAYLOAD_DIR/lib/node" | cut -f1)"
}

_download_nodejs() {
    local NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
    local DEST="$WORK_DIR/node-download"
    mkdir -p "$DEST"

    log "  Downloading Node.js v${NODE_VERSION}..."
    curl -fSL --max-time 300 \
        "https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}.tar.xz" \
        -o "$WORK_DIR/${NODE_TAR}.tar.xz"

    tar -xJf "$WORK_DIR/${NODE_TAR}.tar.xz" -C "$DEST" --strip-components=1

    # Move node → node.real
    if [ -f "$DEST/bin/node" ] && [ ! -L "$DEST/bin/node" ]; then
        mv "$DEST/bin/node" "$DEST/bin/node.real"
    fi
}

# ── Step 4: Bundle OpenClaw ───────────────────────────────────────────────────
bundle_openclaw() {
    log_step "[4/6] Bundling OpenClaw..."

    local OC_SRC=""
    for candidate in \
        "$TERMUX_PREFIX/lib/node_modules/openclaw" \
        "$TERMUX_HOME/.openclaw-android/node/lib/node_modules/openclaw"; do
        if [ -d "$candidate" ] && [ -f "$candidate/openclaw.mjs" ]; then
            OC_SRC="$candidate"
            break
        fi
    done

    if [ -z "$OC_SRC" ]; then
        log_warn "OpenClaw not found locally — installing to get package..."
        # Install to a temp location
        local TMP_NPM="$WORK_DIR/npm-install"
        mkdir -p "$TMP_NPM"
        npm install --prefix "$TMP_NPM" openclaw@latest --ignore-scripts 2>/dev/null || true
        OC_SRC="$TMP_NPM/node_modules/openclaw"
    fi

    if [ -d "$OC_SRC" ]; then
        mkdir -p "$PAYLOAD_DIR/lib/openclaw"
        cp -a "$OC_SRC/." "$PAYLOAD_DIR/lib/openclaw/"
        log_ok "OpenClaw bundled: $(du -sh "$PAYLOAD_DIR/lib/openclaw" | cut -f1)"
    else
        log_warn "OpenClaw not bundled — will be installed at runtime via npm"
    fi
}

# ── Step 5: Bundle glibc-compat.js ───────────────────────────────────────────
bundle_patches() {
    log_step "[5/6] Bundling patches..."

    # Copy glibc-compat.js from the project assets
    local COMPAT_SRC=""
    for candidate in \
        "$(dirname "$0")/../android/app/src/main/assets/glibc-compat.js" \
        "$TERMUX_HOME/.openclaw-android/patches/glibc-compat.js" \
        "$TERMUX_PREFIX/share/openclaw-app/patches/glibc-compat.js"; do
        if [ -f "$candidate" ]; then
            COMPAT_SRC="$candidate"
            break
        fi
    done

    if [ -n "$COMPAT_SRC" ]; then
        cp "$COMPAT_SRC" "$PAYLOAD_DIR/patches/glibc-compat.js"
        log_ok "glibc-compat.js copied"
    else
        log_warn "glibc-compat.js not found — creating minimal version"
        _create_minimal_glibc_compat
    fi
}

_create_minimal_glibc_compat() {
    cat > "$PAYLOAD_DIR/patches/glibc-compat.js" << 'COMPAT_EOF'
'use strict';
// Minimal glibc-compat shim for OpenClaw on Android
const os = require('os');
const path = require('path');
const fs = require('fs');

// Fix process.execPath to point to wrapper, not ld.so
const _wrapperPath = process.env._OA_WRAPPER_PATH || path.join(
  process.env.HOME || '/data/data/com.openclaw.android/files/home',
  '.openclaw-android', 'bin', 'node'
);
try {
  if (fs.existsSync(_wrapperPath)) {
    Object.defineProperty(process, 'execPath', {
      value: _wrapperPath, writable: true, configurable: true,
    });
  }
} catch (_) {}

// Clean LD_PRELOAD to prevent bionic libtermux-exec.so from crashing glibc
delete process.env.LD_PRELOAD;
delete process.env._OA_ORIG_LD_PRELOAD;

// os.cpus() fallback — SELinux blocks /proc/stat on Android 8+
const _origCpus = os.cpus;
os.cpus = function() {
  const r = _origCpus.call(os);
  return r.length > 0 ? r : [{ model: 'unknown', speed: 0, times: { user:0, nice:0, sys:0, idle:0, irq:0 } }];
};

// os.networkInterfaces() safety
const _origNI = os.networkInterfaces;
os.networkInterfaces = function() {
  try { return _origNI.call(os); } catch (_) { return {}; }
};
COMPAT_EOF
}

# ── Step 6: Generate scripts ──────────────────────────────────────────────────
generate_scripts() {
    log_step "[6/6] Generating runtime scripts..."

    # SCRIPT_DIR: directory where build-payload.sh physically lives.
    # When run as ~/build-payload.sh this will be ~/
    # When run from inside the repo it will be android/scripts/
    local SCRIPT_DIR
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

    # Candidate locations for the assets directory, in priority order:
    #  1. OA_ASSETS_DIR env var — explicit override for any layout
    #  2. Repo layout:  <SCRIPT_DIR>/../app/src/main/assets  (run from android/scripts/)
    #  3. Same dir as the script (scp workflow: all files copied to ~/)
    #  4. Standard openclaw-android home dir (post-setup may have placed them there)
    local ASSETS_CANDIDATES=(
        "${OA_ASSETS_DIR:-}"
        "${SCRIPT_DIR}/../app/src/main/assets"
        "${SCRIPT_DIR}"
        "${TERMUX_HOME}/.openclaw-android/assets"
    )

    for script in post-setup.sh run-openclaw.sh; do
        local src=""
        for assets_dir in "${ASSETS_CANDIDATES[@]}"; do
            [ -z "$assets_dir" ] && continue
            local candidate="${assets_dir}/${script}"
            if [ -f "$candidate" ]; then
                src="$candidate"
                break
            fi
        done

        if [ -n "$src" ]; then
            cp "$src" "$PAYLOAD_DIR/$script"
            chmod +x "$PAYLOAD_DIR/$script"
            log_ok "Copied $script from $src"
        else
            log_warn "$script not found — skipping"
            log_warn "  Copy it next to build-payload.sh, or set OA_ASSETS_DIR:"
            log_warn "  scp -P 8022 android/app/src/main/assets/$script user@phone:~/"
        fi
    done
}

# ── Metadata Generation ───────────────────────────────────────────────────────
# Creates a VERSION.json file inside the payload for app-side detection.
generate_metadata() {
    log_step "Generating version metadata..."
    cat > "$PAYLOAD_DIR/VERSION.json" << EOF
{
  "node": "$NODE_VERSION",
  "glibc": "$GLIBC_VERSION",
  "gcc_libs": "$GCC_LIBS_VERSION",
  "build_date": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "target": "aarch64-android",
  "version": "1.0.0"
}
EOF
    log_ok "Metadata generated: VERSION.json"
}

# ── Optimization ──────────────────────────────────────────────────────────────
# Reduces payload size by removing development dependencies and logs.
cleanup_payload() {
    log_step "Optimizing payload size..."
    
    # Prune node_modules in OpenClaw if it exists
    local oc_dir="$PAYLOAD_DIR/lib/openclaw"
    if [ -d "$oc_dir" ] && [ -f "$oc_dir/package.json" ]; then
        log "  Pruning OpenClaw node_modules..."
        (cd "$oc_dir" && npm prune --production &>/dev/null) || true
    fi
    
    # Remove large/unnecessary files
    find "$PAYLOAD_DIR" -name "*.log" -delete
    find "$PAYLOAD_DIR" -name ".git" -type d -exec rm -rf {} + 2>/dev/null || true
    
    log_ok "Payload optimized"
}

# ── 16KB Alignment & Compatibility Fix ────────────────────────────────────────
# Removes native libraries for other OSs/Archs that trigger Android 15 errors.
prune_native_libs() {
    log_step "Fixing 16KB alignment (removing incompatible libs)..."
    
    local node_modules="$PAYLOAD_DIR/lib/openclaw/node_modules"
    if [ -d "$node_modules/koffi/build/koffi" ]; then
        # Keep ONLY linux_arm64, remove everything else (x64, riscv, freebsd, etc.)
        log "  Cleaning koffi binaries..."
        find "$node_modules/koffi/build/koffi" -mindepth 1 -maxdepth 1 ! -name "linux_arm64" -exec rm -rf {} +
    fi
    
    log_ok "Incompatible native libraries pruned"
}


# ── Checksum generation ───────────────────────────────────────────────────────
# Optimized: generates checksums for all files using a single process stream
# instead of a slow bash 'while read' loop.
generate_checksums() {
    log_step "Generating checksums..."

    local CHECKSUM_FILE="$PAYLOAD_DIR/PAYLOAD_CHECKSUM.sha256"
    cd "$PAYLOAD_DIR"
    
    # Use find + sha256sum directly to avoid bash loop overhead
    # We filter out the checksum file itself and any existing .tar.gz
    find . -type f ! -name "PAYLOAD_CHECKSUM.sha256" ! -name "*.tar.gz" -print0 | \
        xargs -0 sha256sum | sort -k2 > "PAYLOAD_CHECKSUM.sha256"
    
    local file_count
    file_count=$(wc -l < "PAYLOAD_CHECKSUM.sha256")
    log_ok "Checksums generated: $file_count files"
    cd - >/dev/null
}

# ── Automatic Compression ─────────────────────────────────────────────────────
# Packs the entire payload into a single archive for high-speed transfer.
# Uses gzip -1 (fastest) to minimize CPU bottleneck on the phone.
pack_payload() {
    log_step "Packing payload for transfer..."
    
    local ARCHIVE="payload.tar.gz"
    local DEST="$TERMUX_HOME/$ARCHIVE"
    
    # Pack payload/ directory into a single file
    # We use 'tar cf -' piped to 'gzip -1' for maximum throughput on mobile CPU
    tar cf - "payload" | gzip -1 > "$DEST"
    
    log_ok "Payload packed: $DEST ($(du -sh "$DEST" | cut -f1))"
    log "  You can now transfer this single file using:"
    log "  ${BLUE}scp -P 8022 $USER@$(ip addr show wlan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1 | head -1):~/payload.tar.gz .${NC}"
}

# ── Summary ───────────────────────────────────────────────────────────────────
print_summary() {
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  Build complete!"
    echo "════════════════════════════════════════════════════════"
    echo ""
    echo "  Payload directory: $PAYLOAD_DIR"
    echo "  Total size: $(du -sh "$PAYLOAD_DIR" | cut -f1)"
    echo ""
    echo "  Contents:"
    find "$PAYLOAD_DIR" -type f | sort | while read -r f; do
        local rel="${f#$PAYLOAD_DIR/}"
        local size
        size=$(du -sh "$f" | cut -f1)
        printf "    %-50s %s\n" "$rel" "$size"
    done
    echo ""
    echo "  Next steps:"
    echo "    1. Copy payload.tar.gz to your PC"
    echo "    2. Extract it into android/app/src/main/assets/"
    echo "    3. Build the APK: ./gradlew assembleRelease"
    echo ""
    echo "  Log: $LOG_FILE"
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    echo ""
    echo "════════════════════════════════════════════════════════"
    echo "  OpenClaw Android — Payload Builder"
    echo "  Target: aarch64 / Android 10+ / No Termux dependency"
    echo "════════════════════════════════════════════════════════"
    echo ""

    > "$LOG_FILE"

    check_prerequisites
    setup_dirs
    bundle_glibc
    bundle_certs
    bundle_nodejs
    bundle_openclaw
    bundle_patches
    generate_scripts
    generate_metadata
    cleanup_payload
    prune_native_libs
    generate_checksums
    pack_payload
    print_summary
}

main "$@"
