# Variables de Entorno — Referencia

## El Problema de las Rutas Hardcodeadas

Los binarios del bootstrap de Termux tienen rutas compiladas que apuntan a `/data/data/com.termux/files/usr/`. Cuando la app corre como `com.openclaw.android`, el sandbox real está en `/data/data/com.openclaw.android/files/usr/`.

Sin las variables de entorno correctas:
- `apt` intenta abrir lock files en `/data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend` → **Permission denied**
- `dpkg` busca su base de datos en la ruta de Termux → **No such file**
- `bash` no encuentra binarios en `$PATH` → **command not found**

## Variables Inyectadas por `TerminalManager.buildEnvBlock()`

Estas variables se escriben en `/tmp/oca-env.sh` y se sourcean antes de cualquier comando en el terminal.

### Rutas Base

```bash
HOME=/data/user/0/com.openclaw.android/files/home
PREFIX=/data/user/0/com.openclaw.android/files/usr
TMPDIR=/data/user/0/com.openclaw.android/files/tmp
APP_FILES_DIR=/data/user/0/com.openclaw.android/files
APP_PACKAGE=com.openclaw.android
```

### PATH

```bash
PATH=$HOME/.openclaw-android/bin:$HOME/.openclaw-android/node/bin:$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/bin
```

Orden de prioridad:
1. `.openclaw-android/bin/` — wrappers glibc (node, npm, openclaw)
2. `.openclaw-android/node/bin/` — node de instalación online
3. `$PREFIX/bin/` — binarios del bootstrap (bash, apt, pkg, git...)
4. `$PREFIX/bin/applets/` — applets de busybox
5. `/system/bin:/bin` — sistema Android

### dpkg y apt

```bash
DPKG_ADMINDIR=$PREFIX/var/lib/dpkg
DPKG_ROOT=$PREFIX
APT_CONFIG=$PREFIX/etc/apt/apt.conf
DEBIAN_FRONTEND=noninteractive
```

**Por qué son críticas**: Sin `DPKG_ADMINDIR` y `DPKG_ROOT`, dpkg usa sus rutas compiladas de Termux. Con estas variables, dpkg crea lock files en `$PREFIX/var/lib/dpkg/lock-frontend` (accesible) en lugar de `/data/data/com.termux/...` (inaccesible).

### npm

```bash
NPM_CONFIG_PREFIX=$PREFIX
npm_config_prefix=$PREFIX
```

Evita que npm instale paquetes globales en `/data/data/com.termux/files/usr/`.

### SSL y Red

```bash
SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem
CURL_CA_BUNDLE=$PREFIX/etc/tls/cert.pem
GIT_SSL_CAINFO=$PREFIX/etc/tls/cert.pem
RESOLV_CONF=$PREFIX/etc/resolv.conf
```

### Git

```bash
GIT_EXEC_PATH=$PREFIX/libexec/git-core
GIT_TEMPLATE_DIR=$PREFIX/share/git-core/templates
GIT_CONFIG_NOSYSTEM=1
```

### OpenClaw

```bash
OA_GLIBC=1
CONTAINER=1
CLAWDHUB_WORKDIR=$HOME/.openclaw/workspace
```

### Compatibilidad Termux

```bash
TERMUX__PREFIX=$PREFIX
TERMUX_PREFIX=$PREFIX
TERMUX__ROOTFS=$APP_FILES_DIR
```

Algunos scripts de Termux leen estas variables en lugar de usar rutas hardcodeadas.

### Supresión de Bash Startup

```bash
BASH_ENV=/dev/null
ENV=/dev/null
```

Evita que bash ejecute `.bashrc` o `.profile` al iniciarse, lo que podría sobreescribir las variables inyectadas.

### LD_LIBRARY_PATH — Regla Crítica

```bash
LD_LIBRARY_PATH=$PREFIX/lib
# NO incluir $PREFIX/glibc/lib aquí
```

**Por qué NO incluir `glibc/lib`:**

```
/system/bin/sh y $PREFIX/bin/bash son binarios Bionic (libc de Android).
Si glibc/lib está en LD_LIBRARY_PATH:
  → El linker Bionic encuentra glibc's libc.so.6
  → Error fatal: "CANNOT LINK EXECUTABLE: cannot find libc.so from verneed[0]"
  → El shell no arranca

glibc/lib se agrega SOLO en el wrapper de node:
  exec ld-linux-aarch64.so.1 --library-path glibc/lib node.real "$@"
```

### unset LD_PRELOAD

```bash
unset LD_PRELOAD
```

Elimina `libtermux-exec.so` si estaba en `LD_PRELOAD`. Este `.so` intercepta `exec()` para redirigir rutas de Termux, pero causa crashes en procesos glibc.

---

## Variables por Contexto de Ejecución

### En el terminal embebido (TerminalSessionManager)

**Modo proot:**
```bash
HOME=...
TMPDIR=...
TERM=xterm-256color
LANG=en_US.UTF-8
PROOT_NO_SECCOMP=1
PROOT_TMP_DIR=...
```

**Modo online (bootstrap instalado):**
```bash
HOME=...
PREFIX=.../usr
TMPDIR=...
PATH=$ocaBin:$nodeDir:$PREFIX/bin:...
LD_LIBRARY_PATH=$PREFIX/lib:$PREFIX/glibc/lib
SSL_CERT_FILE=...
OA_GLIBC=1
CONTAINER=1
```

**Modo payload/fallback:**
```bash
# Generado por EnvironmentBuilder.buildEnvironment()
# Incluye todas las variables del buildEnvBlock() más
# detección dinámica de rutas del payload
```

### En ProcessBuilder (TermuxBootstrapManager)

```bash
HOME=...
PREFIX=...
TMPDIR=...
PATH=$PREFIX/bin:/system/bin:/bin
LANG=en_US.UTF-8
TERM=xterm-256color
ANDROID_DATA=/data
ANDROID_ROOT=/system
# + DEBIAN_FRONTEND=noninteractive cuando se ejecuta dpkg/apt
```

---

## Archivo `oca-env.sh`

Generado en cada llamada a `TerminalManager.writeEnvFile()`:

- **Ruta**: `$TMPDIR/oca-env.sh` → `/data/user/0/com.openclaw.android/files/tmp/oca-env.sh`
- **Permisos**: readable (no ejecutable — se sourcea con `. `)
- **Regenerado**: en cada llamada, siempre actualizado
- **Uso**: `. "$envFile"\n` escrito en la sesión del terminal

Sourcear un archivo es más confiable que inyectar líneas individuales porque el shell lo procesa como una lectura atómica, evitando condiciones de carrera cuando el shell aún está inicializando.
