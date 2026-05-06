#!/usr/bin/env bash
# docker-build-rootfs.sh — Runs INSIDE the Docker aarch64 container
# Called by build-rootfs.sh. Builds the rootfs from scratch.
#
# Environment: Ubuntu 22.04 arm64
# Output: /output/rootfs-aarch64.tar.xz

set -euo pipefail

OUTPUT="/output/rootfs-aarch64.tar.xz"
STAGING="/tmp/rootfs-staging"
PREFIX="${STAGING}/usr"
HOME_DIR="${STAGING}/home"
TMP_DIR="${STAGING}/tmp"
OCA_DIR="${HOME_DIR}/.openclaw-android"
OCA_BIN="${OCA_DIR}/bin"
NODE_DIR="${OCA_DIR}/node"
NODE_VERSION="22.22.0"
GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"

# Placeholder tokens
PH_PREFIX="__PREFIX__"
PH_HOME="__HOME__"
PH_OCA_DIR="__OCA_DIR__"
PH_OCA_BIN="__OCA_BIN__"
PH_NODE_DIR="__NODE_DIR__"
PH_NODE_REAL="__NODE_REAL__"
PH_GLIBC_LDSO="__GLIBC_LDSO__"
PH_GLIBC_LIB="__GLIBC_LIB__"

echo "[docker-build] Starting rootfs build (aarch64)"

# ── Install host tools ────────────────────────────────────────────────────────
apt-get update -qq
apt-get install -y -qq \
    curl wget tar xz-utils ca-certificates \
    binutils file 2>/dev/null

# ── Create directory structure ────────────────────────────────────────────────
mkdir -p \
    "${PREFIX}/bin" \
    "${PREFIX}/lib" \
    "${PREFIX}/etc/tls/certs" \
    "${PREFIX}/etc/ssl/certs" \
    "${PREFIX}/glibc/lib" \
    "${PREFIX}/glibc/etc" \
    "${PREFIX}/lib/node_modules" \
    "${HOME_DIR}" \
    "${TMP_DIR}" \
    "${OCA_DIR}/patches" \
    "${OCA_BIN}" \
    "${NODE_DIR}/bin"

# ── 1. Install CA certificates ────────────────────────────────────────────────
echo "[docker-build] [1/5] Installing CA certificates..."
# Copy Ubuntu system certs
cp /etc/ssl/certs/ca-certificates.crt "${PREFIX}/etc/tls/cert.pem"
cp -a /etc/ssl/certs/. "${PREFIX}/etc/tls/certs/" 2>/dev/null || true
echo "[docker-build]   CA certificates installed"

# ── 2. Configure DNS ──────────────────────────────────────────────────────────
echo "[docker-build] [2/5] Configuring DNS..."
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' \
    > "${PREFIX}/etc/resolv.conf"
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' \
    > "${PREFIX}/glibc/etc/resolv.conf"
cat > "${PREFIX}/glibc/etc/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
echo "[docker-build]   DNS configured"

# ── 3. Install glibc runtime ──────────────────────────────────────────────────
echo "[docker-build] [3/5] Installing glibc runtime..."
# On Ubuntu arm64, glibc is already the system libc.
# Copy the dynamic linker and essential shared libs.
GLIBC_LIBS=(
    "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
    "/lib/aarch64-linux-gnu/libc.so.6"
    "/lib/aarch64-linux-gnu/libm.so.6"
    "/lib/aarch64-linux-gnu/libpthread.so.0"
    "/lib/aarch64-linux-gnu/libdl.so.2"
    "/lib/aarch64-linux-gnu/librt.so.1"
    "/lib/aarch64-linux-gnu/libresolv.so.2"
    "/lib/aarch64-linux-gnu/libnss_dns.so.2"
    "/lib/aarch64-linux-gnu/libnss_files.so.2"
    "/usr/lib/aarch64-linux-gnu/libstdc++.so.6"
    "/usr/lib/aarch64-linux-gnu/libgcc_s.so.1"
)

for lib in "${GLIBC_LIBS[@]}"; do
    if [ -f "$lib" ]; then
        cp -a "$lib" "${PREFIX}/glibc/lib/"
        echo "[docker-build]   copied $(basename "$lib")"
    elif [ -L "$lib" ]; then
        cp -aP "$lib" "${PREFIX}/glibc/lib/"
    fi
done

# Create the canonical linker path
if [ -f "${PREFIX}/glibc/lib/ld-linux-aarch64.so.1" ]; then
    chmod +x "${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
    echo "[docker-build]   glibc linker ready: ${GLIBC_LDSO}"
else
    echo "[docker-build] ERROR: glibc linker not found"
    exit 1
fi

# ── 4. Install Node.js ────────────────────────────────────────────────────────
echo "[docker-build] [4/5] Installing Node.js v${NODE_VERSION}..."
NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}.tar.xz"

echo "[docker-build]   Downloading ${NODE_URL}..."
curl -fsSL --max-time 300 -o "/tmp/${NODE_TAR}.tar.xz" "$NODE_URL"

echo "[docker-build]   Extracting..."
tar -xJf "/tmp/${NODE_TAR}.tar.xz" -C "$NODE_DIR" --strip-components=1
rm -f "/tmp/${NODE_TAR}.tar.xz"

# Move original binary → node.real
if [ -f "${NODE_DIR}/bin/node" ] && [ ! -L "${NODE_DIR}/bin/node" ]; then
    mv "${NODE_DIR}/bin/node" "${NODE_DIR}/bin/node.real"
