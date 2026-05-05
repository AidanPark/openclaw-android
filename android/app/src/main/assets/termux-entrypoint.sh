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

# Limpiar ld.so.preload — el bootstrap de Termux lo apunta a
# libtermux-exec-ld-preload.so compilado para com.termux.
# En com.openclaw.android ese path no existe → signal 1 crash.
if [ -f "$PREFIX/etc/ld.so.preload" ]; then
    : > "$PREFIX/etc/ld.so.preload" 2>/dev/null || true
fi

# Crear bash.bashrc en el PREFIX real si no existe.
# bash está compilado con /data/data/com.termux/files/usr hardcodeado,
# por lo que intenta leer bash.bashrc desde ese path → Permission denied.
# Al crear el archivo en NUESTRO PREFIX y lanzar bash con --norc --noprofile
# + --init-file, evitamos que bash toque el path hardcodeado.
mkdir -p "$PREFIX/etc" 2>/dev/null
if [ ! -f "$PREFIX/etc/bash.bashrc" ]; then
    cat > "$PREFIX/etc/bash.bashrc" << 'EOF'
# OpenClaw bash.bashrc — auto-generado por termux-entrypoint.sh
export PREFIX="@PREFIX@"
export HOME="@HOME@"
export TMPDIR="@TMPDIR@"
export PATH="@PREFIX@/bin:@PREFIX@/bin/applets:/system/bin:/bin"
export LD_LIBRARY_PATH="@PREFIX@/lib"
export LANG=en_US.UTF-8
export TERM=xterm-256color
EOF
    # Sustituir los placeholders con los valores reales
    sed -i "s|@PREFIX@|$PREFIX|g; s|@HOME@|$HOME_DIR|g; s|@TMPDIR@|$APP_BASE/tmp|g" \
        "$PREFIX/etc/bash.bashrc" 2>/dev/null || true
fi

# Crear .bashrc en HOME si no existe
if [ ! -f "$HOME_DIR/.bashrc" ]; then
    cat > "$HOME_DIR/.bashrc" << EOF
# OpenClaw .bashrc
export HOME="$HOME_DIR"
export PREFIX="$PREFIX"
export TMPDIR="$APP_BASE/tmp"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export PS1='\u@openclaw:\w\$ '
alias ls='ls --color=auto'
alias ll='ls -la'
cd "$HOME_DIR"
EOF
fi

# Cambiar al directorio home
cd "$HOME_DIR"

# Ejecutar bash interactivo.
# --norc      : no leer /data/data/com.termux/.../bash.bashrc (hardcodeado → Permission denied)
# --noprofile : no leer /data/data/com.termux/.../profile     (hardcodeado → Permission denied)
# --init-file : cargar NUESTRO .bashrc desde el HOME real
# -i          : shell interactivo
exec "$PREFIX/bin/bash" --norc --noprofile --init-file "$HOME_DIR/.bashrc" -i "$@"