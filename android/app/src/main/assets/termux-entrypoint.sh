#!/system/bin/sh
# Entrypoint para Termux Bootstrap en OpenClaw Android
# Configura el entorno y ejecuta bash con el entorno correcto

# Ruta base de la aplicación
APP_BASE="/data/data/com.openclaw.android/files"
PREFIX="$APP_BASE/usr"
HOME_DIR="$APP_BASE/home"

# Verificar que el bootstrap esté instalado
if [ ! -x "$PREFIX/bin/bash" ]; then
    echo "ERROR: Termux Bootstrap no está instalado."
    echo "Por favor, instala Termux Bootstrap desde el Dashboard de OpenClaw."
    echo ""
    echo "Para instalar manualmente:"
    echo "1. Abre OpenClaw"
    echo "2. Ve al Dashboard"
    echo "3. Haz clic en 'Instalar Termux Bootstrap'"
    exit 1
fi

# Configurar entorno
export HOME="$HOME_DIR"
export PREFIX="$PREFIX"
export TMPDIR="$APP_BASE/tmp"
export TERM="xterm-256color"
export LANG="en_US.UTF-8"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"

# Variables para dpkg/apt
export DEBIAN_FRONTEND="noninteractive"
export DEBCONF_NONINTERACTIVE_SEEN="true"

# Crear directorios si no existen
mkdir -p "$HOME_DIR" "$TMPDIR" 2>/dev/null

# Cambiar al directorio home
cd "$HOME_DIR"

# Ejecutar bash interactivo
exec "$PREFIX/bin/bash" -i "$@"