fi

# Strip debug symbols
strip --strip-unneeded "${NODE_DIR}/bin/node.real" 2>/dev/null || true

echo "[docker-build]   Node.js installed"

# ── 5. Install OpenClaw ───────────────────────────────────────────────────────
echo "[docker-build] [5/5] Installing OpenClaw..."

# Create temporary node wrapper for npm install
cat > "${OCA_BIN}/node" <<WRAPPER
#!/system/bin/sh
unset LD_PRELOAD
export _OA_WRAPPER_PATH="${OCA_BIN}/node"
_OA_COMPAT="${OCA_DIR}/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *"\$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
    esac
fi
exec "${GLIBC_LDSO}" --library-path "${PREFIX}/glibc/lib" "${NODE_DIR}/bin/node.real" "\$@"
WRAPPER
chmod +x "${OCA_BIN}/node"

# Create npm wrapper
if [ -f "${NODE_DIR}/lib/node_modules/npm/bin/npm-cli.js" ]; then
    cat > "${OCA_BIN}/npm" <<NPMWRAP
#!/system/bin/sh
exec "${OCA_BIN}/node" "${NODE_DIR}/lib/node_modules/npm/bin/npm-cli.js" "\$@"
NPMWRAP
    chmod +x "${OCA_BIN}/npm"
fi

# Create npx wrapper
if [ -f "${NODE_DIR}/lib/node_modules/npm/bin/npx-cli.js" ]; then
    cat > "${OCA_BIN}/npx" <<NPXWRAP
#!/system/bin/sh
exec "${OCA_BIN}/node" "${NODE_DIR}/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
    chmod +x "${OCA_BIN}/npx"
fi

# Set up npm prefix to install into PREFIX
export PATH="${OCA_BIN}:${NODE_DIR}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export HOME="${HOME_DIR}"
export SSL_CERT_FILE="${PREFIX}/etc/tls/cert.pem"
export CURL_CA_BUNDLE="${PREFIX}/etc/tls/cert.pem"
export npm_config_prefix="${PREFIX}"
export npm_config_cache="${TMP_DIR}/npm-cache"

mkdir -p "${TMP_DIR}/npm-cache"

echo "[docker-build]   Running: npm install -g openclaw@latest --ignore-scripts"
"${OCA_BIN}/npm" install -g openclaw@latest --ignore-scripts 2>&1 || {
    echo "[docker-build] WARN: npm install failed, trying with --legacy-peer-deps"
    "${OCA_BIN}/npm" install -g openclaw@latest --ignore-scripts --legacy-peer-deps 2>&1
}

# Verify
OC_MJS="${PREFIX}/lib/node_modules/openclaw/openclaw.mjs"
if [ -f "$OC_MJS" ]; then
    echo "[docker-build]   OpenClaw installed: ${OC_MJS}"
else
    echo "[docker-build] ERROR: openclaw.mjs not found after install"
    exit 1
fi

# Create openclaw wrapper in PREFIX/bin
OC_BIN="${PREFIX}/bin/openclaw"
printf '#!/system/bin/sh\nexec "%s/node" "%s" "$@"\n' "${OCA_BIN}" "${OC_MJS}" > "$OC_BIN"
chmod +x "$OC_BIN"

# ── Apply placeholder substitution ───────────────────────────────────────────
echo "[docker-build] Applying placeholder substitution..."

patch_file() {
    local f="$1"
    [ -f "$f" ] || return 0
    case "$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
        7f454c46) return 0 ;;
    esac
    if grep -qF "${PREFIX}\|${HOME_DIR}\|${OCA_DIR}\|${OCA_BIN}\|${NODE_DIR}\|${GLIBC_LDSO}" "$f" 2>/dev/null; then
        sed -i \
            -e "s|${OCA_BIN}|${PH_OCA_BIN}|g" \
            -e "s|${NODE_DIR}/bin/node\.real|${PH_NODE_REAL}|g" \
            -e "s|${NODE_DIR}|${PH_NODE_DIR}|g" \
            -e "s|${GLIBC_LDSO}|${PH_GLIBC_LDSO}|g" \
            -e "s|${PREFIX}/glibc/lib|${PH_GLIBC_LIB}|g" \
            -e "s|${OCA_DIR}|${PH_OCA_DIR}|g" \
            -e "s|${PREFIX}|${PH_PREFIX}|g" \
            -e "s|${HOME_DIR}|${PH_HOME}|g" \
            "$f" 2>/dev/null || true
    fi
}

find "${STAGING}" -type f | while read -r f; do
    patch_file "$f"
done

echo "[docker-build]   Placeholders applied"

# ── Strip unnecessary files ───────────────────────────────────────────────────
echo "[docker-build] Stripping unnecessary files..."
find "${STAGING}" \( \
    -name "*.a" -o \
    -name "*.la" -o \
    -path "*/node/include/*" -o \
    -path "*/node/share/doc/*" -o \
    -path "*/node/share/man/*" -o \
    -path "*/npm/man/*" -o \
    -path "*/npm/docs/*" \
\) -exec rm -rf {} + 2>/dev/null || true

# ── Compress ──────────────────────────────────────────────────────────────────
echo "[docker-build] Compressing rootfs..."
tar -cJf "$OUTPUT" -C "$STAGING" \
    --no-same-owner \
    --no-same-permissions \
    . 2>/dev/null

SIZE=$(du -sh "$OUTPUT" | cut -f1)
echo "[docker-build] Done: ${OUTPUT} (${SIZE})"
