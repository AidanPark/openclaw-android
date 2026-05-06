#!/bin/sh
# env-init.sh — OpenClaw rootfs initializer
# Runs ONCE after rootfs extraction to patch placeholder paths.
# Called by RootfsManager.initializeEnvironment() via runEnvInit().
#
# Inputs (env vars set by RootfsManager.runEnvInit):
#   APP_FILES_DIR  — context.filesDir  (e.g. /data/data/com.openclaw.android/files)
#   APP_PACKAGE    — context.packageName
#   HOME           — filesDir/home
#   PREFIX         — filesDir/usr
#   TMPDIR         — filesDir/tmp
#
# Placeholders used in the pre-built rootfs:
#   __PREFIX__     → $PREFIX
#   __HOME__       → $HOME
#   __OCA_DIR__    → $HOME/.openclaw-android
#   __OCA_BIN__    → $HOME/.openclaw-android/bin
#   __NODE_DIR__   → $HOME/.openclaw-android/node
#   __NODE_REAL__  → $HOME/.openclaw-android/node/bin/node.real
#   __GLIBC_LDSO__ → $PREFIX/glibc/lib/ld-linux-aarch64.so.1
#   __GLIBC_LIB__  → $PREFIX/glibc/lib
#   com.termux     → $APP_PACKAGE

set -e

# ── Validate required env vars ────────────────────────────────────────────────
: "${APP_FILES_DIR:?APP_FILES_DIR not set}"
: "${APP_PACKAGE:?APP_PACKAGE not set}"
: "${HOME:?HOME not set}"
: "${PREFIX:?PREFIX not set}"
: "${TMPDIR:=${APP_FILES_DIR}/tmp}"

OCA_DIR="${HOME}/.openclaw-android"
OCA_BIN="${OCA_DIR}/bin"
NODE_DIR="${OCA_DIR}/node"
NODE_REAL="${NODE_DIR}/bin/node.real"
GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
GLIBC_LIB="${PREFIX}/glibc/lib"

echo "[env-init] Starting initialization"
echo "[env-init]   PREFIX      = ${PREFIX}"
echo "[env-init]   HOME        = ${HOME}"
echo "[env-init]   APP_PACKAGE = ${APP_PACKAGE}"

# ── Create required directories ───────────────────────────────────────────────
mkdir -p "${HOME}" "${TMPDIR}" "${OCA_DIR}" "${OCA_BIN}"
mkdir -p "${OCA_DIR}/patches" "${NODE_DIR}/bin"

# ── Helper: patch_file ────────────────────────────────────────────────────────
# Replaces all placeholders in a text file in-place.
# Skips ELF binaries (first 4 bytes = 7f 45 4c 46).
patch_file() {
    local f="$1"
    [ -f "$f" ] || return 0
    # Skip ELF binaries
    case "$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
        7f454c46) return 0 ;;
    esac
    # Only patch if file contains a placeholder
    if grep -qE '__PREFIX__|__HOME__|__OCA_DIR__|__OCA_BIN__|__NODE_DIR__|__NODE_REAL__|__GLIBC_LDSO__|__GLIBC_LIB__|com\.termux' "$f" 2>/dev/null; then
        sed -i \
            -e "s|__PREFIX__|${PREFIX}|g" \
            -e "s|__HOME__|${HOME}|g" \
            -e "s|__OCA_DIR__|${OCA_DIR}|g" \
            -e "s|__OCA_BIN__|${OCA_BIN}|g" \
            -e "s|__NODE_DIR__|${NODE_DIR}|g" \
            -e "s|__NODE_REAL__|${NODE_REAL}|g" \
            -e "s|__GLIBC_LDSO__|${GLIBC_LDSO}|g" \
            -e "s|__GLIBC_LIB__|${GLIBC_LIB}|g" \
            -e "s|/data/data/com\.termux/files/usr|${PREFIX}|g" \
            -e "s|/data/data/com\.termux/files/home|${HOME}|g" \
            -e "s|com\.termux|${APP_PACKAGE}|g" \
            "$f" 2>/dev/null || true
    fi
}

