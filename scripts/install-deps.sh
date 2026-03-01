#!/usr/bin/env bash
# install-deps.sh - Install required Termux packages (glibc architecture)
# Note: Node.js is NOT installed here — it's installed in install-glibc-env.sh
# as a glibc linux-arm64 binary instead of Termux's Bionic nodejs-lts.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

echo "=== Installing Dependencies ==="
echo ""

# Update and upgrade package repos
echo "Updating package repositories..."
pkg update -y
pkg upgrade -y

# Install required packages
# Note: nodejs-lts is NOT included — glibc Node.js is installed separately
# pacman and proot are installed by install-glibc-env.sh
PACKAGES=(
    git
    python
    make
    cmake
    clang
    binutils
    tmux
    ttyd
    dufs
    android-tools
)

echo "Installing packages: ${PACKAGES[*]}"
pkg install -y "${PACKAGES[@]}"

echo ""

# Install PyYAML (required for .skill packaging)
echo "Installing PyYAML..."
if pip install pyyaml -q; then
    echo -e "${GREEN}[OK]${NC}   PyYAML installed"
else
    echo -e "${RED}[FAIL]${NC} PyYAML installation failed"
    exit 1
fi

echo ""
echo -e "${GREEN}All dependencies installed.${NC}"
echo ""
echo "Note: Node.js will be installed as a glibc binary in the next step."
