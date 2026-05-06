#!/usr/bin/env bash
# OpenClaw Android — Post-Bootstrap Setup
# Runs in the terminal after Termux bootstrap extraction.
# Installs: git, glibc, Node.js, OpenClaw
#
# Strategy:
#   - Termux .deb packages: dpkg-deb -x + relocate (bypasses dpkg hardcoded paths)
#   - Pacman .pkg.tar.xz packages: tar -xJf + relocate (bypasses pacman entirely)
#   - Both have files under data/data/com.termux/files/usr/ which we relocate to $PREFIX
#
# Why not apt-get/dpkg/pacman?
#   All three have hardcoded /data/data/com.termux/... paths that libtermux-exec
#   cannot rewrite (it only intercepts execve, not open/opendir).

set -eo pipefail

# ─── Paths ────────────────────────────────────
: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"
: "${TMPDIR:=$(dirname "$PREFIX")/tmp}"

OCA_DIR="$HOME/.openclaw-android"
NODE_DIR="$OCA_DIR/node"
BIN_DIR="$OCA_DIR/bin"
NODE_VERSION="22.22.0"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
MARKER="$OCA_DIR/.post-setup-done"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ─── GitHub mirror fallback (for China/restricted networks) ──
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
REPO_BASE_MIRRORS=(
    "https://ghfast.top/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://ghproxy.net/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://mirror.ghproxy.com/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
)
REPO_BASE="$REPO_BASE_ORIGIN"
resolve_repo_base() {
    if curl -sI --connect-timeout 3 "$REPO_BASE_ORIGIN/oa.sh" >/dev/null 2>&1; then
        REPO_BASE="$REPO_BASE_ORIGIN"; return 0
    fi
    for mirror in "${REPO_BASE_MIRRORS[@]}"; do
        if curl -sI --connect-timeout 3 "$mirror/oa.sh" >/dev/null 2>&1; then
            echo -e "  ${YELLOW}[MIRROR]${NC} Using mirror: ${mirror%%/oa.sh*}"
            REPO_BASE="$mirror"; return 0
        fi
    done
    return 1
}

# Kept in sync with scripts/lib.sh resolve_npm_registry()
NPM_REGISTRY_ORIGIN="https://registry.npmjs.org/"
NPM_REGISTRY_MIRROR="https://registry.npmmirror.com/"
resolve_npm_registry() {
    local choice
    local cache_file="$OCA_DIR/.npm-registry"
    local reachable=0
    if curl -sI --connect-timeout 5 "$NPM_REGISTRY_ORIGIN" >/dev/null 2>&1; then
        choice="$NPM_REGISTRY_ORIGIN"
        reachable=1
    elif curl -sI --connect-timeout 5 "$NPM_REGISTRY_MIRROR" >/dev/null 2>&1; then
        echo -e "  ${YELLOW}[MIRROR]${NC} Using npm mirror: ${NPM_REGISTRY_MIRROR}"
        choice="$NPM_REGISTRY_MIRROR"
        reachable=1
    else
        choice="$NPM_REGISTRY_ORIGIN"
    fi
    mkdir -p "$(dirname "$cache_file")"
    printf '%s' "$choice" > "$cache_file.tmp" && mv "$cache_file.tmp" "$cache_file"
    export NPM_CONFIG_REGISTRY="$choice"
    if [ "$reachable" -eq 1 ]; then
        return 0
    fi
    return 1
}

