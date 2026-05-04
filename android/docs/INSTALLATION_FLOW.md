# Flujo de Instalación — OpenClaw Android

## Visión General

La instalación de OpenClaw Android tiene tres capas independientes que se ejecutan en orden:

```
┌─────────────────────────────────────────────────────────────────────┐
│  CAPA 1 — Termux Bootstrap                                          │
│  Instala: bash, dpkg, apt, pkg, curl, wget                          │
│  Fuente:  packages.termux.dev/bootstrap/bootstrap-<arch>.zip        │
│  Tamaño:  ~50MB descarga / ~150MB instalado                         │
├─────────────────────────────────────────────────────────────────────┤
│  CAPA 2 — OpenClaw (payload.tar.gz)                                 │
│  Instala: Node.js, glibc, openclaw.mjs                              │
│  Fuente:  assets/payload.tar.gz (bundled en APK, NO en Git)         │
│  Tamaño:  ~118MB en APK / ~400MB instalado                          │
├─────────────────────────────────────────────────────────────────────┤
│  CAPA 3 — Instalación Online                                        │
│  Instala/actualiza: OpenClaw desde internet                         │
│  Comando: curl -sL myopenclawhub.com/install | bash                 │
│  Requiere: Capa 1 instalada (bash, curl, apt disponibles)           │
└─────────────────────────────────────────────────────────────────────┘
```

**Regla fundamental**: La Capa 1 siempre va primero. Sin bash y curl del bootstrap, la instalación online no puede ejecutarse.

---

## Modos de Instalación

### Modo `auto` — APK con payload bundled

```
Usuario abre la app por primera vez
         │
         ▼
InstallerManager.install("auto")
         │
         ├─► PASO 1: TermuxBootstrapManager.install()
         │     Descarga bootstrap-aarch64.zip (~50MB)
         │     Extrae: bash, dpkg, apt, pkg, curl, wget
         │     Ejecuta: dpkg --configure -a (no-interactivo)
         │     Ejecuta: pkg update (hasta 3 reintentos)
         │     Instala: git, curl, wget
         │     Escribe: .termux-bootstrap-installed
         │
         └─► PASO 2: installOffline()  [si hasPayloadAsset() == true]
               Copia payload.tar.gz de assets a homeDir/
               Extrae OpenClaw encima del bootstrap
               Configura: glibc, node wrappers, SSL, DNS
               Valida: InstallValidator.validatePayload()
               Escribe: .installed
         │
         ▼
    Terminal listo
    Shell: bash de Termux
    pkg install funciona ✓
```

### Modo `online` — APK sin payload bundled

```
Usuario elige instalación online
         │
         ▼
MainActivity.startInstallFromUi("online")
         │
         ├─► PASO 1: InstallOverlayController.runInstall("online")
         │     InstallerManager.install("online")
         │       └─► TermuxBootstrapManager.install()
         │             [igual que modo auto, paso 1]
         │
         └─► PASO 2: MainActivity.runOnlineInstallInTerminal()
               showTerminal()
               sessionManager.activeSession ?: createSession()
               TerminalManager.runOnlineInstall(session)
                 │
                 ├─ Inyecta entorno: . /tmp/oca-env.sh
                 │
                 ├─ Intento 1:
                 │    curl -sL myopenclawhub.com/install | bash
                 │    ¿Éxito? → source ~/.bashrc → FIN
                 │
                 ├─ Si falla:
                 │    yes N | dpkg --configure -a --force-confold
                 │    [responde N al prompt de sources.list]
                 │
                 └─ Intento 2:
                      curl -sL myopenclawhub.com/install | bash
                      source ~/.bashrc
         │
         ▼
    Terminal abierto
    Usuario ve la salida en tiempo real
    OpenClaw instalado desde internet
```

### Modo `termux-bootstrap` — Solo bootstrap

```
InstallerManager.install("termux-bootstrap")
         │
         ▼
TermuxBootstrapManager.install()
    [solo Capa 1, sin payload ni online]
         │
         ▼
    Terminal con Termux completo
    dpkg, apt, pkg disponibles
    Sin OpenClaw (instalar manualmente)
```

### Modo `proot` — Ubuntu completo (avanzado)

