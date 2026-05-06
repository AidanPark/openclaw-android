#!/usr/bin/env bash
# env-init.sh — Runtime environment initializer for embedded rootfs
#
# Called by RootfsManager after tar extraction to patch all placeholder paths.
# Runs inside the app sandbox: no root, no Termux, no network.
#
# Placeholders written by build-rootfs.sh:
#   __PREFIX__      → $APP_FILES_DIR/usr
#   __HOME__        → $APP_FILES_DIR/home
#   __OCA_DIR__     → $APP_FILES_DIR/home/.openclaw-android
#   __OCA_BIN__     → $APP_FILES_DIR/home/.openclaw-android/bin
#   __NODE_DIR__    → $APP_FILES_DIR/home/.openclaw-android/node
#   __NODE_REAL__   → $APP_FILES_DIR/home/.openclaw-android/node/bin/node.real
#   __GLIBC_LDSO__  → $APP_FILES_DIR/usr/glibc/lib/ld-linux-aarch64.so.1
#   __GLIBC_LIB__   → $APP_FILES_DIR/usr/glibc/lib
#   com.termux      → $APP_PACKAGE
set -euo pipefail

# ── Variables inyectadas por RootfsManager ────────────────────────────────────
APP_FILES_DIR="${APP_FILES_DIR:?APP_FILES_DIR not set}"
APP_PACKAGE="${APP_PACKAGE:?APP_PACKAGE not set}"

PREFIX="$APP_FILES_DIR/usr"
HOME_DIR="$APP_FILES_DIR/home"
OCA_DIR="$HOME_DIR/.openclaw-android"
OCA_BIN="$OCA_DIR/bin"
NODE_DIR="$OCA_DIR/node"
NODE_REAL="$NODE_DIR/bin/node.real"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
GLIBC_LIB="$PREFIX/glibc/lib"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

echo "=== env-init.sh: patching rootfs paths ==="
echo "  PREFIX   = $PREFIX"
echo "  HOME     = $HOME_DIR"
echo "  PACKAGE  = $APP_PACKAGE"
echo ""

# ── Crear directorios necesarios ──────────────────────────────────────────────
mkdir -p "$HOME_DIR" "$APP_FILES_DIR/tmp" "$OCA_DIR" "$OCA_BIN"
mkdir -p "$OCA_DIR/patches"
mkdir -p "$PREFIX/etc" "$PREFIX/glibc/etc"

# ── Función de reemplazo de placeholders ──────────────────────────────────────
patch_file() {
    local file="$1"
    [ -f "$file" ] || return 0
    # Saltar binarios ELF
    local magic
    magic=$(head -c 4 "$file" 2>/dev/null | od -An -tx1 | tr -d ' \n' | head -c 8)
    [ "$magic" = "7f454c46" ] && return 0

    local tmp="${file}.patching"
    if sed \
        -e "s|__PREFIX__|$PREFIX|g" \
        -e "s|__HOME__|$HOME_DIR|g" \
        -e "s|__OCA_DIR__|$OCA_DIR|g" \
        -e "s|__OCA_BIN__|$OCA_BIN|g" \
        -e "s|__NODE_DIR__|$NODE_DIR|g" \
        -e "s|__NODE_REAL__|$NODE_REAL|g" \
        -e "s|__GLIBC_LDSO__|$GLIBC_LDSO|g" \
        -e "s|__GLIBC_LIB__|$GLIBC_LIB|g" \
        -e "s|/data/data/com\.termux/files/usr|$PREFIX|g" \
        -e "s|com\.termux|$APP_PACKAGE|g" \
        "$file" > "$tmp" 2>/dev/null; then
        mv "$tmp" "$file"
    else
        rm -f "$tmp"
    fi
}

# ── 1. Parchear wrappers node/npm/npx ─────────────────────────────────────────
echo "[1/8] Patching node/npm/npx wrappers..."
for wrapper in "$OCA_BIN/node" "$OCA_BIN/npm" "$OCA_BIN/npx"; do
    if [ -f "$wrapper" ]; then
        patch_file "$wrapper"
        chmod +x "$wrapper"
        ok "$wrapper"
    else
        warn "$wrapper not found (will be created by Kotlin fallback)"
    fi
done