# SSL cert for curl (bootstrap curl looks at hardcoded com.termux path)
# Bootstrap from Android system certs FIRST so curl can reach packages-cf.termux.dev
# without "Certificate verification failed" even before ca-certificates is installed.
_CERT_BUNDLE="$PREFIX/etc/tls/cert.pem"
if [ ! -s "$_CERT_BUNDLE" ] || ! grep -q "BEGIN CERTIFICATE" "$_CERT_BUNDLE" 2>/dev/null; then
    mkdir -p "$PREFIX/etc/tls"
    if [ -d "/system/etc/security/cacerts" ]; then
        cat /system/etc/security/cacerts/*.0 > "$_CERT_BUNDLE" 2>/dev/null || true
    fi
fi
unset _CERT_BUNDLE
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"

# Git system config has hardcoded com.termux path — skip it
export GIT_CONFIG_NOSYSTEM=1

# Git exec path (git looks for helpers like git-remote-https here)
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"

# Git template dir (hardcoded /data/data/com.termux path workaround)
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"

# ─── Repair bin/ shebangs ────────────────────────────────────────────
# The Termux bootstrap has scripts in $PREFIX/bin/ (pkg, apt, termux-*)
# with shebangs hardcoded to /data/data/com.termux/... which breaks when
# the app package name differs (e.g. com.openclaw.android.debug).
# libtermux-exec only intercepts execve(), not open(), so we must patch
# the shebang lines directly. This is idempotent and safe to run always.
_APP_PKG="$(basename "$(dirname "$(dirname "$PREFIX")")")"
if [ -n "$_APP_PKG" ] && [ "$_APP_PKG" != "com.termux" ]; then
    for _f in "$PREFIX/bin"/*; do
        [ -f "$_f" ] || continue
        # Only patch text files (skip ELF binaries)
        _head=$(head -c 4 "$_f" 2>/dev/null | od -An -tx1 | tr -d ' \n')
        [ "$_head" = "7f454c46" ] && continue
        # Only patch if shebang contains com.termux
        head -1 "$_f" 2>/dev/null | grep -q "com\.termux" || continue
        sed -i "s|com\.termux|$_APP_PKG|g" "$_f" 2>/dev/null || true
    done
fi

if [ -f "$MARKER" ]; then
    echo -e "${GREEN}Post-setup already completed.${NC}"
    exit 0
fi

echo ""
echo "══════════════════════════════════════════════"
echo "  OpenClaw Android — Installing components"
echo "══════════════════════════════════════════════"
echo ""

mkdir -p "$OCA_DIR" "$OCA_DIR/patches" "$TMPDIR"

TERMUX_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main"
PACMAN_PKG_REPO="https://service.termux-pacman.dev/gpkg/aarch64"
# Mirrors for termux-pacman packages (used if primary fails DNS resolution)
PACMAN_PKG_MIRRORS=(
    "https://service.termux-pacman.dev/gpkg/aarch64"
    "https://packages.termux.dev/pacman/glibc-aarch64"
    "https://mirror.termux-pacman.dev/gpkg/aarch64"
)
TERMUX_INNER="data/data/com.termux/files/usr"
DEB_DIR="$TMPDIR/debs"
PKG_DIR="$TMPDIR/pkgs"
EXTRACT_DIR="$TMPDIR/pkg-extract"

# ─── Helper: install_deb ──────────────────────
# Prioritizes local payload debs for 100% offline install.
install_deb() {
    local filename="$1"
    local name
    name=$(basename "$filename" | sed 's/_[0-9].*//')
    local deb_file="${DEB_DIR}/$(basename "$filename")"
    
    # 1. Try local payload first (Offline-First)
    local payload_deb="$PAYLOAD_DIR/debs/$(basename "$filename")"
    if [ -f "$payload_deb" ]; then
        deb_file="$payload_deb"
        echo "    (payload) $name"
    elif [ -f "$deb_file" ]; then
        echo "    (cached) $name"
    else
        echo "    downloading $name..."
        local url="${TERMUX_DEB_REPO}/${filename}"
        curl -fsSL --max-time 120 -o "$deb_file" "$url" || return 1
    fi

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    dpkg-deb -x "$deb_file" "$EXTRACT_DIR" 2>/dev/null

    # Relocate: data/data/com.termux/files/usr/* → $PREFIX/
    if [ -d "$EXTRACT_DIR/$TERMUX_INNER" ]; then
        cp -a "$EXTRACT_DIR/$TERMUX_INNER/"* "$PREFIX/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── Helper: install_pacman_pkg ───────────────
# Downloads a .pkg.tar.xz from pacman repo and extracts into target dir.
# Tries multiple mirrors if the primary fails DNS resolution.
install_pacman_pkg() {
    local filename="$1"
    local target="$2"  # e.g., $PREFIX/glibc
    local name
    name=${filename%%-[0-9]*}
    local pkg_file="${PKG_DIR}/${filename}"

    if [ -f "$pkg_file" ]; then
        echo "    (cached) $name"
    else
        # 1. Try local payload first
        local payload_pkg="$PAYLOAD_DIR/pkgs/$(basename "$filename")"
        if [ -f "$payload_pkg" ]; then
            pkg_file="$payload_pkg"
            echo "    (payload) $name"
        else
            echo "    downloading $name..."
            local downloaded=false
            for mirror in "${PACMAN_PKG_MIRRORS[@]}"; do
                local url="${mirror}/${filename}"
                if curl -fsSL --connect-timeout 10 --max-time 300 -o "$pkg_file" "$url" 2>/dev/null; then
                    # Verify the file is a valid xz archive (not an error page)
                    if file "$pkg_file" 2>/dev/null | grep -q "XZ\|xz\|tar"; then
                        downloaded=true
                        break
                    elif tar -tJf "$pkg_file" >/dev/null 2>&1; then
                        downloaded=true
                        break
                    else
                        rm -f "$pkg_file"
                    fi
                else
                    rm -f "$pkg_file"
                fi
                echo "    [mirror failed] $mirror"
            done
            if [ "$downloaded" = "false" ]; then
                echo -e "  ${RED}✗${NC} Failed to download $name from all mirrors"
                return 1
            fi
        fi
    fi

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    tar -xJf "$pkg_file" -C "$EXTRACT_DIR" 2>/dev/null

    # Pacman packages also extract under data/data/com.termux/files/usr/...
    local inner="$EXTRACT_DIR/$TERMUX_INNER"
    if [ -d "$inner/glibc" ]; then
        # glibc packages go under $PREFIX/glibc/
        cp -a "$inner/glibc/"* "$target/" 2>/dev/null || true
    elif [ -d "$inner" ]; then
        cp -a "$inner/"* "$target/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── [1/7] Install essential packages ─────────
echo -e "▸ ${YELLOW}[1/7]${NC} Installing essential packages..."
mkdir -p "$DEB_DIR" "$PKG_DIR"

# Download Packages index to resolve .deb filenames (SKIP IF OFFLINE)
if [ ! -f "$OCA_DIR/.glibc-arch" ]; then
    echo "  Fetching package index..."
    PACKAGES_FILE="$TMPDIR/Packages"
    curl -fsSL --max-time 60 \
        "${TERMUX_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" \
        -o "$PACKAGES_FILE"
else
    echo "  Offline mode: skipping package index fetch."
fi

# Resolve package filename from Packages index
get_deb_filename() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Filename:/ { print $2; exit }
    ' "$PACKAGES_FILE"
}