```
InstallerManager.install("proot")
         │
         ▼
SetupManager.install()
    │
    ├─► ProotManager.downloadProot()
    │     Descarga proot_5.4.0_aarch64.deb (~1.5MB)
    │     Extrae binario proot de data.tar.xz
    │
    ├─► ProotManager.downloadAndExtractRootfs()
    │     Descarga ubuntu-aarch64-pd-v4.22.0.tar.xz (~80MB)
    │     Extrae rootfs Ubuntu a filesDir/ubuntu-rootfs/
    │
    ├─► runUbuntuSetup()
    │     apt-get update
    │     apt-get install ca-certificates curl wget
    │
    ├─► installNodeInProot()
    │     Estrategia 1: NodeSource (curl | bash → apt install nodejs)
    │     Estrategia 2: Binario oficial nodejs.org (fallback)
    │
    ├─► installOpenClawInProot()
    │     npm install -g openclaw@latest
    │     Hasta 3 reintentos con limpieza de caché
    │
    └─► createLaunchScripts()
          Escribe: home/openclaw-start.sh (gateway via proot)
          Escribe: home/openclaw-shell.sh (shell interactivo)
          Escribe: .proot-installed
         │
         ▼
    Terminal: openclaw-shell.sh
    Shell: bash de Ubuntu (dentro de proot)
    apt funciona (Ubuntu apt, no Termux pkg)
    Resistente a Phantom Process Killer ✓
```

---

## Detección de Estado

`InstallerManager.isInstalled()` verifica en este orden:

```
1. ¿Proot instalado?
   .proot-installed existe
   AND ProotManager.isProotReady()
   AND ProotManager.isRootfsReady()
   AND SetupManager.isOpenClawInstalledInRootfs()
   → ubuntu-rootfs/usr/local/lib/node_modules/openclaw/openclaw.mjs

2. ¿Instalación online presente?
   home/.openclaw-android/node/bin/node.real  (>1MB)
   AND home/.openclaw-android/installed.json
   AND usr/lib/node_modules/openclaw/openclaw.mjs
   AND usr/glibc/lib/ld-linux-aarch64.so.1

3. ¿Instalación legada (payload)?
   prefix/ es directorio
   AND .installed existe
```

`InstallerManager.isReady()` — entorno completamente funcional:

```
- proot:   SetupManager.isOpenClawInstalledInRootfs()
- online:  isOnlineInstallPresent()
- payload: InstallValidator.isStructurallyComplete(prefix)
             ✓ glibc/lib/ld-linux-aarch64.so.1 existe y >100KB
             ✓ node binary existe y >1MB
             ✓ openclaw.mjs existe
             ✓ /system/bin/sh disponible
```

---

## Selección de Shell en el Terminal

`TerminalSessionManager.createSession()` detecta automáticamente:

```
¿.proot-installed existe AND openclaw-shell.sh existe?
    → Shell: home/openclaw-shell.sh
      Entorno: proot + Ubuntu rootfs
      apt funciona (Ubuntu)

¿usr/bin/bash existe AND .openclaw-android/installed.json existe
  AND node.real existe AND glibc/ld-linux existe AND openclaw.mjs existe?
    → Shell: usr/bin/bash
      Entorno: Termux bootstrap + glibc
      pkg funciona (Termux)

¿payload instalado?
    → Shell: bash/sh del payload
      Entorno: EnvironmentBuilder.buildEnvironment()
      glibc-wrapped

Fallback:
    → Shell: /system/bin/sh
      Entorno: básico (sin glibc)
      ⚠ No puede ejecutar binarios glibc
```

---

## Manejo del Prompt Interactivo de dpkg

### El problema

Al configurar apt por primera vez, dpkg detecta que `sources.list` fue modificado y pregunta:

```
Configuration file '/data/data/com.termux/files/usr/etc/apt/sources.list'
==> File on system created by you or by a script.
==> File also in package provided by package maintainer.
What would you like to do about it ?
*** sources.list (Y/I/N/O/D/Z) [default=N] ?
```

Sin respuesta, el proceso se cuelga indefinidamente.

### La solución — tres capas de protección

**Capa 1: Variable de entorno**
```bash
DEBIAN_FRONTEND=noninteractive
# Suprime la mayoría de prompts de debconf
```

**Capa 2: Flag de dpkg**
```bash
dpkg --configure -a --force-confold
# --force-confold: mantener archivos de config existentes sin preguntar
```

**Capa 3: Respuesta automática por stdin**
```bash
yes N | dpkg --configure -a --force-confold
# "yes N" envía "N\n" continuamente
# N = "keep your currently-installed version" (correcto para Termux)
```

### Dónde se aplica

| Clase | Método | Protección |
|-------|--------|------------|
| `TermuxBootstrapManager` | `runDpkgConfigure()` | Capa 1 + 2 + stdin N |
| `TermuxBootstrapManager` | `runSinglePkgUpdate()` | Capa 1 + 2 + `yes N \|` |
| `TermuxBootstrapManager` | `installAdditionalPackages()` | Capa 1 + 2 + `yes N \|` |
| `TerminalManager` | `buildOnlineInstallScript()` | Capa 1 + 2 + `yes N \|` |

### Flujo de reintentos en pkg update

