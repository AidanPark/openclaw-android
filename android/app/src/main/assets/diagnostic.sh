#!/system/bin/sh
# Script de diagnóstico para OpenClaw Android
# Verifica el estado de Termux Bootstrap

echo "=== Diagnóstico de OpenClaw Android ==="
echo "Fecha: $(date)"
echo ""

# Información del sistema
echo "1. Información del sistema:"
echo "   PID: $$"
echo "   Usuario: $(id)"
echo "   Directorio actual: $(pwd)"
echo ""

# Verificar estructura de directorios
echo "2. Estructura de directorios:"
# Detectar APP_BASE dinámicamente si no está en el entorno
if [ -z "${APP_FILES_DIR:-}" ]; then
    if [ -n "${HOME:-}" ] && [ "${HOME}" != "${HOME%/home}" ]; then
        APP_FILES_DIR="${HOME%/home}"
    elif [ -n "${PREFIX:-}" ] && [ "${PREFIX}" != "${PREFIX%/usr}" ]; then
        APP_FILES_DIR="${PREFIX%/usr}"
    else
        # Fallback razonable
        APP_FILES_DIR="/data/data/com.openclaw.android/files"
    fi
fi
APP_BASE="$APP_FILES_DIR"
if [ -d "$APP_BASE" ]; then
    echo "   ✓ Directorio base existe: $APP_BASE"
    ls -la "$APP_BASE/" | head -20
else
    echo "   ✗ Directorio base NO existe: $APP_BASE"
fi
echo ""

# Verificar Termux Bootstrap
echo "3. Termux Bootstrap:"
PREFIX="$APP_BASE/usr"
MARKER="$APP_BASE/.termux-bootstrap-installed"

if [ -f "$MARKER" ]; then
    echo "   ✓ Marcador existe: $MARKER"
    echo "   Contenido:"
    cat "$MARKER"
else
    echo "   ✗ Marcador NO existe"
fi
echo ""

if [ -d "$PREFIX" ]; then
    echo "   ✓ Prefix existe: $PREFIX"
    echo "   Contenido de bin/:"
    if [ -d "$PREFIX/bin" ]; then
        ls -la "$PREFIX/bin/" | head -20
    else
        echo "   ✗ bin/ no existe"
    fi
else
    echo "   ✗ Prefix NO existe: $PREFIX"
fi
echo ""

# Verificar binarios esenciales
echo "4. Binarios esenciales:"
for bin in bash dpkg apt curl; do
    BIN_PATH="$PREFIX/bin/$bin"
    if [ -x "$BIN_PATH" ]; then
        echo "   ✓ $bin: $BIN_PATH (ejecutable)"
    elif [ -f "$BIN_PATH" ]; then
        echo "   ⚠ $bin: $BIN_PATH (existe pero no ejecutable)"
        ls -la "$BIN_PATH"
    else
        echo "   ✗ $bin: NO encontrado"
    fi
done
echo ""

# Variables de entorno
echo "5. Variables de entorno:"
env | grep -E "(HOME|PREFIX|PATH|LD_LIBRARY|TERM|LANG)" | sort
echo ""

# Conclusión
echo "=== Conclusión ==="
if [ -x "$PREFIX/bin/bash" ] && [ -f "$MARKER" ]; then
    echo "✓ Termux Bootstrap parece estar instalado correctamente."
    echo "  Bash disponible en: $PREFIX/bin/bash"
    echo ""
    echo "Para probar:"
    echo "  $PREFIX/bin/bash -c 'echo \"Hello from Termux Bootstrap!\"'"
else
    echo "✗ Termux Bootstrap NO está instalado correctamente."
    echo ""
    echo "Solución:"
    echo "  1. Abre OpenClaw"
    echo "  2. Ve al Dashboard"
    echo "  3. Haz clic en 'Instalar Termux Bootstrap'"
    echo "  4. Espera a que se complete la instalación (~50MB, 2-3 minutos)"
fi