# Packages to install via dpkg-deb (dependency order, only those missing from bootstrap)
DEB_PACKAGES=(
    ca-certificates   # SSL certs — must be first so apt/curl can verify mirrors
    libexpat          # git dep
    pcre2             # git dep
    git               # for npm/openclaw
)

TOTAL=${#DEB_PACKAGES[@]}
COUNT=0
for pkg in "${DEB_PACKAGES[@]}"; do
    COUNT=$((COUNT + 1))
    filename=$(get_deb_filename "$pkg")
    if [ -z "$filename" ]; then
        echo -e "  ${RED}✗${NC} Package '$pkg' not found in index"
        continue
    fi
    echo "  [$COUNT/$TOTAL] $pkg"
    install_deb "$filename"
done

# Make sure newly extracted binaries are executable
chmod +x "$PREFIX/bin/"* 2>/dev/null || true

# ─── Activate ca-certificates ────────────────────────────────────────
# ca-certificates extracts certs to $PREFIX/etc/tls/certs/ but curl and
# apt look for the bundle at $PREFIX/etc/tls/cert.pem (already set via
# CURL_CA_BUNDLE). Regenerate the bundle from the extracted certs so
# all subsequent HTTPS requests (apt, curl, npm) can verify SSL.
if [ -d "$PREFIX/etc/tls/certs" ]; then
    # Concatenate all PEM certs into the bundle curl/apt already use
    cat "$PREFIX/etc/tls/certs/"*.pem > "$PREFIX/etc/tls/cert.pem" 2>/dev/null || true
    echo -e "  ${GREEN}✓${NC} ca-certificates activated"
fi

# Verify git
if [ -f "$PREFIX/bin/git" ]; then
    echo -e "  ${GREEN}✓${NC} git $(git --version 2>/dev/null | head -1)"
else
    echo -e "  ${RED}✗${NC} git not found after extraction"
    exit 1
fi

# ─── [2/7] glibc runtime ─────────────────────
echo -e "▸ ${YELLOW}[2/7]${NC} Installing glibc runtime..."

if [ -x "$GLIBC_LDSO" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} glibc already installed"
else
    mkdir -p "$PREFIX/glibc"

    # Strategy A: pacman .pkg.tar.xz from termux-pacman service (with mirrors)
    _glibc_via_pacman() {
        echo "  Downloading glibc (~10MB)..."
        install_pacman_pkg "glibc-2.42-0-aarch64.pkg.tar.xz" "$PREFIX/glibc" || return 1
        echo "  Downloading gcc-libs (~24MB)..."
        install_pacman_pkg "gcc-libs-glibc-14.2.1-1-aarch64.pkg.tar.xz" "$PREFIX/glibc" || return 1
        return 0
    }

    # Strategy B: .deb packages from Termux apt repo (glibc-repo)
    # These are the same packages compiled in debian format, hosted on packages-cf.termux.dev
    _glibc_via_apt() {
        echo "  [fallback] Trying glibc via Termux apt repo..."
        # First install glibc-repo to get access to glibc packages
        local glibc_repo_file
        glibc_repo_file=$(get_deb_filename "glibc-repo" 2>/dev/null || true)
        if [ -n "$glibc_repo_file" ]; then
            install_deb "$glibc_repo_file" || true
        fi

        # Fetch glibc packages index from the glibc repo
        local GLIBC_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main-glibc"
        local GLIBC_PACKAGES_FILE="$TMPDIR/Packages-glibc"
        if curl -fsSL --connect-timeout 10 --max-time 60 \
            "${GLIBC_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" \
            -o "$GLIBC_PACKAGES_FILE" 2>/dev/null; then

            get_glibc_deb_filename() {
                local pkg="$1"
                awk -v pkg="$pkg" '
                    /^Package: / { found = ($2 == pkg) }
                    found && /^Filename:/ { print $2; exit }
                ' "$GLIBC_PACKAGES_FILE"
            }

            local _orig_repo="$TERMUX_DEB_REPO"
            TERMUX_DEB_REPO="$GLIBC_DEB_REPO"

            for pkg in glibc gcc-libs-glibc; do
                local fn
                fn=$(get_glibc_deb_filename "$pkg" 2>/dev/null || true)
                if [ -n "$fn" ]; then
                    echo "  [apt] Installing $pkg..."
                    install_deb "$fn" || true
                fi
            done
            TERMUX_DEB_REPO="$_orig_repo"

            # glibc .deb extracts to $PREFIX directly (not $PREFIX/glibc)
            # Check if linker ended up in $PREFIX/lib instead of $PREFIX/glibc/lib
            if [ -f "$PREFIX/lib/ld-linux-aarch64.so.1" ] && [ ! -f "$GLIBC_LDSO" ]; then
                mkdir -p "$PREFIX/glibc/lib"
                cp -a "$PREFIX/lib/ld-linux-aarch64.so.1" "$PREFIX/glibc/lib/" 2>/dev/null || true
                # Copy all glibc-related libs
                for _lib in "$PREFIX/lib"/libstdc++* "$PREFIX/lib"/libgcc_s* \
                            "$PREFIX/lib"/libc.so* "$PREFIX/lib"/libm.so* \
                            "$PREFIX/lib"/libpthread* "$PREFIX/lib"/libdl*; do
                    [ -f "$_lib" ] || [ -L "$_lib" ] || continue
                    cp -a "$_lib" "$PREFIX/glibc/lib/" 2>/dev/null || true
                done
            fi
            return 0
        fi
        return 1
    }

    if ! _glibc_via_pacman; then
        echo -e "  ${YELLOW}[WARN]${NC} pacman mirrors failed, trying apt fallback..."
        _glibc_via_apt || true
    fi

    # Verify linker
    if [ ! -f "$GLIBC_LDSO" ]; then
        echo -e "  ${RED}✗${NC} glibc linker not found at $GLIBC_LDSO"
        echo "  Tried: pacman mirrors + apt fallback"
        exit 1
    fi
    chmod +x "$GLIBC_LDSO"
    mkdir -p "$OCA_DIR"
    touch "$OCA_DIR/.glibc-arch"
    echo -e "  ${GREEN}✓${NC} glibc installed"
fi

# Install supplementary glibc libraries (libcap etc.)
_GLIBC_LIBS_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches/glibc-libs"
if [ -d "$PREFIX/glibc/lib" ] && [ -d "$_GLIBC_LIBS_SRC" ]; then
    for _lib in "$_GLIBC_LIBS_SRC"/*.so.*; do
        [ -f "$_lib" ] || continue
        _fn=$(basename "$_lib")
        if [ ! -f "$PREFIX/glibc/lib/$_fn" ]; then
            cp "$_lib" "$PREFIX/glibc/lib/$_fn"
            _sn=$(echo "$_fn" | sed -E 's/^(lib[^.]+\.so\.[0-9]+)\..*/\1/')
            [ "$_sn" != "$_fn" ] && ln -sf "$_fn" "$PREFIX/glibc/lib/$_sn"
            echo -e "  ${GREEN}✓${NC} Installed $_sn"
        fi
    done
fi

# Ensure glibc /etc/hosts exists (localhost resolution)
if [ -d "$PREFIX/glibc/etc" ] && [ ! -f "$PREFIX/glibc/etc/hosts" ]; then
    cat > "$PREFIX/glibc/etc/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo -e "  ${GREEN}✓${NC} Created glibc /etc/hosts"
fi
echo -e "  Linker: $GLIBC_LDSO"

# ─── [3/7] Node.js ──────────────────────────
echo -e "▸ ${YELLOW}[3/7]${NC} Installing Node.js v${NODE_VERSION}..."
mkdir -p "$NODE_DIR/bin"

_NODE_CMD=""
if [ -x "$BIN_DIR/node" ]; then _NODE_CMD="$BIN_DIR/node"
elif [ -f "$NODE_DIR/bin/node.real" ] && [ -x "$NODE_DIR/bin/node" ]; then _NODE_CMD="$NODE_DIR/bin/node"
fi
if [ -n "$_NODE_CMD" ] && "$_NODE_CMD" --version &>/dev/null; then
    INSTALLED_VER=$("$_NODE_CMD" --version 2>/dev/null || echo "")
    echo -e "  ${GREEN}[SKIP]${NC} Node.js already installed ($INSTALLED_VER)"
    # Repair wrappers in BIN_DIR (safe from npm overwrites)
    mkdir -p "$BIN_DIR"
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm" << NPMWRAP
#!$PREFIX/bin/bash
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    if [ -f "\$_oc_mjs" ]; then
        [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
        printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
        chmod +x "\$_oc_bin"
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        chmod +x "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx"
    fi
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi
else
    NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
    echo "  Downloading Node.js v${NODE_VERSION} (~25MB)..."
    curl -fSL --max-time 300 \
        "https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}.tar.xz" \
        -o "$TMPDIR/${NODE_TAR}.tar.xz"

    echo "  Extracting..."
    tar -xJf "$TMPDIR/${NODE_TAR}.tar.xz" -C "$NODE_DIR" --strip-components=1

    # Move original binary → node.real
    if [ -f "$NODE_DIR/bin/node" ] && [ ! -L "$NODE_DIR/bin/node" ]; then
        mv "$NODE_DIR/bin/node" "$NODE_DIR/bin/node.real"
    fi

    rm -f "$TMPDIR/${NODE_TAR}.tar.xz"

    # Create grun-style node wrapper in BIN_DIR (safe from npm overwrites)
    mkdir -p "$BIN_DIR"
    cat > "$BIN_DIR/node" << WRAPPER
#!${PREFIX}/bin/bash
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD
export _OA_WRAPPER_PATH="$BIN_DIR/node"
_OA_COMPAT="\$HOME/.openclaw-android/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *"\$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
    esac
fi
_LEADING_OPTS=""
_COUNT=0
for _arg in "\$@"; do
    case "\$_arg" in --*) _COUNT=\$((_COUNT + 1)) ;; *) break ;; esac