```
runPkgUpdateWithRetry(maxAttempts = 3)
    │
    ├─ Intento 1: runSinglePkgUpdate()
    │    yes N | pkg update -y -o Dpkg::Options::="--force-confold"
    │    ¿Éxito? → return true
    │
    ├─ Falla → runDpkgConfigure()  [repara estado inconsistente]
    │
    ├─ Intento 2: runSinglePkgUpdate()
    │    ¿Éxito? → return true
    │
    ├─ Falla → runDpkgConfigure()
    │
    └─ Intento 3: runSinglePkgUpdate()
         ¿Éxito? → return true
         Falla → return false (continúa instalación con advertencia)
```

---

## Variables de Entorno Críticas

`TerminalManager.buildEnvBlock()` inyecta estas variables antes de cualquier comando:

```bash
# Rutas del sandbox de la app
HOME=/data/user/0/com.openclaw.android/files/home
PREFIX=/data/user/0/com.openclaw.android/files/usr
TMPDIR=/data/user/0/com.openclaw.android/files/tmp

# CRÍTICO: dpkg/apt usan estas rutas para lock files y base de datos
# Sin esto, buscan en /data/data/com.termux/... (inaccesible)
DPKG_ADMINDIR=$PREFIX/var/lib/dpkg
DPKG_ROOT=$PREFIX
APT_CONFIG=$PREFIX/etc/apt/apt.conf

# Evitar prompts interactivos
DEBIAN_FRONTEND=noninteractive

# PATH: bin de la app primero, luego sistema
PATH=$HOME/.openclaw-android/bin:$PREFIX/bin:/system/bin:/bin

# SSL
SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem
CURL_CA_BUNDLE=$PREFIX/etc/tls/cert.pem

# IMPORTANTE: NO incluir glibc/lib aquí
# LD_LIBRARY_PATH=$PREFIX/lib  ← solo librerías Bionic
# glibc/lib se agrega SOLO en el wrapper de node
```

**Por qué NO incluir `glibc/lib` en `LD_LIBRARY_PATH` para el shell:**

```
/system/bin/sh es Bionic (libc de Android)
Si glibc/lib está en LD_LIBRARY_PATH:
  → Bionic encuentra glibc's libc.so
  → Error: "CANNOT LINK EXECUTABLE: cannot find libc.so from verneed[0]"
  → El shell no arranca

Solución: glibc/lib solo en el wrapper de node:
  exec ld-linux-aarch64.so.1 --library-path glibc/lib node.real "$@"
```

---

## Estructura de Archivos Post-Instalación

```
filesDir/  (/data/user/0/com.openclaw.android/files/)
│
├── .installed                          ← Marcador instalación payload
├── .proot-installed                    ← Marcador instalación proot
├── .termux-bootstrap-installed         ← Marcador bootstrap Termux
│
├── usr/                                ← PREFIX
│   ├── bin/
│   │   ├── bash, sh → bash             ← Shell principal
│   │   ├── dpkg                        ← Gestor de paquetes
│   │   ├── apt, apt-get → apt          ← APT
│   │   ├── pkg                         ← Wrapper Termux de apt
│   │   ├── curl, wget, git             ← Herramientas de red
│   │   └── openclaw                    ← Wrapper de lanzamiento
│   ├── lib/
│   │   ├── node/bin/node.real          ← Node.js ELF (~120MB)
│   │   ├── openclaw/openclaw.mjs       ← Entry point OpenClaw
│   │   └── node_modules/
│   ├── glibc/
│   │   ├── lib/ld-linux-aarch64.so.1   ← Linker dinámico glibc
│   │   ├── lib/libc.so.6               ← libc glibc
│   │   └── etc/resolv.conf
│   ├── etc/
│   │   ├── tls/cert.pem                ← Certificados SSL
│   │   ├── resolv.conf                 ← DNS
│   │   └── apt/
│   │       ├── sources.list            ← Repositorios Termux
│   │       └── apt.conf
│   └── var/lib/dpkg/                   ← Base de datos dpkg
│
├── home/
│   ├── .openclaw-android/
│   │   ├── bin/
│   │   │   ├── node                    ← Wrapper glibc para node
│   │   │   ├── npm, npx
│   │   │   └── openclaw
│   │   ├── node/bin/node.real          ← Node (instalación online)
│   │   ├── patches/glibc-compat.js     ← Shim de compatibilidad
│   │   └── installed.json              ← Marcador instalación online
│   ├── openclaw-start.sh               ← Lanza gateway OpenClaw
│   ├── openclaw-shell.sh               ← Shell interactivo (proot)
│   └── .bashrc
│
├── tmp/
│   └── oca-env.sh                      ← Variables de entorno (generado)
│
└── ubuntu-rootfs/                      ← Solo modo proot
    ├── bin/, usr/, etc/
    └── root/
```

