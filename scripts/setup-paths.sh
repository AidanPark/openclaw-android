#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

echo "=== Setting Up Paths ==="
echo ""

mkdir -p "$PREFIX/tmp"
echo -e "${GREEN}[OK]${NC}   Created $PREFIX/tmp"

mkdir -p "$PROJECT_DIR/patches"
echo -e "${GREEN}[OK]${NC}   Created $PROJECT_DIR/patches"

echo ""
echo "Standard path mappings (via \$PREFIX):"
echo "  /bin/sh      -> $PREFIX/bin/sh"
echo "  /usr/bin/env -> $PREFIX/bin/env"
echo "  /tmp         -> $PREFIX/tmp"

echo ""
echo -e "${GREEN}Path setup complete.${NC}"
