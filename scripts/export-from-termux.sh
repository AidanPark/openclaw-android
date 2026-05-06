#!/usr/bin/env bash
# export-from-termux.sh — FASE 2: Export OpenClaw runtime from Termux
#
# Run this script INSIDE Termux (on the Android device or via adb shell).
# It exports a minimal, self-contained runtime tarball that can be bundled
# as assets/rootfs-aarch64.tar.xz in the APK.
#
# Usage (inside Termux):
#   bash export-from-termux.sh [output_dir]
#
# Output:
#   openclaw-runtime.tar.gz  — full runtime (for manual inspection)
#   rootfs-aarch64.tar.xz    — APK-ready asset (xz compressed, placeholder paths)
#
# Requirements (must be installed in Termux first):
#   pkg install glibc-runner nodejs openclaw git ca-certificates
#
# Strategy:
#   1. Copy Termux prefix + home dirs to a staging area
#   2. Strip unnecessary files (docs, headers, static libs, test suites)
#   3. Replace hardcoded /data/data/com.termux/... paths with placeholders
#   4. Compress with xz (best ratio for APK assets)

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
OCA_DIR="${TERMUX_HOME}/.openclaw-android"
OUTPUT_DIR="${1:-${TERMUX_HOME}/openclaw-export}"
STAGING_DIR="${OUTPUT_DIR}/staging"
ROOTFS_DIR="${STAGING_DIR}/rootfs"

# Placeholder tokens (replaced by env-init.sh at runtime)
PLACEHOLDER_PREFIX="__PREFIX__"
PLACEHOLDER_HOME="__HOME__"
PLACEHOLDER_OCA_DIR="__OCA_DIR__"
PLACEHOLDER_OCA_BIN="__OCA_BIN__"
PLACEHOLDER_NODE_DIR="__NODE_DIR__"
PLACEHOLDER_NODE_REAL="__NODE_REAL__"
PLACEHOLDER_GLIBC_LDSO="__GLIBC_LDSO__"
PLACEHOLDER_GLIBC_LIB="__GLIBC_LIB__"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[export]${NC} $*"; }
ok()   { echo -e "${GREEN}[ok]${NC} $*"; }
warn() { echo -e "${YELLOW}[warn]${NC} $*"; }
fail() { echo -e "${RED}[fail]${NC} $*"; exit 1; }

# ── Validate prerequisites ────────────────────────────────────────────────────
log "Validating prerequisites..."

[ -d "$TERMUX_PREFIX" ] || fail "Termux prefix not found: $TERMUX_PREFIX"
[ -d "$TERMUX_HOME" ]   || fail "Termux home not found: $TERMUX_HOME"
[ -d "$OCA_DIR" ]       || fail "OpenClaw dir not found: $OCA_DIR (run post-setup.sh first)"

GLIBC_LDSO="${TERMUX_PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
[ -x "$GLIBC_LDSO" ] || fail "glibc linker not found: $GLIBC_LDSO"

NODE_REAL="${OCA_DIR}/node/bin/node.real"
[ -x "$NODE_REAL" ] || fail "Node.js not found: $NODE_REAL"

OC_MJS="${TERMUX_PREFIX}/lib/node_modules/openclaw/openclaw.mjs"
[ -f "$OC_MJS" ] || fail "OpenClaw not found: $OC_MJS"

ok "All prerequisites found"

# ── Create staging area ───────────────────────────────────────────────────────
log "Creating staging area at ${STAGING_DIR}..."
rm -rf "$STAGING_DIR"
mkdir -p "${ROOTFS_DIR}/usr" "${ROOTFS_DIR}/home" "${ROOTFS_DIR}/tmp"

# ── FASE 2.1: Copy Termux prefix (usr/) ──────────────────────────────────────
log "[1/5] Copying Termux prefix..."

# Essential directories to include
INCLUDE_DIRS=(
    bin
    lib
    libexec
    etc
    share/ca-certificates
    share/git-core
    glibc
)

# Directories to explicitly exclude (saves ~200MB)
EXCLUDE_PATTERNS=(
    "share/doc"
    "share/man"
    "share/info"
    "share/locale"
    "share/termux-keyring"
    "include"
    "lib/pkgconfig"
    "lib/cmake"
    "var/cache"
    "var/log"
    "tmp"
)

# Build rsync exclude args
RSYNC_EXCLUDES=()
for pat in "${EXCLUDE_PATTERNS[@]}"; do
    RSYNC_EXCLUDES+=("--exclude=${pat}")
done

# Copy with rsync if available, otherwise cp
if command -v rsync >/dev/null 2>&1; then
    rsync -a --no-owner --no-group \
        "${RSYNC_EXCLUDES[@]}" \
        "${TERMUX_PREFIX}/" "${ROOTFS_DIR}/usr/"
else
    cp -a "${TERMUX_PREFIX}/." "${ROOTFS_DIR}/usr/"
    # Manual cleanup
    for pat in "${EXCLUDE_PATTERNS[@]}"; do
        rm -rf "${ROOTFS_DIR}/usr/${pat}" 2>/dev/null || true
    done
fi

ok "Termux prefix copied"

# ── FASE 2.2: Copy OpenClaw home dir ─────────────────────────────────────────
log "[2/5] Copying OpenClaw home dir..."

OCA_STAGING="${ROOTFS_DIR}/home/.openclaw-android"
mkdir -p "$OCA_STAGING"

# Copy node runtime
if [ -d "${OCA_DIR}/node" ]; then
    cp -a "${OCA_DIR}/node" "${OCA_STAGING}/"
    ok "Node.js runtime copied"
fi

