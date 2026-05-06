#!/usr/bin/env bash
# install-infra-deps.sh — Install core infrastructure packages (L1)
# Always runs regardless of platform selection.
#
# Strategy:
#   1. Fix SSL certs + mirror FIRST (so pkg/apt can actually work)
#   2. Try pkg install (fast path)
#   3. If pkg fails, fall back to direct .deb download from packages-cf.termux.dev
#      (bypasses broken mirrors entirely — no apt/dpkg needed)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

TERMUX_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main"
TERMUX_INNER="data/data/com.termux/files/usr"

echo "=== Installing Infrastructure Dependencies ==="
echo ""

# ── Step 0: Fix SSL certificates BEFORE any network call ─────────────────────
# Without certs, pkg update fails with "Certificate verification failed".
# Priority: 1) bundled cacert.pem from repo, 2) Android system certs, 3) certs dir
_fix_ssl_certs() {
    local cert_bundle="$PREFIX/etc/tls/cert.pem"
    local cert_dir="$PREFIX/etc/tls/certs"

    # Already have a valid bundle?
    if [ -s "$cert_bundle" ] && grep -q "BEGIN CERTIFICATE" "$cert_bundle" 2>/dev/null; then
        echo -e "  ${GREEN}[OK]${NC}   SSL certs already present"
        return 0
    fi

    mkdir -p "$PREFIX/etc/tls"

    # Source 1: Bundled cacert.pem from the repo (Mozilla bundle — most reliable)
    local repo_cert="$SCRIPT_DIR/cacert.pem"
    if [ -f "$repo_cert" ] && grep -q "BEGIN CERTIFICATE" "$repo_cert" 2>/dev/null; then
        cp "$repo_cert" "$cert_bundle"
        local n
        n=$(grep -c "BEGIN CERTIFICATE" "$cert_bundle" 2>/dev/null || echo "?")
        echo -e "  ${GREEN}[OK]${NC}   SSL certs from bundled cacert.pem ($n certs)"
        export CURL_CA_BUNDLE="$cert_bundle"
        export SSL_CERT_FILE="$cert_bundle"
        return 0
    fi

    # Source 2: Android system certs (always available on Android, no internet needed)
    if [ -d "/system/etc/security/cacerts" ]; then
        cat /system/etc/security/cacerts/*.0 > "$cert_bundle" 2>/dev/null || true
        if [ -s "$cert_bundle" ]; then
            local n
            n=$(grep -c "BEGIN CERTIFICATE" "$cert_bundle" 2>/dev/null || echo "?")
            echo -e "  ${GREEN}[OK]${NC}   SSL certs from Android system ($n certs)"
            export CURL_CA_BUNDLE="$cert_bundle"
            export SSL_CERT_FILE="$cert_bundle"
            return 0
        fi
    fi

    # Source 3: Existing certs dir (from previous partial install)
    if [ -d "$cert_dir" ] && ls "$cert_dir"/*.pem >/dev/null 2>&1; then
        cat "$cert_dir"/*.pem > "$cert_bundle" 2>/dev/null || true
        if [ -s "$cert_bundle" ]; then
            echo -e "  ${GREEN}[OK]${NC}   SSL certs from $cert_dir"
            export CURL_CA_BUNDLE="$cert_bundle"
            export SSL_CERT_FILE="$cert_bundle"
            return 0
        fi
    fi

    echo -e "  ${YELLOW}[WARN]${NC} No SSL certs found — pkg may fail, will use direct .deb fallback"
    return 1
}

# ── Step 1: Fix mirror if it's broken ────────────────────────────────────────
_fix_mirror() {
    # Test if current mirror works
    if pkg update -y 2>&1 | grep -q "Certificate verification failed\|No system certificates\|Failed to fetch"; then
        echo -e "  ${YELLOW}[WARN]${NC} Mirror broken — switching to packages.termux.dev"
        # Write a working sources.list directly
        mkdir -p "$PREFIX/etc/apt/sources.list.d"
        echo "deb https://packages-cf.termux.dev/apt/termux-main stable main" \
            > "$PREFIX/etc/apt/sources.list"
        # Remove broken mirror entries
        rm -f "$PREFIX/etc/apt/sources.list.d"/*.list 2>/dev/null || true
        echo "deb https://packages-cf.termux.dev/apt/termux-main stable main" \
            > "$PREFIX/etc/apt/sources.list.d/termux-main.list"
        return 0
    fi
    return 0
}

# ── Step 2: Direct .deb install (no apt/pkg needed) ──────────────────────────
# Downloads .deb from packages-cf.termux.dev and extracts directly into $PREFIX.
# This bypasses dpkg's hardcoded /data/data/com.termux paths entirely.
_install_deb_direct() {
    local pkg="$1"
    local packages_index="$TMPDIR/Packages-infra"

    # Fetch package index if not cached
    if [ ! -f "$packages_index" ]; then
        echo "  Fetching package index from packages-cf.termux.dev..."
        if ! curl -fsSL --max-time 60 \
            "${TERMUX_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" \
            -o "$packages_index" 2>/dev/null; then
            echo -e "  ${RED}[FAIL]${NC} Cannot reach packages-cf.termux.dev"
            return 1
        fi
    fi

    # Resolve filename from index
    local filename
    filename=$(awk -v p="$pkg" '
        /^Package: / { found = ($2 == p) }
        found && /^Filename:/ { print $2; exit }
    ' "$packages_index")

    if [ -z "$filename" ]; then
        echo -e "  ${RED}[FAIL]${NC} Package '$pkg' not found in index"
        return 1
    fi

    local deb_file="$TMPDIR/$(basename "$filename")"
    if [ ! -f "$deb_file" ]; then
        echo "    downloading $pkg..."
        curl -fsSL --max-time 120 \
            "${TERMUX_DEB_REPO}/${filename}" \
            -o "$deb_file" || { echo -e "  ${RED}[FAIL]${NC} Download failed: $pkg"; return 1; }
    else
        echo "    (cached) $pkg"
    fi

    local extract_dir="$TMPDIR/deb-extract-$$"
    rm -rf "$extract_dir"
    mkdir -p "$extract_dir"
    dpkg-deb -x "$deb_file" "$extract_dir" 2>/dev/null || {
        # dpkg-deb may not be available yet — use ar + tar
        (cd "$extract_dir" && ar x "$deb_file" 2>/dev/null && \
            tar xf data.tar.* 2>/dev/null) || true
    }

    # Relocate: data/data/com.termux/files/usr/* → $PREFIX/
    if [ -d "$extract_dir/$TERMUX_INNER" ]; then
        cp -a "$extract_dir/$TERMUX_INNER/"* "$PREFIX/" 2>/dev/null || true
        echo -e "  ${GREEN}[OK]${NC}   $pkg installed (direct)"
    else
        echo -e "  ${YELLOW}[WARN]${NC} $pkg: unexpected archive layout"
    fi
    rm -rf "$extract_dir"
}

# ── Main install logic ────────────────────────────────────────────────────────

# Always fix SSL first
_fix_ssl_certs || true
export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$PREFIX/etc/tls/cert.pem}"
export SSL_CERT_FILE="${SSL_CERT_FILE:-$PREFIX/etc/tls/cert.pem}"

mkdir -p "$TMPDIR"

# Try pkg (fast path)
echo "Trying pkg install..."
PKG_OK=true
if ! pkg update -y 2>&1; then
    echo -e "  ${YELLOW}[WARN]${NC} pkg update failed — switching to direct .deb install"
    PKG_OK=false
fi

if $PKG_OK; then
    if pkg install -y git 2>&1; then
        echo -e "${GREEN}[OK]${NC}   git installed via pkg"
    else
        echo -e "  ${YELLOW}[WARN]${NC} pkg install git failed — trying direct .deb"
        PKG_OK=false
    fi
fi

# Fallback: direct .deb download (no apt/pkg/mirror needed)
if ! $PKG_OK || ! command -v git &>/dev/null; then
    echo "Installing via direct .deb download (bypasses broken mirrors)..."
    for pkg in ca-certificates libexpat pcre2 git; do
        if [ "$pkg" = "git" ] && command -v git &>/dev/null; then
            echo -e "  ${GREEN}[SKIP]${NC} git already available"
            continue
        fi
        _install_deb_direct "$pkg" || true
    done
    chmod +x "$PREFIX/bin/"* 2>/dev/null || true

    # Activate ca-certificates if just installed
    if [ -d "$PREFIX/etc/tls/certs" ]; then
        cat "$PREFIX/etc/tls/certs/"*.pem > "$PREFIX/etc/tls/cert.pem" 2>/dev/null || true
        echo -e "  ${GREEN}[OK]${NC}   ca-certificates activated"
    fi
fi

# Final check
if ! command -v git &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} git could not be installed"
    echo "  Try manually: termux-change-repo → choose packages.termux.dev"
    exit 1
fi

echo ""
echo -e "${GREEN}Infrastructure dependencies installed.${NC}"