done
if [ \$_COUNT -gt 0 ] && [ \$_COUNT -lt \$# ]; then
    while [ \$# -gt 0 ]; do
        case "\$1" in
            --*) _LEADING_OPTS="\${_LEADING_OPTS:+\$_LEADING_OPTS }\$1"; shift ;;
            *) break ;;
        esac
    done
    export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }\$_LEADING_OPTS"
fi
exec "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_DIR/bin/node.real" "\$@"
WRAPPER
    chmod +x "$BIN_DIR/node"

    # Create npm/npx wrappers in BIN_DIR
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm" << NPMWRAP
#!$PREFIX/bin/bash
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    if [ -f "\$_oc_mjs" ]; then
        [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
        printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
        chmod +x "\$_oc_bin"
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        chmod +x "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx"
    fi
    # corepack: shebang patch only
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi

    # Configure npm
    export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"
    "$BIN_DIR/npm" config set script-shell "$PREFIX/bin/sh" 2>/dev/null || true

    # Verify
    NODE_VER=$("$BIN_DIR/node" --version 2>/dev/null) || {
        echo -e "  ${RED}✗${NC} Node.js verification failed"
        exit 1
    }
    echo -e "  ${GREEN}✓${NC} Node.js $NODE_VER (glibc)"
fi

# ─── [4/7] OpenClaw ─────────────────────────
echo -e "▸ ${YELLOW}[4/7]${NC} Installing OpenClaw..."
export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"

# Auto-detect GitHub mirror for restricted networks
resolve_repo_base

# Auto-detect npm registry (session-scoped via NPM_CONFIG_REGISTRY env var).
# Does NOT write to ~/.npmrc — see CHANGELOG v1.0.24.
resolve_npm_registry || true

# Force git to use HTTPS instead of SSH (no SSH client available).
# Preserve any existing user .gitconfig (name, email, aliases); only set our keys.
touch "$HOME/.gitconfig"
git config --global http.sslCAInfo "$PREFIX/etc/tls/cert.pem"
git config --global --unset-all url."https://github.com/".insteadOf 2>/dev/null || true
git config --global --add url."https://github.com/".insteadOf "ssh://git@github.com/"
git config --global --add url."https://github.com/".insteadOf "git@github.com:"

# Git wrapper: replace $PREFIX/bin/git with a wrapper that:
#   1. Strips --recurse-submodules (triggers open() on hardcoded com.termux path)
#   2. Cleans existing target dirs before clone (npm's withTempDir creates dir first)
# npm caches git path at module load via which.sync('git'), so we must replace the binary.
# $PREFIX/bin/git is a symlink -> ../libexec/git-core/git (the real ELF binary).
REAL_GIT="$PREFIX/libexec/git-core/git"
if [ -f "$REAL_GIT" ] && [ ! -f "$PREFIX/bin/git.wrapper-installed" ]; then
    echo "  Installing git wrapper (strips --recurse-submodules)..."
    rm -f "$PREFIX/bin/git"
    # Write shebang with absolute path (no LD_PRELOAD = no /bin/bash rewrite)
    echo "#!${PREFIX}/bin/bash" > "$PREFIX/bin/git"
    cat >> "$PREFIX/bin/git" << 'ENDWRAP'
filtered=()
is_clone=false
for a in "$@"; do
  case "$a" in
    --recurse-submodules) ;;
    clone) is_clone=true; filtered+=("$a") ;;
    *) filtered+=("$a") ;;
  esac