# ── 2. Parchear shebangs en PREFIX/bin/ ───────────────────────────────────────
echo "[2/8] Patching shebangs in $PREFIX/bin/..."
if [ -d "$PREFIX/bin" ]; then
    count=0
    for f in "$PREFIX/bin"/*; do
        [ -f "$f" ] || continue
        # Saltar ELF
        magic=$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n' | head -c 8)
        [ "$magic" = "7f454c46" ] && continue
        # Solo parchear si contiene placeholders o rutas de com.termux
        if grep -qE '__PREFIX__|__HOME__|__OCA_DIR__|__OCA_BIN__|__NODE_DIR__|__NODE_REAL__|__GLIBC_LDSO__|__GLIBC_LIB__|com\.termux' "$f" 2>/dev/null; then
            patch_file "$f"
            count=$((count + 1))
        fi
    done
    ok "Patched $count scripts in bin/"
fi

# ── 3. Parchear wrapper openclaw ──────────────────────────────────────────────
echo "[3/8] Patching openclaw wrapper..."
OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
OC_BIN="$PREFIX/bin/openclaw"
if [ -f "$OC_MJS" ]; then
    [ -L "$OC_BIN" ] && rm -f "$OC_BIN"
    printf '#!/system/bin/sh\nexec "%s" "%s" "$@"\n' "$OCA_BIN/node" "$OC_MJS" > "$OC_BIN"
    chmod +x "$OC_BIN"
    ok "openclaw wrapper → $OC_BIN"
else
    warn "openclaw.mjs not found — skipping wrapper"
fi

# ── 4. Parchear shebangs en CLIs globales de npm ──────────────────────────────
echo "[4/8] Patching npm global CLI shebangs..."
count=0
for js_file in "$PREFIX/lib/node_modules"/*/bin/*.js \
               "$PREFIX/lib/node_modules"/@*/*/bin/*.js; do
    [ -f "$js_file" ] || continue
    first_line=$(head -1 "$js_file" 2>/dev/null)
    case "$first_line" in
        "#!/usr/bin/env node")
            # Reemplazar con wrapper glibc
            tmp="${js_file}.patching"
            { printf '#!/system/bin/sh\nexec "%s"\n' "$OCA_BIN/node"; tail -n +2 "$js_file"; } > "$tmp" \
                && mv "$tmp" "$js_file" || rm -f "$tmp"
            count=$((count + 1))
            ;;
        *"__PREFIX__"*|*"__OCA_BIN__"*|*"com.termux"*)
            patch_file "$js_file"
            count=$((count + 1))
            ;;
    esac
done
ok "Patched $count npm CLI entry points"

# ── 5. DNS — resolv.conf ──────────────────────────────────────────────────────
echo "[5/8] Configuring DNS..."
for resolv in "$PREFIX/etc/resolv.conf" "$PREFIX/glibc/etc/resolv.conf"; do
    if [ ! -s "$resolv" ]; then
        printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > "$resolv"
        ok "resolv.conf → $resolv"
    fi
done

# /etc/hosts para glibc
GLIBC_HOSTS="$PREFIX/glibc/etc/hosts"
if [ ! -f "$GLIBC_HOSTS" ]; then
    printf '127.0.0.1 localhost localhost.localdomain\n::1 localhost\n' > "$GLIBC_HOSTS"
    ok "glibc/etc/hosts created"
fi

# ── 6. Certificados SSL ───────────────────────────────────────────────────────
echo "[6/8] Activating SSL certificates..."
CERT_DIR="$PREFIX/etc/tls/certs"
CERT_BUNDLE="$PREFIX/etc/tls/cert.pem"
mkdir -p "$PREFIX/etc/tls"
if [ -d "$CERT_DIR" ] && [ ! -f "$CERT_BUNDLE" ]; then
    cat "$CERT_DIR"/*.pem > "$CERT_BUNDLE" 2>/dev/null && ok "cert.pem bundle created" || warn "No .pem files found in $CERT_DIR"
elif [ -f "$CERT_BUNDLE" ]; then
    ok "cert.pem already exists"
else
    warn "No cert directory found — SSL may not work"
fi

# ── 7. Crear openclaw-start.sh ────────────────────────────────────────────────
echo "[7/8] Creating openclaw-start.sh..."
WRAPPER="$HOME_DIR/openclaw-start.sh"
cat > "$WRAPPER" << WRAPEOF
#!/system/bin/sh
export HOME="$HOME_DIR"
export PREFIX="$PREFIX"
export TMPDIR="$APP_FILES_DIR/tmp"
export PATH="$OCA_BIN:$NODE_DIR/bin:$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export OA_GLIBC=1
export CONTAINER=1
exec "$OCA_BIN/node" "$PREFIX/lib/node_modules/openclaw/openclaw.mjs" gateway --host 0.0.0.0
WRAPEOF
chmod +x "$WRAPPER"
ok "openclaw-start.sh → $WRAPPER"

# ── 8. Marcadores de instalación ──────────────────────────────────────────────
echo "[8/8] Writing installation markers..."
printf '{"installed":true,"source":"rootfs-prebuilt","initialized":true}\n' \
    > "$OCA_DIR/installed.json"
touch "$OCA_DIR/.post-setup-done"
ok "Markers written"

echo ""
echo "=== env-init.sh completed successfully ==="