# Copy bin wrappers (node, npm, npx)
if [ -d "${OCA_DIR}/bin" ]; then
    cp -a "${OCA_DIR}/bin" "${OCA_STAGING}/"
    ok "bin wrappers copied"
fi

# Copy patches (glibc-compat.js etc.)
if [ -d "${OCA_DIR}/patches" ]; then
    cp -a "${OCA_DIR}/patches" "${OCA_STAGING}/"
    ok "patches copied"
fi

# Copy .openclaw config if present
if [ -d "${TERMUX_HOME}/.openclaw" ]; then
    cp -a "${TERMUX_HOME}/.openclaw" "${ROOTFS_DIR}/home/"
    ok ".openclaw config copied"
fi

# ── FASE 2.3: Strip unnecessary files ────────────────────────────────────────
log "[3/5] Stripping unnecessary files..."

STRIP_PATTERNS=(
    "*.a"                          # static libraries
    "*.la"                         # libtool archives
    "node/include"                 # node headers
    "node/share/doc"               # node docs
    "node/share/man"               # node man pages
    "node/lib/node_modules/npm/man"
    "node/lib/node_modules/npm/docs"
    "node/lib/node_modules/npm/node_modules/.cache"
)

for pat in "${STRIP_PATTERNS[@]}"; do
    find "${ROOTFS_DIR}" -name "$pat" -exec rm -rf {} + 2>/dev/null || true
done

# Strip debug symbols from ELF binaries (saves ~30%)
if command -v strip >/dev/null 2>&1; then
    log "  Stripping debug symbols..."
    find "${ROOTFS_DIR}/usr/lib" -name "*.so*" -type f 2>/dev/null | while read -r lib; do
        strip --strip-unneeded "$lib" 2>/dev/null || true
    done
    find "${ROOTFS_DIR}/home/.openclaw-android/node/bin" -type f 2>/dev/null | while read -r bin; do
        strip --strip-unneeded "$bin" 2>/dev/null || true
    done
fi

ok "Stripping complete"

# ── FASE 2.4: Replace hardcoded paths with placeholders ──────────────────────
log "[4/5] Replacing hardcoded paths with placeholders..."

OCA_BIN_REAL="${OCA_DIR}/bin"
NODE_DIR_REAL="${OCA_DIR}/node"
GLIBC_LIB_REAL="${TERMUX_PREFIX}/glibc/lib"

# Function to patch a text file
patch_placeholders() {
    local f="$1"
    [ -f "$f" ] || return 0
    # Skip ELF binaries
    case "$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
        7f454c46) return 0 ;;
    esac
    # Only process if file contains a real path
    if grep -qF "${TERMUX_PREFIX}\|${TERMUX_HOME}\|com.termux" "$f" 2>/dev/null; then
        sed -i \
            -e "s|${OCA_BIN_REAL}|${PLACEHOLDER_OCA_BIN}|g" \
            -e "s|${NODE_DIR_REAL}/bin/node\.real|${PLACEHOLDER_NODE_REAL}|g" \
            -e "s|${NODE_DIR_REAL}|${PLACEHOLDER_NODE_DIR}|g" \
            -e "s|${GLIBC_LDSO}|${PLACEHOLDER_GLIBC_LDSO}|g" \
            -e "s|${GLIBC_LIB_REAL}|${PLACEHOLDER_GLIBC_LIB}|g" \
            -e "s|${OCA_DIR}|${PLACEHOLDER_OCA_DIR}|g" \
            -e "s|${TERMUX_PREFIX}|${PLACEHOLDER_PREFIX}|g" \
            -e "s|${TERMUX_HOME}|${PLACEHOLDER_HOME}|g" \
            -e "s|/data/data/com\.termux/files/usr|${PLACEHOLDER_PREFIX}|g" \
            -e "s|/data/data/com\.termux/files/home|${PLACEHOLDER_HOME}|g" \
            -e "s|com\.termux|com.PLACEHOLDER_PKG|g" \
            "$f" 2>/dev/null || true
    fi
}

# Patch all text files in staging
find "${ROOTFS_DIR}" -type f | while read -r f; do
    patch_placeholders "$f"
done

ok "Path placeholders applied"

# ── FASE 2.5: Compress ────────────────────────────────────────────────────────
log "[5/5] Compressing rootfs..."

mkdir -p "$OUTPUT_DIR"

# Full tarball (for inspection)
TARBALL="${OUTPUT_DIR}/openclaw-runtime.tar.gz"
tar -czf "$TARBALL" -C "$ROOTFS_DIR" . 2>/dev/null
TARBALL_SIZE=$(du -sh "$TARBALL" | cut -f1)
ok "Full tarball: ${TARBALL} (${TARBALL_SIZE})"

# APK-ready asset (xz, best compression)
ASSET="${OUTPUT_DIR}/rootfs-aarch64.tar.xz"
tar -cJf "$ASSET" -C "$ROOTFS_DIR" . 2>/dev/null
ASSET_SIZE=$(du -sh "$ASSET" | cut -f1)
ok "APK asset: ${ASSET} (${ASSET_SIZE})"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════"
echo "  Export complete!"
echo "════════════════════════════════════════════════"
echo ""
echo "  APK asset:  ${ASSET}"
echo "  Size:       ${ASSET_SIZE}"
echo ""
echo "  Next steps:"
echo "  1. Copy rootfs-aarch64.tar.xz to:"
echo "     android/app/src/main/assets/rootfs-aarch64.tar.xz"
echo "  2. Build the APK:"
echo "     cd android && ./gradlew assembleDebug"
echo ""
echo "  The app will extract and initialize the rootfs"
echo "  on first launch via RootfsManager.install()"
echo ""