done
if $is_clone; then
  for a in "${filtered[@]}"; do
    case "$a" in
      clone|--*|-*|http*|ssh*|git*|[0-9]) ;;
      *) [ -d "$a" ] && rm -rf "$a" ;;
    esac
  done
fi
ENDWRAP
    echo "exec \"$REAL_GIT\" \"\${filtered[@]}\"" >> "$PREFIX/bin/git"
    chmod +x "$PREFIX/bin/git"
    touch "$PREFIX/bin/git.wrapper-installed"
    echo -e "  ${GREEN}\u2713${NC} git wrapper installed"
else
    if [ -f "$PREFIX/bin/git.wrapper-installed" ]; then
        echo -e "  ${GREEN}[SKIP]${NC} git wrapper already installed"
    else
        echo -e "  ${RED}\u2717${NC} Real git not found at $REAL_GIT"
        exit 1
    fi
fi

# ── Pre-install network verification ──
echo "  Verifying network connectivity..."
_NPM_REGISTRY="${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}"
if ! curl -fsSL --connect-timeout 10 "$_NPM_REGISTRY" >/dev/null 2>&1; then
    echo -e "    ${YELLOW}[WARN]${NC} Primary registry unreachable, trying mirrors..."
    # Try npmmirror as fallback
    if curl -fsSL --connect-timeout 10 "https://registry.npmmirror.com/" >/dev/null 2>&1; then
        export NPM_CONFIG_REGISTRY="https://registry.npmmirror.com/"
        echo -e "    ${GREEN}[OK]${NC}   Using npmmirror registry"
    else
        echo -e "    ${RED}[FAIL]${NC} No registry reachable. Check network/DNS."
        # Continue anyway - let npm handle the error
    fi