---

## Flujo Completo — Diagrama de Clases

```
WebView (JS)
    │  window.OpenClaw.startSetup("online")
    ▼
JsBridge.startSetup()
    │
    ▼
SetupBridge.startSetup(mode)
    │  activity.startInstallFromUi(mode)
    ▼
MainActivity.startInstallFromUi(mode)
    │
    ├─ mode == "online" ──────────────────────────────────────────────┐
    │                                                                 │
    │  InstallOverlayController.show()                                │
    │  InstallOverlayController.runInstall("online")                  │
    │    InstallerManager.install("online")                           │
    │      TermuxBootstrapManager.install()  [Capa 1]                 │
    │    InstallOverlayController.hide()                              │
    │  MainActivity.runOnlineInstallInTerminal()  ◄────────────────── ┘
    │    showTerminal()
    │    sessionManager.activeSession ?: createSession()
    │    TerminalManager.runOnlineInstall(session)
    │      writeEnvFile() → /tmp/oca-env.sh
    │      session.write(". /tmp/oca-env.sh\n")
    │      session.write(buildOnlineInstallScript())
    │        curl -sL myopenclawhub.com/install | bash
    │        [si falla] yes N | dpkg --configure -a --force-confold
    │        curl -sL myopenclawhub.com/install | bash  [reintento]
    │        source ~/.bashrc
    │
    └─ mode == "auto" / "offline" / "termux-bootstrap" / "proot"
         InstallOverlayController.show()
         InstallOverlayController.runInstall(mode)
           InstallerManager.install(mode)  [en Dispatchers.IO]
             ProgressListener.onProgress() → updateProgress() → UI
           onComplete(success)
             if (success) reloadWebView()
```

---

## Validación Post-Instalación

`InstallValidator.validatePayload(prefix)` — se ejecuta antes de escribir `.installed`:

```
Checks críticos (fallo = no se escribe marcador):
  ✓ glibc/lib/ld-linux-aarch64.so.1 existe y tamaño > 100KB
  ✓ node binary existe en alguna ruta conocida y tamaño > 1MB
  ✓ openclaw.mjs existe en alguna ruta conocida
  ✓ /system/bin/sh disponible

Checks importantes (fallo = advertencia en logs):
  ✓ SSL cert.pem existe
  ✓ glibc-compat.js presente
  ✓ run-openclaw.sh o openclaw-start.sh existe
```

Si la validación falla, el marcador `.installed` NO se escribe. La próxima vez que la app inicie, detectará que no está instalado y reintentará.

---

## Sobre `payload.tar.gz`

| Aspecto | Detalle |
|---------|---------|
| **Qué contiene** | OpenClaw pre-compilado: Node.js, glibc, openclaw.mjs |
| **Qué NO contiene** | dpkg, apt, bash (eso es el bootstrap de Termux) |
| **Ubicación** | `android/app/src/main/assets/payload.tar.gz` |
| **Tamaño** | ~118MB comprimido / ~400MB extraído |
| **En Git** | ❌ Excluido (`.gitignore`) — archivo demasiado grande |
| **En APK** | ✅ Incluido por Gradle al compilar |
| **`noCompress`** | `"gz"` en `build.gradle.kts` — evita doble compresión |
| **Detección** | `InstallerManager.hasPayloadAsset()` busca por nombre |
| **Orden de uso** | Siempre DESPUÉS del bootstrap de Termux |

**Nombres buscados por `hasPayloadAsset()`** (en orden de prioridad):
1. `payload.tar.gz` ← nombre actual
2. `payload-final.tar.gz`
3. `openclaw-payload.tar.gz`
4. `payload/openclaw-payload.tar.gz`
5. `payload/payload.tar.gz`

---

## Comparación de Modos

| | `auto` | `online` | `termux-bootstrap` | `proot` |
|---|---|---|---|---|
| **Termux Bootstrap** | ✅ | ✅ | ✅ | ❌ |
| **payload.tar.gz** | ✅ si existe | ❌ | ❌ | ❌ |
| **curl \| bash** | ❌ | ✅ en terminal | ❌ | ❌ |
| **Ubuntu rootfs** | ❌ | ❌ | ❌ | ✅ |
| **Requiere internet** | Solo bootstrap | Sí | Solo bootstrap | Sí |
| **Tamaño total** | ~200MB | ~200MB+ | ~150MB | ~330MB |
| **Phantom Process Killer** | ⚠️ | ⚠️ | ⚠️ | ✅ resistente |
| **pkg install** | ✅ | ✅ | ✅ | ❌ (usa apt) |
| **Recomendado para** | APK con payload | Sin payload | Solo entorno | Avanzados |
