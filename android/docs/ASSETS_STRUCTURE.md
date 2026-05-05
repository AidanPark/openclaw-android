# Assets — Estructura de Archivos Comprimidos

Documentación de los dos archivos comprimidos incluidos en el APK como assets.

**Plataforma:** Android arm64 (aarch64)  
**Node.js:** v22.22.0 linux-arm64 (glibc)  
**OpenClaw:** 2026.4.29  
**Ubicación en el proyecto:** `android/app/src/main/assets/`

---

## Índice

1. [payload-final.tar.gz](#1-payload-finaltargz) — payload principal de instalación offline
2. [oa-backup.tar.gz](#2-oa-backuptargz) — backup de recuperación

---

## 1. payload-final.tar.gz

**Tamaño:** ~113 MB  
**Entradas totales:** ~37 000 archivos  
**Propósito:** Payload completo para instalación offline. Se extrae a `filesDir/home/payload/` durante la primera instalación.

### Estructura

```
payload/
├── glibc/
│   ├── lib/                          ← Librerías glibc (ELF binarios reales)
│   │   ├── ld-linux-aarch64.so.1     ← Loader ELF — punto de entrada para node.real
│   │   ├── libc.so                   ← Symlink → libc.so.6 (linker script de texto)
│   │   ├── libc.so.6                 ← Binario ELF real de glibc
│   │   ├── libm.so.6                 ← Matemáticas
│   │   ├── libpthread.so.0           ← Threads POSIX
│   │   ├── libdl.so.2                ← Dynamic linking
│   │   ├── librt.so.1                ← Real-time extensions
│   │   ├── libresolv.so.2            ← DNS resolver
│   │   ├── libnss_dns.so.2           ← NSS: resolución DNS
│   │   ├── libnss_files.so.2         ← NSS: /etc/hosts
│   │   ├── libutil.so.1              ← Utilidades POSIX
│   │   ├── libstdc++.so.6            ← C++ standard library
│   │   ├── libgcc_s.so.1             ← GCC runtime
│   │   ├── libz.so.1                 ← Compresión zlib
│   │   ├── libcrypto.so.3            ← OpenSSL crypto
│   │   └── libssl.so.3               ← OpenSSL TLS
│   └── etc/
│       ├── nsswitch.conf             ← Configuración NSS (hosts: files dns)
│       └── hosts                     ← Hosts mínimo (127.0.0.1 localhost)
│
├── lib/
│   ├── node/
│   │   └── bin/
│   │       └── node.real             ← Binario ELF Node.js v22.22.0 (~120 MB sin comprimir)
│   └── openclaw/
│       ├── openclaw.mjs              ← Punto de entrada principal de OpenClaw
│       ├── package.json              ← Versión: 2026.4.29
│       ├── node_modules/             ← Dependencias npm (~37 000 archivos)
│       ├── dist/                     ← Código compilado y extensiones
│       ├── skills/                   ← Skills incluidos
│       ├── scripts/                  ← Scripts de utilidad
│       └── patches/                  ← Patches de compatibilidad
│
├── certs/
│   └── cert.pem                      ← Bundle de certificados CA para HTTPS
│
├── patches/
│   └── glibc-compat.js               ← Shim de compatibilidad glibc/Android para Node.js
│
└── run-openclaw.sh                   ← Launcher del gateway (usa ld-linux directamente)
```

### Cómo se usa

El payload se extrae con `PayloadExtractor.extractTarGzAsset()` a `filesDir/home/payload/`. Después de la extracción, `InstallerManager.completeInstallation()` ejecuta los pasos de configuración:

1. Verifica `glibc/lib/ld-linux-aarch64.so.1` (debe ser ELF, >100 KB)
2. Repara symlinks de glibc (`libc.so → libc.so.6`)
3. Crea el wrapper `~/.openclaw-android/bin/node` que invoca node.real via el loader
4. Configura DNS (`glibc/etc/resolv.conf`) y SSL (`ssl/cert.pem`)
5. Crea `openclaw-start.sh` que llama a `run-openclaw.sh`
6. Escribe `installed.json` con versiones detectadas

### Cómo se lanza node

```sh
# El wrapper ~/.openclaw-android/bin/node hace esto:
unset LD_PRELOAD
exec /path/to/payload/glibc/lib/ld-linux-aarch64.so.1 \
    --library-path /path/to/payload/glibc/lib \
    /path/to/payload/lib/node/bin/node.real \
    "$@"
```

> **CRÍTICO:** `LD_LIBRARY_PATH` con `glibc/lib` NUNCA debe estar en el entorno de shells Bionic (`/system/bin/sh`, `bash` de Termux). Solo debe existir dentro del proceso `node.real` lanzado via `ld-linux-aarch64.so.1`.

### Notas importantes

| Archivo | Nota |
|---|---|
| `glibc/lib/libc.so` | Es un **symlink** a `libc.so.6`, no un binario. Normal en glibc. |
| `glibc/lib/libc.so.6` | Binario ELF real. Verificar con `xxd`: primeros 4 bytes = `7f 45 4c 46` |
| `lib/node/bin/node.real` | ELF 64-bit LSB ARM aarch64. ~120 MB sin comprimir. |
| `glibc/etc/resolv.conf` | **Excluido del tar** — Android no permite escribirlo. Se crea en runtime. |

---

## 2. oa-backup.tar.gz

**Tamaño:** ~145 MB  
**Propósito:** Backup completo del entorno instalado en Termux. Se usa como fallback si el payload principal falla o para restaurar una instalación previa sin necesidad de internet.

### Estructura

```
oa-backup/
├── openclaw-android.tar.gz           ← Backup del directorio ~/.openclaw-android (~62.8 MB)
├── glibc-esencial.tar.gz             ← (referencia, las libs están sueltas abajo)
├── glibc-libs/                       ← Librerías glibc sueltas (ELF binarios reales)
│   ├── ld-linux-aarch64.so.1         ← Loader ELF
│   ├── libc.so.6                     ← glibc libc
│   ├── libm.so.6
│   ├── libpthread.so.0
│   ├── libdl.so.2
│   ├── librt.so.1
│   ├── libresolv.so.2
│   ├── libstdc++.so.6
│   ├── libgcc_s.so.1
│   ├── libz.so.1
│   ├── libssl.so.3
│   ├── libcrypto.so.3
│   ├── libnss_dns.so.2
│   └── libnss_files.so.2
└── openclaw-modules.tar.gz           ← Backup del paquete npm openclaw (~59.6 MB)
```

### Contenido de openclaw-android.tar.gz (~62.8 MB)

Backup del directorio `~/.openclaw-android/` instalado por el script online:

```
.openclaw-android/
├── bin/
│   ├── node                          ← Wrapper glibc (script shell)
│   ├── npm                           ← Wrapper npm
│   └── npx                           ← Wrapper npx
├── node/
│   └── bin/
│       ├── node.real                 ← Binario ELF Node.js v22.22.0
│       ├── npm
│       ├── npx
│       └── corepack
├── patches/
│   └── glibc-compat.js               ← Shim de compatibilidad
├── scripts/
│   ├── lib.sh
│   ├── setup-env.sh
│   └── backup.sh
├── installer/                        ← Código fuente del instalador
├── platforms/                        ← Configuración por plataforma
├── .glibc-arch                       ← Arquitectura detectada (aarch64)
├── .platform                         ← Plataforma (android)
├── .npm-registry                     ← Registry npm configurado
├── patch.log                         ← Log de patches aplicados
└── uninstall.sh                      ← Script de desinstalación
```

### Contenido de openclaw-modules.tar.gz (~59.6 MB)

Backup del paquete npm `openclaw` instalado globalmente:

```
openclaw/
├── openclaw.mjs                      ← Punto de entrada principal
├── package.json                      ← Versión: 2026.4.29
├── CHANGELOG.md
├── README.md
├── LICENSE
└── node_modules/                     ← Dependencias npm completas
```

### Contenido de glibc-libs/ (librerías sueltas)

Las 14 librerías glibc esenciales copiadas con `cp -L` (binarios reales, sin symlinks). Son los mismos archivos que `payload-final.tar.gz/glibc/lib/` pero sin la estructura de directorios, para restauración directa.

### Cómo se usa el backup

El backup se usa cuando:
- La instalación principal falla (payload corrupto, extracción incompleta)
- Se necesita restaurar sin internet
- Se quiere recuperar una versión conocida-buena del entorno

Flujo de restauración:
```sh
# 1. Extraer backup
tar -xzf oa-backup.tar.gz

# 2. Restaurar ~/.openclaw-android
tar -xzf oa-backup/openclaw-android.tar.gz -C ~

# 3. Restaurar librerías glibc
mkdir -p $PREFIX/glibc/lib
cp oa-backup/glibc-libs/* $PREFIX/glibc/lib/
ln -sf libc.so.6 $PREFIX/glibc/lib/libc.so

# 4. Restaurar módulos openclaw
tar -xzf oa-backup/openclaw-modules.tar.gz -C $PREFIX/lib/node_modules/
```

---

## Comparación de los dos archivos

| Aspecto | payload-final.tar.gz | oa-backup.tar.gz |
|---|---|---|
| **Tamaño** | 113 MB | 145 MB |
| **Propósito** | Instalación offline desde APK | Backup/recuperación |
| **Node.js** | `lib/node/bin/node.real` | `openclaw-android.tar.gz` → `node/bin/node.real` |
| **OpenClaw** | `lib/openclaw/openclaw.mjs` | `openclaw-modules.tar.gz` → `openclaw.mjs` |
| **glibc** | `glibc/lib/*.so` (en estructura) | `glibc-libs/*.so` (sueltos) |
| **Certs SSL** | `certs/cert.pem` | No incluido (se genera en runtime) |
| **Launcher** | `run-openclaw.sh` | Incluido en `openclaw-android.tar.gz` |
| **Extracción destino** | `filesDir/home/payload/` | `filesDir/home/` (restauración manual) |
| **Usado por** | `InstallerManager.installOffline()` | Recuperación manual / fallback |

---

## Verificación de integridad

```sh
# Verificar que libc.so.6 es ELF real (no linker script de texto)
tar -xOf payload-final.tar.gz payload/glibc/lib/libc.so.6 | xxd | head -1
# → 00000000: 7f45 4c46 ...  ← 7f 45 4c 46 = ELF ✔

# Verificar que node.real es ELF
tar -xOf payload-final.tar.gz payload/lib/node/bin/node.real | xxd | head -1
# → 00000000: 7f45 4c46 ...  ← ELF ✔

# Verificar que resolv.conf NO está en el tar (excluido intencionalmente)
tar -tzf payload-final.tar.gz | grep "resolv.conf"
# → sin resultado ✔

# Verificar versión de OpenClaw
tar -xOf payload-final.tar.gz payload/lib/openclaw/package.json | grep '"version"'
# → "version": "2026.4.29"
```

---

*Generado: Mayo 2026 — OpenClaw 2026.4.29 | Node.js v22.22.0 | Android aarch64*