fi
unset _NPM_REGISTRY

# ── Install OpenClaw with retry logic ──
if command -v openclaw &>/dev/null 2>&1; then
    OC_VER=$(openclaw --version 2>/dev/null || echo "unknown")
    echo -e "  ${GREEN}[SKIP]${NC} OpenClaw already installed ($OC_VER)"
else
    echo "  Installing OpenClaw (with retry)..."
    _NPM_RETRIES=3
    _NPM_RETRY_DELAY=5
    _NPM_SUCCESS=false
    
    for _try in $(seq 1 $_NPM_RETRIES); do
        echo "    Attempt $_try/$_NPM_RETRIES..."
        # Clean npm cache tmp dir before each attempt
        rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
        if npm install -g openclaw@latest --ignore-scripts 2>&1; then
            _NPM_SUCCESS=true
            break
        else
            if [ "$_try" -lt "$_NPM_RETRIES" ]; then
                echo -e "    ${YELLOW}[WARN]${NC} Install failed, retrying in ${_NPM_RETRY_DELAY}s..."
                sleep $_NPM_RETRY_DELAY
                _NPM_RETRY_DELAY=$((_NPM_RETRY_DELAY * 2))
            fi
        fi
    done
    
    if [ "$_NPM_SUCCESS" != "true" ]; then
        echo -e "  ${RED}[FAIL]${NC} OpenClaw installation failed after $_NPM_RETRIES attempts"
        echo "  Diagnostic info:"
        echo "    - Node: $($BIN_DIR/node --version 2>&1 || echo 'NOT FOUND')"
        echo "    - NPM: $($BIN_DIR/npm --version 2>&1 || echo 'NOT FOUND')"
        echo "    - Registry: ${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}"
        echo "    - SSL: $([ -s "$PREFIX/etc/tls/cert.pem" ] && echo 'OK' || echo 'MISSING')"
        # Try one more time with verbose output to capture error
        echo ""
        echo "  Last attempt output:"
        npm install -g openclaw@latest --ignore-scripts 2>&1 | tail -10 || true
    else
        OC_VER=$(openclaw --version 2>/dev/null || echo "installed")
        echo -e "  ${GREEN}✓${NC} OpenClaw $OC_VER"
    fi
fi

# ─── Repair openclaw wrapper ─────────────────
# The external installer (myopenclawhub.com/install) creates $PREFIX/bin/openclaw
# with #!/usr/bin/env node which doesn't exist in this environment.
# Always rewrite it to use our glibc-wrapped node from BIN_DIR.
_OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
_OC_BIN="$PREFIX/bin/openclaw"
if [ -f "$_OC_MJS" ]; then
    [ -L "$_OC_BIN" ] && rm -f "$_OC_BIN"
    printf '#!%s/bin/bash\nexec "%s/node" "%s" "$@"\n' "$PREFIX" "$BIN_DIR" "$_OC_MJS" > "$_OC_BIN"
    chmod +x "$_OC_BIN"
    echo -e "  ${GREEN}✓${NC} openclaw wrapper repaired → $BIN_DIR/node"
fi

# Restore optional/channel deps that --ignore-scripts skips.
# Uses npm_config_ignore_scripts=true so sharp's native build doesn't block.
OPENCLAW_DIR="$(npm root -g)/openclaw"
if [ -d "$OPENCLAW_DIR" ]; then
    echo "  Restoring optional dependencies..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

