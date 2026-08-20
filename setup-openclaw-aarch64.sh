#!/usr/bin/env bash
# Setup openclaw-android di Termux — paksa pacman ke aarch64 lalu install OpenClaw.
# Idempotent: aman dijalankan ulang. Design untuk Android aarch64/Termux saja.
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
PACMAN_CONF="$PREFIX/etc/pacman.conf"
CACHE="$PREFIX/var/cache/pacman/pkg"
KEYRING_DIR="$PREFIX/etc/pacman.d/gnupg"
LINKER="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
INSTALLER_URL="${INSTALLER_URL:-https://myopenclawhub.com/install}"

info() { printf '[INFO] %s\n' "$*"; }
die()  { printf '[FAIL] %s\n' "$*" >&2; exit 1; }

# --- Guard: hanya Termux/Android ---
[ -d /data/data/com.termux ] || die "bukan lingkungan Termux/Android. Jangan dijalankan di PC."

# --- Guard: host arch harus aarch64 (eksak, sama seperti install-glibc.sh upstream) ---
[ "$(uname -m)" = "aarch64" ] || die "arch tidak didukung: $(uname -m). Install-glibc.sh upstream butuh aarch64."
info "Host arch: $(uname -m)"

# --- Install pacman bila belum ada (sama seperti install-glibc.sh upstream) ---
command -v pacman >/dev/null || { info "Install pacman dulu..."; pkg install -y pacman; }

# --- Paksa Architecture = aarch64 di pacman.conf (ganti nilai apapun sebelumnya) ---
if [ -f "$PACMAN_CONF" ]; then
  sed -i 's/^Architecture\s*=.*/Architecture = aarch64/' "$PACMAN_CONF"
  grep -q '^Architecture = aarch64' "$PACMAN_CONF" || echo 'Architecture = aarch64' >> "$PACMAN_CONF"
else
  die "pacman.conf tidak ada di $PACMAN_CONF. Periksa instalasi pacman."
fi
info "pacman.conf: $(grep '^Architecture' "$PACMAN_CONF")"

# --- Init keyring bila belum pernah ---
if [ ! -d "$KEYRING_DIR" ]; then
  info "Inisialisasi keyring..."
  pacman-key --init
  pacman-key --populate
fi

# --- Buang cache package arch-salah / corrupt ---
info "Bersihkan cache glibc lama..."
rm -f "$CACHE"/glibc-*.pkg.tar.xz "$CACHE"/glibc-runner-*.pkg.tar.xz

# --- Install glibc + glibc-runner aarch64 ---
# --assume-installed: sama seperti install-glibc.sh upstream (paket disediakan apt Termux, pacman tak kenal)
info "Install glibc, glibc-runner, dan gcc-libs-glibc (libstdc++ untuk Node.js)..."
pacman -Sy glibc glibc-runner gcc-libs-glibc --noconfirm --assume-installed bash,patchelf,resolv-conf

# --- Purge library arch-salah (sisa era pacman.conf x86_64) ---
# glibc aarch64 hanya menimpa file milik paketnya sendiri. lib x86_64 lain
# (mis. libstdc++.so.6 dari gcc-libs-glibc versi lama) tetap nempel dan
# membuat node.real segfault saat di-load. Hapus semua .so yang bukan aarch64.
info "Bersihkan library non-aarch64 di $PREFIX/glibc/lib..."
if [ -d "$PREFIX/glibc/lib" ]; then
  for _lib in "$PREFIX"/glibc/lib/*.so*; do
    [ -e "$_lib" ] || continue
    if file "$_lib" 2>/dev/null | grep -q 'ELF' && ! file "$_lib" 2>/dev/null | grep -q 'aarch64'; then
      rm -f "$_lib"
      info "  hapus: $(basename "$_lib")"
    fi
  done
fi

# --- Verifikasi dynamic linker aarch64 benar-benar ada ---
[ -f "$LINKER" ] || die "linker aarch64 tidak ditemukan: $LINKER. Versi x86_64 masih menempel?"
info "Linker OK: $LINKER"

# --- Download installer, patch defensif x86_64->aarch64 (no-op bila sudah bersih), jalankan ---
info "Download installer dari $INSTALLER_URL (jangan simpan di /tmp, noexec)..."
curl -fsSL "$INSTALLER_URL" > "$HOME/oa_install.sh"
sed -i 's/Architecture = x86_64/Architecture = aarch64/g' "$HOME/oa_install.sh"
info "Jalankan installer..."
bash "$HOME/oa_install.sh"

info "Selesai. Mulai pakai: source ~/.bashrc && oa"