# ── 1. Patch node/npm/npx wrappers in OCA_BIN ────────────────────────────────
echo "[env-init] Patching node/npm/npx wrappers..."
for name in node npm npx; do
    wrapper="${OCA_BIN}/${name}"
    if [ -f "$wrapper" ]; then
        patch_file "$wrapper"
        chmod +x "$wrapper"
        echo "[env-init]   patched ${name}"
    fi
done

# ── 2. Patch shebangs in PREFIX/bin/ scripts ──────────────────────────────────
echo "[env-init] Patching PREFIX/bin scripts..."
if [ -d "${PREFIX}/bin" ]; then
    for f in "${PREFIX}/bin"/*; do
        [ -f "$f" ] || continue
        patch_file "$f"
    done
fi

# ── 3. Patch openclaw wrapper ─────────────────────────────────────────────────
echo "[env-init] Patching openclaw wrapper..."
OC_MJS="${PREFIX}/lib/node_modules/openclaw/openclaw.mjs"
OC_BIN="${PREFIX}/bin/openclaw"
if [ -f "$OC_MJS" ]; then
    # Remove symlink if present, write fresh wrapper
    [ -L "$OC_BIN" ] && rm -f "$OC_BIN"
    printf '#!/system/bin/sh\nexec "%s/node" "%s" "$@"\n' "${OCA_BIN}" "${OC_MJS}" > "$OC_BIN"
    chmod +x "$OC_BIN"
    echo "[env-init]   openclaw wrapper written: $OC_BIN"

    # IMPORTANT: Also create wrapper in OCA_BIN (first in PATH) to intercept
    # any ELF openclaw binary in payload/bin/ before the kernel tries to run it.
    # Without this, Android fails with "invalid ELF header" because it lacks
    # /lib/ld-linux-aarch64.so.1.
    OCA_OC_BIN="${OCA_BIN}/openclaw"
    printf '#!/system/bin/sh\n# OpenClaw glibc-wrapped launcher — auto-generated\nunset LD_PRELOAD\nexec "%s/node" "%s" "$@"\n' "${OCA_BIN}" "${OC_MJS}" > "$OCA_OC_BIN"
    chmod +x "$OCA_OC_BIN"
    echo "[env-init]   openclaw wrapper in OCA_BIN written: $OCA_OC_BIN"
fi

# ── 4. Patch npm global CLI entry points ──────────────────────────────────────
echo "[env-init] Patching npm global CLI entry points..."
NM_DIR="${PREFIX}/lib/node_modules"
if [ -d "$NM_DIR" ]; then
    find "$NM_DIR" -name "*.js" -path "*/bin/*" 2>/dev/null | while read -r jsfile; do
        first_line=$(head -1 "$jsfile" 2>/dev/null || true)
        case "$first_line" in
            *__PREFIX__*|*__OCA_BIN__*|*com.termux*)
                patch_file "$jsfile"
                ;;
            '#!/usr/bin/env node')
                # Rewrite to use our glibc-wrapped node
                sed -i "1s|#!/usr/bin/env node|#!${OCA_BIN}/node|" "$jsfile" 2>/dev/null || true
                ;;
        esac
    done
fi

# ── 5. DNS — resolv.conf ──────────────────────────────────────────────────────
echo "[env-init] Configuring DNS..."
for resolv_path in \
    "${PREFIX}/etc/resolv.conf" \
    "${PREFIX}/glibc/etc/resolv.conf"; do
    mkdir -p "$(dirname "$resolv_path")"
    if [ ! -s "$resolv_path" ] || ! grep -q "nameserver" "$resolv_path" 2>/dev/null; then
        printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > "$resolv_path"
        echo "[env-init]   wrote ${resolv_path}"
    fi
done

# ── 6. glibc /etc/hosts ───────────────────────────────────────────────────────
GLIBC_HOSTS="${PREFIX}/glibc/etc/hosts"
if [ ! -f "$GLIBC_HOSTS" ]; then
    mkdir -p "${PREFIX}/glibc/etc"
    cat > "$GLIBC_HOSTS" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo "[env-init]   created glibc /etc/hosts"
fi

# ── 7. SSL certificate bundle ─────────────────────────────────────────────────
echo "[env-init] Activating SSL certificates..."
CERT_DIR="${PREFIX}/etc/tls/certs"
CERT_BUNDLE="${PREFIX}/etc/tls/cert.pem"
mkdir -p "${PREFIX}/etc/tls"

if [ -d "$CERT_DIR" ] && [ ! -s "$CERT_BUNDLE" ]; then
    cat "${CERT_DIR}"/*.pem > "$CERT_BUNDLE" 2>/dev/null && \
        echo "[env-init]   cert.pem bundle built from ${CERT_DIR}" || \
        echo "[env-init]   WARN: no .pem files found in ${CERT_DIR}"
fi

# Fallback: use Android system certs if bundle still missing
if [ ! -s "$CERT_BUNDLE" ] && [ -d "/system/etc/security/cacerts" ]; then
    cat /system/etc/security/cacerts/*.0 > "$CERT_BUNDLE" 2>/dev/null && \
        echo "[env-init]   cert.pem built from Android system certs" || true
fi

# ── 8. Create openclaw-start.sh ───────────────────────────────────────────────
echo "[env-init] Writing openclaw-start.sh..."
START_SCRIPT="${HOME}/openclaw-start.sh"
cat > "$START_SCRIPT" <<STARTSCRIPT
#!/system/bin/sh
# OpenClaw gateway launcher — auto-generated by env-init.sh
# DO NOT EDIT: regenerated on each APK update

export HOME="${HOME}"
export PREFIX="${PREFIX}"
export TMPDIR="${TMPDIR}"
export PATH="${OCA_BIN}:${NODE_DIR}/bin:${PREFIX}/bin:${PREFIX}/bin/applets:/system/bin:/bin"
export LD_LIBRARY_PATH="${PREFIX}/lib"
export SSL_CERT_FILE="${PREFIX}/etc/tls/cert.pem"
export CURL_CA_BUNDLE="${PREFIX}/etc/tls/cert.pem"
export GIT_SSL_CAINFO="${PREFIX}/etc/tls/cert.pem"
export RESOLV_CONF="${PREFIX}/etc/resolv.conf"
export GIT_CONFIG_NOSYSTEM=1
export GIT_EXEC_PATH="${PREFIX}/libexec/git-core"
export GIT_TEMPLATE_DIR="${PREFIX}/share/git-core/templates"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export ANDROID_DATA=/data
export ANDROID_ROOT=/system
export OA_GLIBC=1
export CONTAINER=1
export CLAWDHUB_WORKDIR="${HOME}/.openclaw/workspace"

# Unset LD_PRELOAD: prevents bionic libtermux-exec.so from loading into glibc node
unset LD_PRELOAD

exec "${OCA_BIN}/node" "${PREFIX}/lib/node_modules/openclaw/openclaw.mjs" gateway --host 0.0.0.0
STARTSCRIPT
chmod +x "$START_SCRIPT"
echo "[env-init]   openclaw-start.sh written"

# ── 9. Installation markers ───────────────────────────────────────────────────
echo "[env-init] Writing installation markers..."
mkdir -p "$OCA_DIR"
printf '{"installed":true,"source":"rootfs-prebuilt","initialized":true}\n' \
    > "${OCA_DIR}/installed.json"
touch "${OCA_DIR}/.post-setup-done"
echo "[env-init]   markers written"

echo "[env-init] Initialization complete"