# Install clawdhub (skill manager)
echo "  Installing clawdhub..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "  ${GREEN}✓${NC} clawdhub installed"
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "  Installing undici dependency for clawdhub..."
        (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit) || true
    fi
else
    echo -e "  ${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
fi

# PyYAML (for .skill packaging)
command -v python &>/dev/null && { python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true; }

# Run openclaw update (builds native modules like sharp)
echo "  Running: openclaw update (this may take 5-10 minutes)..."
openclaw update || true

# ─── [5/7] Patches ──────────────────────────
echo -e "▸ ${YELLOW}[5/7]${NC} Applying patches..."

# Copy glibc-compat.js from project (bundled alongside this script)
COMPAT_SRC="$(dirname "$0")/glibc-compat.js"
if [ -f "$COMPAT_SRC" ]; then
    cp "$COMPAT_SRC" "$OCA_DIR/patches/glibc-compat.js"
else
    # Fallback: download from repo
    curl -fsSL "$REPO_BASE/patches/glibc-compat.js" \
        -o "$OCA_DIR/patches/glibc-compat.js" 2>/dev/null || true
fi

# systemctl stub
printf '#!%s/bin/bash\nexit 0\n' "$PREFIX" > "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"

# sharp WASM fallback (prebuilt native binaries don't load on Android)
if [ -d "$OPENCLAW_DIR/node_modules/sharp" ]; then
    if ! node -e "require('$OPENCLAW_DIR/node_modules/sharp')" 2>/dev/null; then
        echo "  Installing sharp WebAssembly runtime..."
        (cd "$OPENCLAW_DIR" && npm install @img/sharp-wasm32 --force --no-audit --no-fund 2>&1 | tail -3) || true
    fi
fi

echo -e "  ${GREEN}✓${NC} Patches applied"

# ─── [6/7] Environment ──────────────────────
echo -e "▸ ${YELLOW}[6/7]${NC} Configuring environment..."

cat > "$HOME/.bashrc" << BASHRC
# OpenClaw Android environment
export PREFIX="$PREFIX"
export HOME="$HOME"
export TMPDIR="$TMPDIR"
export PATH="$BIN_DIR:$NODE_DIR/bin:\$PREFIX/bin:\$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
export TERMUX__PREFIX="$PREFIX"
export TERMUX_PREFIX="$PREFIX"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export OA_GLIBC=1
export CONTAINER=1
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM=1
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
# npm registry (auto-detected by OpenClaw Android, safe to override manually)
[ -z "\${NPM_CONFIG_REGISTRY:-}" ] && [ -s "\$HOME/.openclaw-android/.npm-registry" ] && \\
    export NPM_CONFIG_REGISTRY="\$(cat "\$HOME/.openclaw-android/.npm-registry")"

# ── Auto-run post-setup if not yet completed ──────────────────────────────────
# Runs automatically on first terminal open after bootstrap.
# Skipped once ~/.openclaw-android/.post-setup-done exists.
_OCA_SETUP_MARKER="\$HOME/.openclaw-android/.post-setup-done"
_OCA_SETUP_SCRIPT="\$HOME/.openclaw-android/post-setup.sh"
if [ ! -f "\$_OCA_SETUP_MARKER" ] && [ -f "\$_OCA_SETUP_SCRIPT" ]; then
    echo ""
    echo "  OpenClaw setup not yet complete — running post-setup.sh..."
    echo ""
    bash "\$_OCA_SETUP_SCRIPT"
fi
unset _OCA_SETUP_MARKER _OCA_SETUP_SCRIPT
BASHRC

echo -e "  ${GREEN}✓${NC} ~/.bashrc configured"

# oa CLI (enables oa --update, oa --backup, etc.)
if curl -fsSL "$REPO_BASE/oa.sh" \
        -o "$PREFIX/bin/oa" 2>/dev/null; then
    chmod +x "$PREFIX/bin/oa"
    echo -e "  ${GREEN}✓${NC} oa CLI installed"
else
    echo -e "  ${YELLOW}[WARN]${NC} oa CLI installation failed (non-critical)"
fi

