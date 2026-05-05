# OpenClaw Android — Documentación Final Completa
## Todo el proceso: instalación, payload, integración en APK

**OpenClaw:** 2026.4.29 (a448042)  
**Node.js:** v22.22.0 (linux-arm64 glibc)  
**APK:** com.openclaw.android (0.4.132-DEBUG)  
**Fecha:** Mayo 2026  

---

## Índice

1. [Arquitectura del sistema](#1-arquitectura)
2. [Instalación online en Termux](#2-instalación-online)
3. [Dónde instala todo el script oficial](#3-ubicación-de-archivos)
4. [Construcción del payload offline](#4-construcción-del-payload)
5. [Errores encontrados y correcciones](#5-errores-y-correcciones)
6. [Scripts generados](#6-scripts)
7. [Backup del entorno](#7-backup)
8. [Integración en APK Android](#8-integración-en-apk)
9. [Estado actual y pendientes](#9-estado-actual)
10. [Comandos de referencia rápida](#10-referencia-rápida)

---

## 1. Arquitectura

### Enfoque: glibc sin proot

```
Android Kernel
  └── Bionic libc (Android nativo)
        └── Termux embebido en APK
              └── glibc ld.so (solo el linker)
                    └── Node.js (linux-arm64)
                          └── OpenClaw gateway
```

### Por qué glibc y no Bionic

| Aspecto | Bionic (Android) | glibc (Linux) |
|---|---|---|
| Portable fuera de Termux | ❌ | ✔ |
| Compatible con node.real ELF | ❌ | ✔ |
| Inyecta LD_PRELOAD peligroso | ✔ problema | ❌ |

### Estructura del payload final

```
payload/
├── glibc/
│   ├── lib/
│   │   ├── ld-linux-aarch64.so.1   ← loader ELF
│   │   ├── libc.so.6               ← binario real ELF
│   │   ├── libc.so                 ← symlink a libc.so.6
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
│   ├── node/bin/node.real          ← ELF 120MB linux-arm64
│   └── openclaw/                   ← npm install completo
├── certs/cert.pem
├── patches/glibc-compat.js
└── run-openclaw.sh                 ← launcher con env -i
```

---

## 2. Instalación Online

### Comando

```bash
curl -sL myopenclawhub.com/install | bash
```

### Respuestas durante la instalación

Responder **n** a todas las herramientas opcionales:
```
Install tmux?          → n
Install ttyd?          → n
Install dufs?          → n
Install android-tools? → n
Install Chromium?      → n
Install code-server?   → n
Install OpenCode?      → n
Install Claude Code?   → n
Install Gemini CLI?    → n
Install Codex CLI?     → n
```

Cuando dpkg pregunte por archivos de configuración:
```
*** openssl.cnf (Y/I/N/O/D/Z) [default=N] ? → N
*** sources.list (Y/I/N/O/D/Z) [default=N] ? → N
```

### Si dpkg fue interrumpido

```bash
dpkg --configure -a   # → N cuando pregunte
apt -f install
curl -sL myopenclawhub.com/install | bash
```

### Resultado esperado

```
[PASS] Node.js v22.22.0 (>= 22)
[PASS] npm 10.9.4
[PASS] OA_GLIBC=1
[PASS] glibc dynamic linker (ld-linux-aarch64.so.1)
[PASS] openclaw OpenClaw 2026.4.29 (a448042)
Results: 12 passed, 0 failed, 2 warnings
Installation verification PASSED!
```

---

## 3. Ubicación de Archivos

Después de la instalación, todo queda en:

```
~/.openclaw-android/
├── bin/
│   ├── node        ← wrapper bash que llama al loader glibc
│   ├── npm
│   └── npx
├── node/
│   └── bin/
│       └── node.real  ← binario ELF real (120MB)
├── patches/
│   └── glibc-compat.js
├── .glibc-arch
├── .platform
└── .npm-registry

$PREFIX/glibc/lib/
├── ld-linux-aarch64.so.1  ← loader
├── libc.so.6              ← binario ELF real
├── libc.so                ← linker script texto (NORMAL en glibc)
└── ...otras libs

$PREFIX/lib/node_modules/openclaw/  ← OpenClaw completo
```

### El wrapper node (cómo funciona)

```bash
cat ~/.openclaw-android/bin/node
# Hace esto:
unset LD_PRELOAD
exec "/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1" \
    --library-path "/data/data/com.termux/files/usr/glibc/lib" \
    "/data/data/com.termux/files/home/.openclaw-android/node/bin/node.real" \
    "$@"
```

### Variables de entorno configuradas en ~/.bashrc

```bash
export PATH="$HOME/.openclaw-android/bin:$HOME/.openclaw-android/node/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export OA_GLIBC=1
export CONTAINER=1
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
```

---

## 4. Construcción del Payload

### IMPORTANTE: libc.so es texto — es NORMAL

`$PREFIX/glibc/lib/libc.so` es un **linker script de texto**, no un binario. Es el comportamiento estándar de glibc. El binario real es `libc.so.6`. Por eso se usa `cp -L` (dereference) al copiar.

### Paso a paso

```bash
# Limpiar
rm -rf ~/payload ~/payload-final.tar.gz

# Crear estructura
mkdir -p ~/payload/{glibc/lib,glibc/etc,lib/node/bin,lib/openclaw,certs,patches}

# 1. Copiar librerías glibc con -L (copia binarios reales, no symlinks)
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
          libdl.so.2 librt.so.1 libresolv.so.2 libnss_dns.so.2 \
          libnss_files.so.2 libutil.so.1 libstdc++.so.6 \
          libgcc_s.so.1 libz.so.1 libcrypto.so.3 libssl.so.3; do
    cp -L $PREFIX/glibc/lib/$f ~/payload/glibc/lib/ && \
        echo "OK: $f" || echo "FALTA: $f"
done

# Crear symlink libc.so → libc.so.6
cd ~/payload/glibc/lib
ln -sf libc.so.6 libc.so

# etc mínimo
printf "passwd: files\ngroup: files\nhosts: files dns\n" \
    > ~/payload/glibc/etc/nsswitch.conf
printf "127.0.0.1 localhost\n::1 localhost\n" \
    > ~/payload/glibc/etc/hosts

# 2. Copiar node.real (binario ELF de 120MB)
cp ~/.openclaw-android/node/bin/node.real ~/payload/lib/node/bin/node.real
chmod +x ~/payload/lib/node/bin/node.real

# 3. Copiar OpenClaw completo
cp -r $PREFIX/lib/node_modules/openclaw/* ~/payload/lib/openclaw/

# 4. Patches
cp ~/.openclaw-android/patches/glibc-compat.js ~/payload/patches/

# 5. Certificados
cp $PREFIX/etc/tls/cert.pem ~/payload/certs/

# Verificar ELF
file ~/payload/glibc/lib/libc.so.6
file ~/payload/glibc/lib/ld-linux-aarch64.so.1
file ~/payload/lib/node/bin/node.real
# Todos deben mostrar: ELF 64-bit LSB ... ARM aarch64
```

---

## 5. Errores y Correcciones

### Error 1 — `bad ELF magic: 2f2a2047`

**Qué es:** `2f2a2047` en ASCII = `/*G` = inicio de comentario JavaScript/shell. El archivo es texto, no binario.

**Causa:** `libc.so` fue copiado como symlink (que apunta al linker script de texto) en lugar del binario real.

**Corrección:** Usar `cp -L` para copiar el binario real:
```bash
cp -L $PREFIX/glibc/lib/libc.so.6 ~/payload/glibc/lib/
```

**Verificar:**
```bash
xxd ~/payload/glibc/lib/libc.so.6 | head -1
# → 7f 45 4c 46 = ELF ✔
```

---

### Error 2 — `EACCES (Permission denied)` en resolv.conf

**Qué es:** La app intenta extraer `glibc/etc/resolv.conf` pero Android no permite escribirlo.

**Corrección:** Excluirlo al empaquetar:
```bash
tar --dereference \
    --exclude='payload/glibc/etc/resolv.conf' \
    -czf payload-final.tar.gz payload/
```

---

### Error 3 — `cannot find "libc.so" from verneed[0] in DT_NEEDED`

**Qué es:** La app usa `/system/bin/sh` para verificar el entorno, pero `sh` de Android Bionic no puede cargar `libc.so` de glibc.

**Causa:** El código de verificación en la app usa `/system/bin/sh` en lugar del loader glibc.

**Corrección en Kotlin:**
```kotlin
// MAL
Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "node --version"))

// BIEN — usar el loader glibc directamente
val payloadDir = "${context.filesDir.absolutePath}/payload"
Runtime.getRuntime().exec(arrayOf(
    "$payloadDir/glibc/lib/ld-linux-aarch64.so.1",
    "--library-path", "$payloadDir/glibc/lib",
    "$payloadDir/lib/node/bin/node.real",
    "--version"
))
```

---

### Error 4 — `env: 'node': No such file or directory`

**Causa:** Nueva sesión de Termux no carga `~/.bashrc`.

**Corrección:**
```bash
source ~/.bashrc
# o
export PATH="$PREFIX/glibc/bin:$HOME/.openclaw-android/bin:$PATH"
```

---

### Error 5 — Rutas inconsistentes en APK (`/data/data/` vs `/data/user/0/`)

**Causa:** El `EnvironmentBuilder` hardcodeaba `/data/data/` para PREFIX pero usaba `/data/user/0/` para HOME.

**Corrección en Kotlin:**
```kotlin
// MAL
val prefix = "/data/data/$packageName/files/usr"
val home   = "/data/user/0/$packageName/files/home"

// BIEN — siempre context.filesDir
val base   = context.filesDir.absolutePath
val prefix = "$base/usr"
val home   = "$base/home"
val tmpdir = "$base/tmp"
```

---

## 6. Scripts

### run-openclaw.sh (launcher principal)

```bash
cat > ~/payload/run-openclaw.sh << 'EOF'
#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
LOADER="$DIR/glibc/lib/ld-linux-aarch64.so.1"
LIBS="$DIR/glibc/lib"
NODE="$DIR/lib/node/bin/node.real"
APP="$DIR/lib/openclaw/openclaw.mjs"
unset LD_PRELOAD
exec env -i \
  HOME="$HOME" \
  TMPDIR="/data/local/tmp" \
  PATH="/system/bin:/bin" \
  LD_LIBRARY_PATH="$LIBS" \
  OA_GLIBC=1 \
  CONTAINER=1 \
  SSL_CERT_FILE="$DIR/certs/cert.pem" \
  "$LOADER" --library-path "$LIBS" "$NODE" "$APP" "$@"
EOF
chmod +x ~/payload/run-openclaw.sh
```

**Prueba:**
```bash
cd ~/payload
./run-openclaw.sh --version
# → OpenClaw 2026.4.29 (a448042) ✔
```

---

## 7. Backup

### Crear backup del entorno instalado

```bash
mkdir -p ~/oa-backup

# Backup de ~/.openclaw-android (wrapper, node.real, patches)
tar --dereference -czf ~/oa-backup/openclaw-android.tar.gz \
    -C ~ .openclaw-android/

# Backup de librerías glibc esenciales (binarios reales con -L)
mkdir -p ~/oa-backup/glibc-libs
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
          libdl.so.2 librt.so.1 libresolv.so.2 libnss_dns.so.2 \
          libnss_files.so.2 libutil.so.1 libstdc++.so.6 \
          libgcc_s.so.1 libz.so.1 libcrypto.so.3 libssl.so.3; do
    cp -L $PREFIX/glibc/lib/$f ~/oa-backup/glibc-libs/
done
tar -czf ~/oa-backup/glibc-esencial.tar.gz -C ~/oa-backup glibc-libs/

# Backup de OpenClaw
tar --dereference -czf ~/oa-backup/openclaw-modules.tar.gz \
    -C $PREFIX/lib/node_modules openclaw/

# Comprimir todo
cd ~
tar -czf oa-backup.tar.gz oa-backup/

# Mover a Downloads
cp ~/oa-backup.tar.gz ~/storage/downloads/
```

### Tamaños de referencia

| Componente | Tamaño |
|---|---|
| ~/.openclaw-android/ | ~213MB |
| $PREFIX/glibc/ | ~396MB |
| $PREFIX/lib/node_modules/openclaw/ | ~341MB |
| payload-final.tar.gz | **114MB** |

---

## 8. Integración en APK

### Assets necesarios

```
app/src/main/assets/
├── payload-final.tar.gz    ← 114MB, payload completo
└── install-online.sh       ← fallback con internet
```

### Extraer payload (Kotlin)

```kotlin
fun extractPayload(context: Context) {
    val filesDir = context.filesDir.absolutePath
    val process = Runtime.getRuntime().exec(arrayOf(
        "sh", "-c",
        "cd $filesDir && " +
        "tar -xzf payload-final.tar.gz && " +
        "chmod -R 755 payload/ && " +
        "chmod 755 payload/glibc/lib/ld-linux-aarch64.so.1 && " +
        "chmod 755 payload/lib/node/bin/node.real && " +
        "chmod 755 payload/run-openclaw.sh"
    ))
    process.waitFor()
}
```

### Ejecutar OpenClaw (Kotlin)

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
        env.remove("LD_PRELOAD")   // CRÍTICO
        env["OA_GLIBC"] = "1"
        env["CONTAINER"] = "1"
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = "/data/local/tmp"
        env["SSL_CERT_FILE"] = "$base/certs/cert.pem"
    }
    return pb.start()
}
```

### Verificar entorno (Kotlin) — sin usar /system/bin/sh

```kotlin
fun checkNodeVersion(context: Context): String {
    val base = "${context.filesDir.absolutePath}/payload"
    val process = ProcessBuilder(
        "$base/glibc/lib/ld-linux-aarch64.so.1",
        "--library-path", "$base/glibc/lib",
        "$base/lib/node/bin/node.real",
        "--version"
    ).also { pb ->
        pb.environment()["LD_LIBRARY_PATH"] = "$base/glibc/lib"
        pb.environment().remove("LD_PRELOAD")
    }.start()
    return process.inputStream.bufferedReader().readLine() ?: "error"
}
```

### EnvironmentBuilder — rutas siempre consistentes

```kotlin
// SIEMPRE usar context.filesDir — funciona en debug y release
val base   = context.filesDir.absolutePath
val prefix = "$base/usr"    // Termux embebido
val home   = "$base/home"
val tmpdir = "$base/tmp"
val payload = "$base/payload"

// Package name dinámico — no hardcodear
// com.openclaw.android        → producción
// com.openclaw.android.debug  → desarrollo
// context.filesDir lo resuelve automáticamente
```

### Modo híbrido offline/online

```kotlin
fun setupOpenClaw(context: Context) {
    val payloadDir = File(context.filesDir, "payload")
    val prefs = context.getSharedPreferences("openclaw", Context.MODE_PRIVATE)

    when {
        // Ya instalado
        prefs.getBoolean("installed", false) &&
        File(payloadDir, "lib/openclaw/openclaw.mjs").exists() -> {
            startOpenClaw(context)
        }
        // Modo offline: payload en assets
        hasAsset(context, "payload-final.tar.gz") -> {
            copyAsset(context, "payload-final.tar.gz")
            extractPayload(context)
            prefs.edit().putBoolean("installed", true).apply()
            startOpenClaw(context)
        }
        // Modo online: fallback curl
        else -> {
            installOnline(context)
        }
    }
}
```

---

## 9. Estado Actual

### ✔ Funcionando

- Instalación online con `curl -sL myopenclawhub.com/install | bash`
- OpenClaw 2026.4.29 corriendo en Termux
- Node.js, git, openclaw detectados en el panel
- Gateway activo (imagen 3 — "Activa")
- payload-final.tar.gz (114MB) con ELF válidos
- Backup completo en Downloads

### ⚠ Errores de diagnóstico (no bloquean)

Los errores `cannot find "libc.so" from verneed[0]` en Ajustes son porque la app verifica el entorno con `/system/bin/sh` en lugar del loader glibc. OpenClaw sigue funcionando.

**Pendiente corregir en el código de la app:**
- Reemplazar verificaciones con `/system/bin/sh` por el loader glibc directo
- Ver sección 8 — "Verificar entorno (Kotlin)"

### Archivos en Downloads

```
~/storage/downloads/
├── payload-final.tar.gz   ← 114MB, payload listo para app
└── oa-backup.tar.gz       ← backup completo del entorno
```

---

## 10. Referencia Rápida

### Reconstruir payload desde cero

```bash
rm -rf ~/payload ~/payload-final.tar.gz
mkdir -p ~/payload/{glibc/lib,glibc/etc,lib/node/bin,lib/openclaw,certs,patches}
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 \
          libdl.so.2 librt.so.1 libresolv.so.2 libnss_dns.so.2 \
          libnss_files.so.2 libutil.so.1 libstdc++.so.6 \
          libgcc_s.so.1 libz.so.1 libcrypto.so.3 libssl.so.3; do
    cp -L $PREFIX/glibc/lib/$f ~/payload/glibc/lib/
done
cd ~/payload/glibc/lib && ln -sf libc.so.6 libc.so
printf "passwd: files\ngroup: files\nhosts: files dns\n" > ~/payload/glibc/etc/nsswitch.conf
printf "127.0.0.1 localhost\n::1 localhost\n" > ~/payload/glibc/etc/hosts
cp ~/.openclaw-android/node/bin/node.real ~/payload/lib/node/bin/node.real
chmod +x ~/payload/lib/node/bin/node.real
cp -r $PREFIX/lib/node_modules/openclaw/* ~/payload/lib/openclaw/
cp ~/.openclaw-android/patches/glibc-compat.js ~/payload/patches/
cp $PREFIX/etc/tls/cert.pem ~/payload/certs/
cd ~ && tar --dereference --exclude='payload/glibc/etc/resolv.conf' \
    -czf payload-final.tar.gz payload/
cp payload-final.tar.gz ~/storage/downloads/
```

### Verificar payload

```bash
# libc.so.6 es ELF real
tar -xOf ~/payload-final.tar.gz payload/glibc/lib/libc.so.6 | xxd | head -1
# → 7f 45 4c 46 ✔

# resolv.conf excluido
tar -tzf ~/payload-final.tar.gz | grep "resolv.conf"
# → sin resultado ✔

# Probar ejecución
cd ~/payload && ./run-openclaw.sh --version
# → OpenClaw 2026.4.29 ✔
```

### Actualizar OpenClaw en el payload

```bash
# Instalar nueva versión
npm install -g openclaw@latest --ignore-scripts
source ~/.bashrc

# Reemplazar en payload
rm -rf ~/payload/lib/openclaw
cp -r $PREFIX/lib/node_modules/openclaw/* ~/payload/lib/openclaw/

# Reempaquetar
cd ~
tar --dereference --exclude='payload/glibc/etc/resolv.conf' \
    -czf payload-final.tar.gz payload/
cp payload-final.tar.gz ~/storage/downloads/
```

### Reinstalar OpenClaw en Termux

```bash
dpkg --configure -a           # Si hubo problemas previos
pkg update -y && pkg upgrade -y
curl -sL myopenclawhub.com/install | bash
source ~/.bashrc
openclaw --version
```

---

*Documentación generada el 3 de mayo de 2026*  
*OpenClaw 2026.4.29 | Node.js v22.22.0 | Android aarch64 | APK 0.4.132-DEBUG*
