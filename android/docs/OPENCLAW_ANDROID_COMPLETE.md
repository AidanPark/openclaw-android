# OpenClaw Android — Documentación Técnica Completa
## Runtime Portable Offline + Integración en APK

**Versión documentada:** OpenClaw 2026.4.29 (a448042)  
**Node.js:** v22.22.0 | **npm:** 10.9.4  
**Plataforma:** Android 10+ / aarch64 / Termux  
**Fecha:** Mayo 2026

---

## Índice

1. [Contexto y Arquitectura](#1-contexto-y-arquitectura)
2. [Instalación Online (modo curl)](#2-instalación-online-modo-curl)
3. [Errores Encontrados y Correcciones Aplicadas](#3-errores-encontrados-y-correcciones-aplicadas)
4. [Construcción del Payload Offline](#4-construcción-del-payload-offline)
5. [Scripts Generados](#5-scripts-generados)
6. [Validación y Empaquetado Final](#6-validación-y-empaquetado-final)
7. [Integración en APK Android](#7-integración-en-apk-android)
8. [Modo Offline vs Online en la App](#8-modo-offline-vs-online-en-la-app)
9. [Actualizaciones Futuras](#9-actualizaciones-futuras)
10. [Plan de Recuperación Total](#10-plan-de-recuperación-total)
11. [Prompt para IA — Aplicar Correcciones](#11-prompt-para-ia--aplicar-correcciones)

---

## 1. Contexto y Arquitectura

### ¿Qué se construyó?

Un **runtime Linux portable autocontenido** capaz de ejecutar OpenClaw dentro de Android sin depender de:
- Termux externo instalado en el dispositivo
- Bionic libc (libc nativa de Android)
- Conexión a internet en el dispositivo destino

### Por qué se necesita glibc y no Bionic

| Aspecto | Bionic (Android nativo) | glibc (Linux) |
|---|---|---|
| Portabilidad fuera de Termux | ❌ No | ✔ Sí |
| Compatible con node.real ELF | ❌ No | ✔ Sí |
| Inyecta LD_PRELOAD peligroso | ✔ Sí (problema) | ❌ No |
| Usado por el sistema Android | ✔ Sí | ❌ No |

### Estructura final del payload

```
payload/
├── glibc/
│   ├── lib/
│   │   ├── ld-linux-aarch64.so.1   ← loader ELF principal
│   │   ├── libc.so.6
│   │   ├── libc.so                 ← symlink CRÍTICO (sin esto falla)
│   │   ├── libm.so.6
│   │   ├── libpthread.so.0
│   │   ├── libdl.so.2
│   │   ├── librt.so.1
│   │   ├── libresolv.so.2
│   │   ├── libnss_dns.so.2
│   │   ├── libnss_files.so.2
│   │   ├── libutil.so.1
│   │   ├── libstdc++.so.6
│   │   ├── libgcc_s.so.1
│   │   ├── libz.so.1
│   │   ├── libcrypto.so.3
│   │   └── libssl.so.3
│   └── etc/
│       ├── nsswitch.conf
│       └── hosts
├── lib/
│   ├── node/
│   │   └── bin/
│   │       └── node.real           ← binario ELF glibc (NO el de Termux)
│   └── openclaw/                   ← instalado vía npm con parches
├── certs/
│   └── cert.pem
├── patches/
│   └── glibc-compat.js
├── run-openclaw.sh                 ← launcher principal
└── PAYLOAD_CHECKSUM.sha256
```

---

## 2. Instalación Online (modo curl)

### Comando de instalación

```bash
curl -sL myopenclawhub.com/install | bash
```

### Qué hace este comando

- `curl -sL` descarga el script de instalación en memoria (no guarda archivo)
- `| bash` lo ejecuta directamente
- El instalador v1.0.27 ejecuta 8 fases automáticamente

### Fases del instalador v1.0.27

| Fase | Descripción |
|---|---|
| [1/8] Environment Check | Verifica Termux, arquitectura, espacio |
| [2/8] Platform Selection | Selecciona plataforma OpenClaw |
| [3/8] Optional Tools (L3) | Ofrece tmux, ttyd, Chromium, etc. |
| [4/8] Core Infrastructure | Actualiza paquetes base + build tools |
| [5/8] glibc Environment | Instala Node.js vía glibc |
| [6/8] Platform Package | `npm install -g openclaw@latest` |
| [6.5] Environment + CLI | Variables de entorno + markers |
| [7/8] Optional Tools | Instala herramientas opcionales |
| [8/8] Verification | Verifica toda la instalación |

### Respuestas correctas durante la instalación

Cuando el instalador pregunte por herramientas opcionales, responder `n` a todo para evitar conflictos:

```
Install tmux?             → n
Install ttyd?             → n
Install dufs?             → n
Install android-tools?    → n
Install Chromium?         → n
Install code-server?      → n
Install OpenCode?         → n
Install Claude Code CLI?  → n
Install Gemini CLI?       → n
Install Codex CLI?        → n
```

Cuando `dpkg` pregunte por archivos de configuración:
```
*** openssl.cnf (Y/I/N/O/D/Z) [default=N] ?  → N (Enter)
*** sources.list (Y/I/N/O/D/Z) [default=N] ? → N (Enter)
```

### Resultado esperado al completar

```
[PASS] Node.js v22.22.0 (>= 22)
[PASS] npm 10.9.4
[PASS] OA_GLIBC=1 (glibc architecture)
[PASS] glibc-compat.js exists
[PASS] glibc dynamic linker (ld-linux-aarch64.so.1)
[PASS] glibc node wrapper script
[PASS] openclaw OpenClaw 2026.4.29 (a448042)
[PASS] CONTAINER=1
[PASS] clawdhub command available

Results: 12 passed, 0 failed, 2 warnings
Installation verification PASSED!
```

---

## 3. Errores Encontrados y Correcciones Aplicadas

### ERROR 1 — `env: 'node': No such file or directory`

**Cuándo ocurre:** Al ejecutar `openclaw start` justo después de instalar.

**Causa:** La nueva sesión de Termux no carga el bloque de variables de entorno que el instalador escribió en `~/.bashrc`. El PATH no incluye la ruta del wrapper glibc de node.

**Corrección:**
```bash
source ~/.bashrc

# O cargar manualmente el PATH correcto
export PATH="$PREFIX/glibc/bin:$PATH"

# Verificar
node --version     # → v22.22.0
openclaw --version # → OpenClaw 2026.4.29
```

**Corrección permanente en código (si aplica al APK):** Ver sección 7.

---

### ERROR 2 — `dpkg was interrupted` / `end of file on stdin at conffile prompt`

**Cuándo ocurre:** Al volver a ejecutar `curl | bash` después de una instalación interrumpida.

**Causa:** La primera ejecución del instalador se cortó mientras `dpkg` esperaba una respuesta interactiva sobre `sources.list`. Al correrse via pipe (`curl | bash`), el stdin estaba vacío y `dpkg` falló.

**Corrección:**
```bash
# Paso 1: Reparar dpkg
dpkg --configure -a
# Si pregunta por algún archivo de config → N

# Paso 2: Reparar dependencias rotas
apt -f install

# Paso 3: Volver a ejecutar el instalador
curl -sL myopenclawhub.com/install | bash
```

---

### ERROR 3 — `tar: payload/glibc-aarch64.tar.xz: Cannot open: No such file or directory`

**Cuándo ocurre:** Al intentar crear el archivo comprimido de glibc estando dentro de `~/payload/`.

**Causa:** El comando `tar -cJf payload/glibc-aarch64.tar.xz` estando en `~/payload/` intentaba escribir en `~/payload/payload/glibc-aarch64.tar.xz` — ruta inexistente.

**Corrección:**
```bash
# SIEMPRE ejecutar desde ~ con ruta absoluta
cd ~
tar -cJf ~/payload/glibc-aarch64.tar.xz -C ~/.build-work/glibc-wrap glibc/
```

---

### ERROR 4 — `cannot execute: required file not found`

**Cuándo ocurre:** Al intentar ejecutar `run-openclaw.sh` después de crearlo.

**Causa:** El loader `ld-linux-aarch64.so.1` estaba en `payload/glibc-aarch64.tar.xz` (comprimido) pero NO extraído como carpeta `payload/glibc/lib/`.

**Corrección:**
```bash
cd ~/payload
tar -xJf glibc-aarch64.tar.xz
ls glibc/lib/   # Verificar que ld-linux-aarch64.so.1 existe
```

---

### ERROR 5 — `error while loading shared libraries: libc.so: invalid ELF header`

**Cuándo ocurre:** Al ejecutar el loader con `node.real`.

**Causa:** `node.real` busca `libc.so` (sin número de versión). Al no encontrarlo en el payload, buscaba en el sistema Termux `/data/data/com.termux/files/usr/glibc/lib/libc.so` y encontraba un binario incompatible (ELF header inválido por diferencias de versión).

**Corrección — crear symlink crítico:**
```bash
cd ~/payload/glibc/lib
ln -s libc.so.6 libc.so

# Verificar
ls -l libc.so
# libc.so -> libc.so.6
```

---

### ERROR 6 — `version 'LIBC' not found (required by libtermux-exec-ld-preload.so)`

**Cuándo ocurre:** Al ejecutar `node.real` con el loader glibc.

**Causa:** Termux inyecta automáticamente `libtermux-exec-ld-preload.so` vía `LD_PRELOAD`. Este binario usa Bionic libc, incompatible con glibc. Hay un conflicto de versiones de símbolos LIBC.

**Corrección — usar `env -i` para limpiar todo el entorno:**
```bash
exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin" \
  LD_LIBRARY_PATH="$LIBS" \
  "$LOADER" --library-path "$LIBS" "$NODE" "$APP" "$@"
```

El `env -i` elimina TODAS las variables heredadas incluyendo `LD_PRELOAD`, evitando la inyección de Termux.

---

### ERROR 7 — `openclaw.sh: command not found`

**Cuándo ocurre:** Al ejecutar `openclaw.sh` sin ruta completa.

**Causa:** En Linux/Android, el directorio actual no está en PATH por defecto.

**Corrección:**
```bash
# Opción A — ejecutar con ruta
~/openclaw.sh --help

# Opción B — mover al PATH (recomendada)
cp ~/openclaw.sh $PREFIX/bin/openclaw
chmod +x $PREFIX/bin/openclaw
openclaw --help
```

---

### ERROR 8 — Rutas inconsistentes en el APK (`/data/data/` vs `/data/user/0/`)

**Cuándo ocurre:** En el APK con terminal embebido, las variables de entorno mezclaban:
```
PREFIX = /data/data/com.openclaw.android.debug/files/usr
HOME   = /data/user/0/com.openclaw.android.debug/files/home
```

**Causa:** El `EnvironmentBuilder` del APK hardcodeaba `/data/data/` para PREFIX pero usaba `/data/user/0/` para HOME. Android las resuelve al mismo lugar físico, pero los scripts bash fallan al comparar strings.

**Corrección en Kotlin:**
```kotlin
// MAL — rutas inconsistentes
val prefix = "/data/data/$packageName/files/usr"
val home   = "/data/user/0/$packageName/files/home"

// BIEN — siempre usar context.filesDir
val base   = context.filesDir.absolutePath
val prefix = "$base/usr"
val home   = "$base/home"
val tmpdir = "$base/tmp"
```

---

## 4. Construcción del Payload Offline

### FASE 0 — Limpiar y preparar sistema

```bash
# Reparar dpkg si fue interrumpido anteriormente
dpkg --configure -a        # → N cuando pregunte por config files

# Reparar dependencias
apt -f install

# Seleccionar mirror estable
termux-change-repo         # → Seleccionar packages.termux.dev

# Actualizar base
pkg update -y && pkg upgrade -y
```

### FASE 1 — Preparar directorios

```bash
termux-setup-storage

# Limpiar intentos anteriores
rm -rf ~/payload ~/.build-work ~/payload-final.tar.gz

# Crear estructura
mkdir -p ~/payload/{certs,bin,lib/node,patches}
mkdir -p ~/.build-work
```

### FASE 2 — Instalar dependencias

```bash
pkg install -y nodejs xz-utils glibc ca-certificates \
  coreutils curl tar grep findutils sed dos2unix which
```

### FASE 3 — Empaquetar glibc (parte más crítica)

```bash
mkdir -p ~/.build-work/glibc-stage/{lib,etc}

# Librerías esenciales
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
         libdl.so.2 librt.so.1 libresolv.so.2 libnss_dns.so.2 \
         libnss_files.so.2 libutil.so.1; do
    [ -e "$PREFIX/glibc/lib/$f" ] && \
        cp -aL "$PREFIX/glibc/lib/$f" ~/.build-work/glibc-stage/lib/
done

# Librerías opcionales útiles
for f in libstdc++.so.6 libgcc_s.so.1 libz.so.1 libcrypto.so.3 libssl.so.3; do
    [ -e "$PREFIX/glibc/lib/$f" ] && \
        cp -aL "$PREFIX/glibc/lib/$f" ~/.build-work/glibc-stage/lib/
done

# Archivos etc mínimos
printf "passwd: files\ngroup: files\nhosts: files dns\n" \
    > ~/.build-work/glibc-stage/etc/nsswitch.conf
printf "127.0.0.1 localhost\n::1 localhost\n" \
    > ~/.build-work/glibc-stage/etc/hosts

# Empaquetar (DESDE ~, no desde ~/payload)
mkdir -p ~/.build-work/glibc-wrap/glibc
cp -r ~/.build-work/glibc-stage/* ~/.build-work/glibc-wrap/glibc/
cd ~
tar -cJf ~/payload/glibc-aarch64.tar.xz -C ~/.build-work/glibc-wrap glibc/

# Verificar integridad
xz -t ~/payload/glibc-aarch64.tar.xz
```

### FASE 4 — Copiar certificados SSL

```bash
cp $PREFIX/etc/tls/cert.pem ~/payload/certs/ 2>/dev/null || \
    cp $PREFIX/etc/ssl/certs/ca-certificates.crt ~/payload/certs/cert.pem

grep -c "BEGIN CERTIFICATE" ~/payload/certs/cert.pem
# Resultado esperado: > 0 (normalmente ~145)
```

### FASE 5 — Node.js portable (binario glibc, NO el de Termux)

> **Crítico:** El node de Termux usa Bionic. Hay que descargar el binario oficial Linux ARM64 glibc.

```bash
NODE_VERSION="v22.22.0"
cd ~/.build-work

curl -fLO https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz
tar -xJf node-${NODE_VERSION}-linux-arm64.tar.xz

mkdir -p ~/payload/lib/node/bin
mv node-${NODE_VERSION}-linux-arm64/bin/node ~/payload/lib/node/bin/node.real
chmod +x ~/payload/lib/node/bin/node.real

# Verificar que es ELF válido
file ~/payload/lib/node/bin/node.real
# → ELF 64-bit LSB executable, ARM aarch64

head -c 4 ~/payload/lib/node/bin/node.real | od -An -tx1
# → 7f 45 4c 46
```

### FASE 6 — OpenClaw vía npm

```bash
mkdir -p ~/.build-work/npm
npm install --prefix ~/.build-work/npm openclaw@latest --ignore-scripts

mkdir -p ~/payload/lib/openclaw
cp -r ~/.build-work/npm/node_modules/openclaw/* ~/payload/lib/openclaw/

ls ~/payload/lib/openclaw  # Verificar
```

### FASE 7 — Patch de compatibilidad

```bash
cat > ~/payload/patches/glibc-compat.js << 'EOF'
'use strict';
delete process.env.LD_PRELOAD;
EOF
```

### FASE 8 — Extraer glibc en el payload (OBLIGATORIO)

```bash
cd ~/payload
tar -xJf glibc-aarch64.tar.xz

# Verificar
ls ~/payload/glibc/lib/
# Debe mostrar: ld-linux-aarch64.so.1, libc.so.6, etc.
```

### FASE 9 — Crear symlink libc.so (CRÍTICO)

```bash
cd ~/payload/glibc/lib
ln -s libc.so.6 libc.so

# Verificar
ls -l libc.so
# → libc.so -> libc.so.6
```

---

## 5. Scripts Generados

### Script 1 — `run-openclaw.sh` (ejecutor aislado principal)

```bash
cat > ~/payload/run-openclaw.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash

DIR="$(cd "$(dirname "$0")" && pwd)"

LOADER="$DIR/glibc/lib/ld-linux-aarch64.so.1"
LIBS="$DIR/glibc/lib"
NODE="$DIR/lib/node/bin/node.real"
APP="$DIR/lib/openclaw/openclaw.mjs"

# env -i elimina LD_PRELOAD de Termux (crítico para evitar conflicto glibc/bionic)
exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin" \
  LD_LIBRARY_PATH="$LIBS" \
  "$LOADER" \
  --library-path "$LIBS" \
  "$NODE" "$APP" "$@"
EOF

chmod +x ~/payload/run-openclaw.sh
```

---

### Script 2 — `openclaw.sh` (launcher global desde cualquier directorio)

```bash
cat > ~/openclaw.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash

BASE="$HOME/payload"

LOADER="$BASE/glibc/lib/ld-linux-aarch64.so.1"
LIBS="$BASE/glibc/lib"
NODE="$BASE/lib/node/bin/node.real"
APP="$BASE/lib/openclaw/openclaw.mjs"

exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin" \
  LD_LIBRARY_PATH="$LIBS" \
  "$LOADER" \
  --library-path "$LIBS" \
  "$NODE" "$APP" "$@"
EOF

chmod +x ~/openclaw.sh

# Registrar en PATH para ejecutar desde cualquier lugar
cp ~/openclaw.sh $PREFIX/bin/openclaw
chmod +x $PREFIX/bin/openclaw
```

**Uso:**
```bash
openclaw --help
openclaw gateway
openclaw onboard
openclaw --version
```

---

### Script 3 — `install-openclaw.sh` (instalador automático offline)

```bash
cat > ~/install-openclaw.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "=== OpenClaw Offline Installer ==="

ARCHIVE="payload.tar.gz"
INSTALL_DIR="$HOME/openclaw"

if [ ! -f "$ARCHIVE" ]; then
    echo "ERROR: No se encontró $ARCHIVE en $(pwd)"
    exit 1
fi

if [ -d "$INSTALL_DIR" ]; then
    echo "Instalación previa detectada — eliminando..."
    rm -rf "$INSTALL_DIR"
fi

echo "Extrayendo payload..."
mkdir -p "$INSTALL_DIR"
tar -xzf "$ARCHIVE" -C "$INSTALL_DIR"

echo "Creando comando global..."
cat > $PREFIX/bin/openclaw << 'INNER'
#!/data/data/com.termux/files/usr/bin/bash
BASE="$HOME/openclaw/payload"
LOADER="$BASE/glibc/lib/ld-linux-aarch64.so.1"
LIBS="$BASE/glibc/lib"
NODE="$BASE/lib/node/bin/node.real"
APP="$BASE/lib/openclaw/openclaw.mjs"
exec env -i \
  HOME="$HOME" \
  PATH="/usr/bin:/bin" \
  LD_LIBRARY_PATH="$LIBS" \
  "$LOADER" --library-path "$LIBS" \
  "$NODE" "$APP" "$@"
INNER
chmod +x $PREFIX/bin/openclaw

echo "Verificando..."
if openclaw --version > /dev/null 2>&1; then
    echo "OK — Instalación completada"
    openclaw --version
else
    echo "WARN — Instalado pero verificación falló"
fi

echo "Usa: openclaw --help"
EOF

chmod +x ~/install-openclaw.sh
```

---

### Script 4 — `install-online.sh` (fallback con internet)

Para integrar en el APK como modo de instalación online:

```bash
cat > ~/install-online.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash

# Actualizar e instalar Node.js
pkg update -y
pkg install nodejs-lts -y

# Ejecutar instalador oficial de OpenClaw
curl -sL myopenclawhub.com/install | bash

# Corregir PATH si es necesario
export PATH="$PREFIX/glibc/bin:$PATH"
echo 'export PATH="$PREFIX/glibc/bin:$PATH"' >> ~/.bashrc

# Verificar
node --version
openclaw --version
EOF

chmod +x ~/install-online.sh
```

---

## 6. Validación y Empaquetado Final

### Prueba del loader (validación crítica)

```bash
cd ~/payload
./glibc/lib/ld-linux-aarch64.so.1 \
  --library-path ./glibc/lib \
  ./lib/node/bin/node.real --version
# Resultado esperado: v22.22.0
```

### Prueba completa de OpenClaw

```bash
cd ~/payload
./run-openclaw.sh --help
# Resultado esperado: OpenClaw 2026.4.29 — Usage: openclaw [options] [command]
```

### Prueba de aislamiento (sin Termux)

```bash
env -i \
  LD_LIBRARY_PATH=./glibc/lib \
  ./glibc/lib/ld-linux-aarch64.so.1 \
  --library-path ./glibc/lib \
  ./lib/node/bin/node.real --version
```

### Generar checksums de integridad

```bash
cd ~/payload
find . -type f -print0 | xargs -0 sha256sum | sort -k2 > PAYLOAD_CHECKSUM.sha256

# Verificar integridad
sha256sum -c PAYLOAD_CHECKSUM.sha256
```

### Empaquetar y exportar

```bash
cd ~
tar -czf payload-final.tar.gz payload/

# Verificar contenido
tar -tzf payload-final.tar.gz | head -20

# Mover a Descargas del teléfono
cp ~/payload-final.tar.gz ~/storage/downloads/
cp ~/install-openclaw.sh ~/storage/downloads/
cp ~/openclaw.sh ~/storage/downloads/

# Renombrar para el APK
cp ~/payload-final.tar.gz ~/storage/downloads/payload.tar.gz
```

### Probar instalación en directorio limpio

```bash
mkdir ~/test-install
tar -xzf ~/payload-final.tar.gz -C ~/test-install
cd ~/test-install/payload
./run-openclaw.sh --help
```

---

## 7. Integración en APK Android

### Estructura de assets en el proyecto Android

```
app/src/main/assets/
├── payload.tar.gz          ← payload-final.tar.gz renombrado
└── install-online.sh       ← fallback si no hay payload
```

### Copiar assets al almacenamiento interno (Kotlin)

```kotlin
fun copyAsset(context: Context, assetName: String) {
    val file = File(context.filesDir, assetName)
    if (!file.exists()) {
        context.assets.open(assetName).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        file.setExecutable(true)
    }
}

// Uso al iniciar la app
copyAsset(this, "payload.tar.gz")
copyAsset(this, "install-online.sh")
```

### Extraer payload dentro de la app (Kotlin)

```kotlin
fun extractPayload(context: Context) {
    val filesDir = context.filesDir.absolutePath
    val process = Runtime.getRuntime().exec(arrayOf(
        "sh", "-c",
        "cd $filesDir && tar -xzf payload.tar.gz && chmod -R +x payload/"
    ))
    process.waitFor()
}
```

### Ejecutar OpenClaw desde la app (Kotlin)

```kotlin
fun runOpenClaw(context: Context, vararg args: String): Process {
    val base = "${context.filesDir.absolutePath}/payload"
    val pb = ProcessBuilder(
        "$base/glibc/lib/ld-linux-aarch64.so.1",
        "--library-path", "$base/glibc/lib",
        "$base/lib/node/bin/node.real",
        "$base/lib/openclaw/openclaw.mjs",
        *args
    )
    pb.environment().also { env ->
        env["LD_LIBRARY_PATH"] = "$base/glibc/lib"
        env.remove("LD_PRELOAD")
        env["OA_GLIBC"] = "1"
        env["CONTAINER"] = "1"
        env["HOME"] = context.filesDir.absolutePath
    }
    return pb.start()
}
```

### Corrección crítica — EnvironmentBuilder.kt

```kotlin
// INCORRECTO — mezcla /data/data/ con /data/user/0/
val prefix = "/data/data/$packageName/files/usr"   // ← MAL
val home   = "/data/user/0/$packageName/files/home" // ← MAL

// CORRECTO — usar context.filesDir consistentemente
val base   = context.filesDir.absolutePath
val prefix = "$base/usr"
val home   = "$base/home"
val tmpdir = "$base/tmp"

// Env block completo
val envBlock = mapOf(
    "HOME"                to home,
    "PREFIX"              to prefix,
    "TMPDIR"              to tmpdir,
    "APP_FILES_DIR"       to base,
    "APP_PACKAGE"         to packageName,
    "PATH"                to "$home/.openclaw-android/bin:$home/.openclaw-android/node/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin",
    "NPM_CONFIG_PREFIX"   to prefix,
    "npm_config_prefix"   to prefix,
    "LD_LIBRARY_PATH"     to "$prefix/lib:$prefix/glibc/lib",
    "SSL_CERT_FILE"       to "$prefix/etc/tls/cert.pem",
    "CURL_CA_BUNDLE"      to "$prefix/etc/tls/cert.pem",
    "OA_GLIBC"            to "1",
    "CONTAINER"           to "1",
    "LANG"                to "en_US.UTF-8",
    "TERM"                to "xterm-256color"
)
```

### Corrección en install.sh (del APK)

El `install.sh` bundleado en el APK tiene 3 bugs críticos a corregir:

**Bug 1 — `set -euo pipefail` mata el proceso en verificación de red:**
```bash
# ANTES (MAL)
if ! curl -fsSL --connect-timeout 10 "$_NPM_REGISTRY" >/dev/null 2>&1; then

# DESPUÉS (BIEN)
set +e
if ! curl -fsSL --connect-timeout 15 --retry 2 "$_NPM_REGISTRY" >/dev/null 2>&1; then
    # fallback a mirror
fi
set -e
```

**Bug 2 — `pkg install nodejs` falla en sandbox de la app:**
```bash
# ANTES (MAL) — usa repos de Termux que no tienen glibc node
pkg install -y nodejs git

# DESPUÉS (BIEN) — descargar binario oficial
NODE_VERSION="v22.22.0"
NODE_DIR="$HOME/.openclaw-android/node"
curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.gz" \
    -o "$TMPDIR/node.tar.gz"
tar -xzf "$TMPDIR/node.tar.gz" -C "$NODE_DIR" --strip-components=1
```

**Bug 3 — NPM_BIN mal definido antes de instalar Node:**
```bash
# Redefinir DESPUÉS de instalar Node
NPM_BIN="$HOME/.openclaw-android/node/bin/npm"
[ ! -x "$NPM_BIN" ] && NPM_BIN="$PREFIX/bin/npm"
```

### Permisos en AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
```

### Flujo completo dentro del APK

```
APK inicia
    ↓
¿payload ya extraído?
    ├─ SÍ → startOpenClaw() directamente
    └─ NO → copyAsset("payload.tar.gz")
              ↓
            extractPayload()
              ↓
            chmod +x scripts y binarios
              ↓
            startOpenClaw()
              ↓
          OpenClaw corre aislado ✔
```

---

## 8. Modo Offline vs Online en la App

### Lógica de decisión (Kotlin)

```kotlin
class OpenClawSetup(private val context: Context) {

    private val payloadDir = File(context.filesDir, "payload")
    private val prefs = context.getSharedPreferences("openclaw", Context.MODE_PRIVATE)

    fun setupOpenClaw() {
        when {
            isAlreadyInstalled() -> startOpenClaw()
            hasOfflinePayload()  -> installFromPayload()
            else                 -> installOnline()
        }
    }

    private fun isAlreadyInstalled(): Boolean {
        return prefs.getBoolean("installed", false) &&
               File(payloadDir, "lib/openclaw/openclaw.mjs").exists()
    }

    private fun hasOfflinePayload(): Boolean {
        return try {
            context.assets.open("payload.tar.gz").available() > 0
        } catch (e: Exception) { false }
    }

    private fun installFromPayload() {
        // Extraer desde assets (modo offline)
        copyAsset(context, "payload.tar.gz")
        extractPayload(context)
        prefs.edit().putBoolean("installed", true).apply()
        startOpenClaw()
    }

    private fun installOnline() {
        // Fallback: ejecutar install-online.sh
        copyAsset(context, "install-online.sh")
        val process = ProcessBuilder("sh", "${context.filesDir}/install-online.sh")
            .directory(context.filesDir)
            .start()
        process.waitFor()
        prefs.edit().putBoolean("installed", true).apply()
        startOpenClaw()
    }
}
```

### Tamaños esperados

| Componente | Tamaño sin comprimir | Comprimido |
|---|---|---|
| glibc runtime | ~85 MB | ~27 MB |
| Node.js v22 | ~70 MB | ~25 MB |
| OpenClaw + node_modules | ~350 MB | ~60 MB |
| **Total payload** | **~500 MB** | **~112 MB** |

---

## 9. Actualizaciones Futuras

### Actualizar solo OpenClaw (más frecuente)

```bash
# En Termux
rm -rf ~/.build-work/npm
npm install --prefix ~/.build-work/npm openclaw@latest --ignore-scripts

rm -rf ~/payload/lib/openclaw
mkdir -p ~/payload/lib/openclaw
cp -r ~/.build-work/npm/node_modules/openclaw/* ~/payload/lib/openclaw/

# Regenerar checksums
cd ~/payload
find . -type f -print0 | xargs -0 sha256sum | sort -k2 > PAYLOAD_CHECKSUM.sha256

# Reempaquetar y exportar
cd ~
tar -czf payload-final.tar.gz payload/
cp payload-final.tar.gz ~/storage/downloads/payload.tar.gz
```

### Actualizar Node.js

```bash
NEW_NODE="v22.x.x"    # ← cambiar por versión deseada
cd ~/.build-work

curl -fLO https://nodejs.org/dist/${NEW_NODE}/node-${NEW_NODE}-linux-arm64.tar.xz
tar -xJf node-${NEW_NODE}-linux-arm64.tar.xz

cp node-${NEW_NODE}-linux-arm64/bin/node ~/payload/lib/node/bin/node.real
chmod +x ~/payload/lib/node/bin/node.real

# Verificar
file ~/payload/lib/node/bin/node.real

# Reempaquetar
cd ~
tar -czf payload-final.tar.gz payload/
```

### Script de actualización rápida (resumen)

```bash
#!/data/data/com.termux/files/usr/bin/bash
# update-openclaw.sh — ejecutar para actualizar OpenClaw en el payload

set -e

echo "=== Actualizando OpenClaw ==="

# 1. Actualizar OpenClaw
rm -rf ~/.build-work/npm
npm install --prefix ~/.build-work/npm openclaw@latest --ignore-scripts
rm -rf ~/payload/lib/openclaw
cp -r ~/.build-work/npm/node_modules/openclaw/* ~/payload/lib/openclaw/

# 2. Checksums
cd ~/payload
find . -type f -print0 | xargs -0 sha256sum | sort -k2 > PAYLOAD_CHECKSUM.sha256

# 3. Empaquetar
cd ~
tar -czf payload-final.tar.gz payload/
cp payload-final.tar.gz ~/storage/downloads/payload.tar.gz

echo "OK — Actualización completada"
openclaw --version
```

---

## 10. Plan de Recuperación Total

### Si node.real no ejecuta

```bash
# Verificar que es ELF válido
file ~/payload/lib/node/bin/node.real
# Debe decir: ELF 64-bit LSB executable, ARM aarch64

# Si está corrupto → re-descargar (FASE 5)
```

### Si glibc da errores

```bash
# Verificar symlink
ls -l ~/payload/glibc/lib/libc.so
# Si no existe:
cd ~/payload/glibc/lib && ln -s libc.so.6 libc.so

# Verificar que glibc está extraído
ls ~/payload/glibc/lib/ld-linux-aarch64.so.1
# Si no existe:
cd ~/payload && tar -xJf glibc-aarch64.tar.xz
```

### Si checksums fallan

```bash
cd ~/payload
find . -type f -print0 | xargs -0 sha256sum | sort -k2 > PAYLOAD_CHECKSUM.sha256
sha256sum -c PAYLOAD_CHECKSUM.sha256
```

### Reset completo

```bash
rm -rf ~/payload ~/.build-work
rm -f ~/payload-final.tar.gz ~/install-openclaw.sh ~/openclaw.sh
pkg clean
pkg update && pkg upgrade
pkg reinstall nodejs glibc coreutils curl tar
# Repetir desde FASE 0
```

---

## 11. Prompt para IA — Aplicar Correcciones

Copia este prompt y pásalo a cualquier IA junto con los archivos a corregir:

```
Tengo un fork de openclaw-android en github.com/desarrollo032/openclaw-android.
Es una app Android con terminal embebido (TerminalManager) que instala OpenClaw
(un AI gateway en Node.js) dentro de su sandbox en /data/user/0/com.openclaw.android.debug/files/.

Necesito que corrijas los siguientes archivos aplicando exactamente estos fixes:

ARCHIVO 1: EnvironmentBuilder.kt o TerminalManager.kt
Fix: Usar context.filesDir.absolutePath como base para TODAS las variables de entorno.
No mezclar /data/data/ con /data/user/0/ en el mismo env block.
Reemplazar cualquier hardcode de /data/data/ por val base = context.filesDir.absolutePath.

ARCHIVO 2: app/src/main/assets/install/install.sh
Fix 1: Agregar set +e / set -e alrededor del bloque "Verifying network connectivity"
        para que un curl fallido no mate el proceso completo.
Fix 2: Reemplazar "pkg install nodejs" por descarga directa del binario Node.js
        v22.22.0 linux-arm64 desde nodejs.org/dist.
Fix 3: Redefinir NPM_BIN DESPUÉS de instalar Node.js, no antes.

RESTRICCIONES:
- No cambiar la lógica general del archivo
- Solo aplicar los 3 fixes específicos
- Mostrar el diff completo de cada cambio
- Mantener comentarios existentes

CONTEXTO ADICIONAL:
- Package name del APK: com.openclaw.android.debug
- Node.js correcto: v22.22.0 linux-arm64 glibc (NO el de Termux/Bionic)
- El error principal es: npm install failed after 3 attempts (al 71%)
- Causa raíz: rutas inconsistentes + Phantom Process Killer Android 12+
```

---

## Resumen ejecutable completo

```bash
# ── SISTEMA ──────────────────────────────────────────────
dpkg --configure -a                   # → N si pregunta
apt -f install
termux-change-repo                    # → packages.termux.dev
pkg update -y && pkg upgrade -y

# ── PREPARAR ─────────────────────────────────────────────
termux-setup-storage
rm -rf ~/payload ~/.build-work
mkdir -p ~/payload/{certs,bin,lib/node,patches} ~/.build-work
pkg install -y nodejs xz-utils glibc ca-certificates coreutils curl tar dos2unix

# ── GLIBC ────────────────────────────────────────────────
mkdir -p ~/.build-work/glibc-stage/{lib,etc}
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
          libdl.so.2 librt.so.1 libresolv.so.2 libnss_dns.so.2 \
          libnss_files.so.2 libutil.so.1 libstdc++.so.6 \
          libgcc_s.so.1 libz.so.1 libcrypto.so.3 libssl.so.3; do
    [ -e "$PREFIX/glibc/lib/$f" ] && \
        cp -aL "$PREFIX/glibc/lib/$f" ~/.build-work/glibc-stage/lib/
done
printf "passwd: files\ngroup: files\nhosts: files dns\n" \
    > ~/.build-work/glibc-stage/etc/nsswitch.conf
printf "127.0.0.1 localhost\n::1 localhost\n" \
    > ~/.build-work/glibc-stage/etc/hosts
mkdir -p ~/.build-work/glibc-wrap/glibc
cp -r ~/.build-work/glibc-stage/* ~/.build-work/glibc-wrap/glibc/
cd ~ && tar -cJf ~/payload/glibc-aarch64.tar.xz -C ~/.build-work/glibc-wrap glibc/
xz -t ~/payload/glibc-aarch64.tar.xz   # Verificar

# ── CERTS ────────────────────────────────────────────────
cp $PREFIX/etc/tls/cert.pem ~/payload/certs/

# ── NODE.JS PORTABLE ─────────────────────────────────────
cd ~/.build-work
curl -fLO https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-arm64.tar.xz
tar -xJf node-v22.22.0-linux-arm64.tar.xz
mkdir -p ~/payload/lib/node/bin
mv node-v22.22.0-linux-arm64/bin/node ~/payload/lib/node/bin/node.real
chmod +x ~/payload/lib/node/bin/node.real

# ── OPENCLAW ─────────────────────────────────────────────
mkdir -p ~/.build-work/npm
npm install --prefix ~/.build-work/npm openclaw@latest --ignore-scripts
mkdir -p ~/payload/lib/openclaw
cp -r ~/.build-work/npm/node_modules/openclaw/* ~/payload/lib/openclaw/

# ── PATCH ────────────────────────────────────────────────
echo "'use strict'; delete process.env.LD_PRELOAD;" > ~/payload/patches/glibc-compat.js

# ── EXTRAER GLIBC Y CREAR SYMLINK CRÍTICO ────────────────
cd ~/payload
tar -xJf glibc-aarch64.tar.xz
cd glibc/lib && ln -s libc.so.6 libc.so

# ── CREAR SCRIPTS (ver sección 5) ────────────────────────

# ── VALIDAR ──────────────────────────────────────────────
cd ~/payload
./glibc/lib/ld-linux-aarch64.so.1 --library-path ./glibc/lib \
    ./lib/node/bin/node.real --version
./run-openclaw.sh --help

# ── EMPAQUETAR ───────────────────────────────────────────
cd ~/payload
find . -type f -print0 | xargs -0 sha256sum | sort -k2 > PAYLOAD_CHECKSUM.sha256
cd ~
tar -czf payload-final.tar.gz payload/
cp payload-final.tar.gz ~/storage/downloads/payload.tar.gz
cp install-openclaw.sh ~/storage/downloads/
```

---

*Documento generado el 2 de mayo de 2026*  
*OpenClaw: 2026.4.29 (a448042) | Node.js: v22.22.0 | Android: aarch64*