# ─── [7/7] Optional Tools ──────────────────
TOOL_CONF="$OCA_DIR/tool-selections.conf"
if [ -f "$TOOL_CONF" ]; then
    # shellcheck source=/dev/null
    source "$TOOL_CONF"

    HAS_TOOLS=false
    for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_CODE_SERVER INSTALL_PLAYWRIGHT INSTALL_CLAUDE_CODE INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
        [ "${!var}" = "true" ] && HAS_TOOLS=true && break
    done

    if $HAS_TOOLS; then
        echo -e "▸ ${YELLOW}[7/7]${NC} Installing optional tools..."

        # Helper: install .deb with direct dependencies
        install_with_deps() {
            local pkg="$1"
            local deps
            deps=$(awk -v pkg="$pkg" '
                /^Package: / { found = ($2 == pkg) }
                found && /^Depends:/ {
                    gsub(/^Depends: /, "")
                    gsub(/ *\([^)]*\)/, "")
                    gsub(/, /, "\n")
                    print; exit
                }
            ' "$PACKAGES_FILE")
            while IFS= read -r dep; do
                dep=$(echo "$dep" | tr -d ' ')
                [ -z "$dep" ] && continue
                local dep_file
                dep_file=$(get_deb_filename "$dep")
                if [ -n "$dep_file" ]; then install_deb "$dep_file" 2>/dev/null || true; fi
            done <<< "$deps"
            local filename
            filename=$(get_deb_filename "$pkg")
            [ -n "$filename" ] && install_deb "$filename"
        }

        # Termux packages
        [ "${INSTALL_TMUX:-false}" = "true" ] && {
            echo "  Installing tmux..."
            install_with_deps tmux
            echo -e "  ${GREEN}✓${NC} tmux"
        }
        [ "${INSTALL_TTYD:-false}" = "true" ] && {
            echo "  Installing ttyd..."
            install_with_deps ttyd
            echo -e "  ${GREEN}✓${NC} ttyd"
        }
        [ "${INSTALL_DUFS:-false}" = "true" ] && {
            echo "  Installing dufs..."
            install_with_deps dufs
            echo -e "  ${GREEN}✓${NC} dufs"
        }

        # npm packages
        [ "${INSTALL_CODE_SERVER:-false}" = "true" ] && {
            echo "  Installing code-server (this may take a while)..."
            npm install -g code-server 2>&1 || true
            echo -e "  ${GREEN}✓${NC} code-server"
        }
        [ "${INSTALL_PLAYWRIGHT:-false}" = "true" ] && {
            echo "  Installing Playwright (playwright-core)..."
            npm install -g playwright-core 2>&1 || true
            # Set Playwright environment variables if Chromium is available
            CHROMIUM_BIN=""
            for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
                [ -x "$bin" ] && CHROMIUM_BIN="$bin" && break
            done
            if [ -n "$CHROMIUM_BIN" ]; then
                PW_MARKER_START="# >>> Playwright >>>"
                PW_MARKER_END="# <<< Playwright <<<"
                if ! grep -qF "$PW_MARKER_START" "$HOME/.bashrc"; then
                    cat >> "$HOME/.bashrc" << PWENV

${PW_MARKER_START}
export PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH="$CHROMIUM_BIN"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
${PW_MARKER_END}
PWENV
                fi
                echo -e "  ${GREEN}✓${NC} Playwright (env: PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$CHROMIUM_BIN)"
            else
                echo -e "  ${GREEN}✓${NC} Playwright (install Chromium later via 'oa --install' for full setup)"
            fi
        }
        [ "${INSTALL_CLAUDE_CODE:-false}" = "true" ] && {
            echo "  Installing Claude Code..."
            npm install -g @anthropic-ai/claude-code 2>&1 || true
            echo -e "  ${GREEN}✓${NC} Claude Code"
        }
        [ "${INSTALL_GEMINI_CLI:-false}" = "true" ] && {
            echo "  Installing Gemini CLI..."
            npm install -g @google/gemini-cli 2>&1 || true
            echo -e "  ${GREEN}✓${NC} Gemini CLI"
        }
        [ "${INSTALL_CODEX_CLI:-false}" = "true" ] && {
            echo "  Installing Codex CLI (Termux)..."
            npm install -g @mmmbuto/codex-cli-termux 2>&1 || true
            # Create codex CLI wrapper (DioNanos fork launcher fix)
            _codex_bin="$PREFIX/bin/codex"
            _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
            if [ -f "$_codex_pkg/codex.bin" ]; then
                [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
                printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
                    "$PREFIX" "$_codex_pkg" > "$_codex_bin"
                chmod +x "$_codex_bin"
            fi
            echo -e "  ${GREEN}✓${NC} Codex CLI (Termux)"
        }

        # Fix shebangs in npm global CLIs (kept in sync with scripts/lib.sh fix_npm_global_shebangs())
        for _js in "$PREFIX/lib/node_modules"/*/bin/*.js \
                   "$PREFIX/lib/node_modules"/@*/*/bin/*.js; do
            [ -f "$_js" ] || continue
            head -1 "$_js" | grep -q '^#!/usr/bin/env node$' || continue
            sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$_js"
        done
    else
        echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
    fi
else
    echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
fi

# ─── Cleanup ────────────────────────────────
rm -rf "$DEB_DIR" "$PKG_DIR" "$PACKAGES_FILE" "$TMPDIR/gpkg.db" 2>/dev/null || true

# ─── Done ────────────────────────────────────
touch "$MARKER"

echo ""
echo "══════════════════════════════════════════════"
echo -e "  ${GREEN}✓ Installation complete!${NC}"
echo "══════════════════════════════════════════════"
echo ""
echo "  Loading environment..."
source "$HOME/.bashrc"
echo ""
echo "  Starting OpenClaw onboard..."
echo ""
openclaw onboard
