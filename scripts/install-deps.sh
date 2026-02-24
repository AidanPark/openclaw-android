#!/usr/bin/env bash
# install-deps.sh - Install required Termux packages
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
PACKAGES=(
    nodejs-lts
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

# Verify Node.js version
if ! command -v node &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} Node.js installation failed"
    exit 1
fi

NODE_VER=$(node -v)
NODE_MAJOR="${NODE_VER%%.*}"
NODE_MAJOR="${NODE_MAJOR#v}"

echo -e "${GREEN}[OK]${NC}   Node.js $NODE_VER installed"

if [ "$NODE_MAJOR" -lt 22 ]; then
    echo -e "${RED}[FAIL]${NC} Node.js >= 22 required, got $NODE_VER"
    echo "       Try: pkg install nodejs-lts"
    exit 1
fi

# Verify npm
if ! command -v npm &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} npm not found"
    exit 1
fi

NPM_VER=$(npm -v)
echo -e "${GREEN}[OK]${NC}   npm $NPM_VER installed"

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
