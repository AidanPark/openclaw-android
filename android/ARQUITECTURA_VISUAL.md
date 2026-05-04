# 🏗️ Arquitectura Visual del Sistema

## 📐 Diagrama General

```
┌─────────────────────────────────────────────────────────────────────┐
│                         OPENCLAW ANDROID APP                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌───────────────────────────────────────────────────────────┐     │
│  │                      MainActivity                          │     │
│  │  - onCreate()                                             │     │
│  │  - showInstallDialog()                                    │     │
│  │  - installTermuxBootstrap()                               │     │
│  │  - showAdvancedOptions()                                  │     │
│  └────────────────────┬──────────────────────────────────────┘     │
│                       │                                            │
│                       ↓                                            │
│  ┌───────────────────────────────────────────────────────────┐     │
│  │                   InstallerManager                        │     │
│  │  - install(mode, uri, listener)                           │     │
│  │  - isInstalled()                                          │     │
│  │  - getStatus()                                            │     │
│  └────────┬──────────────────────┬─────────────────┬─────────┘     │
│           │                      │                 │               │
│           ↓                      ↓                 ↓               │
│  ┌────────────────┐   ┌──────────────────┐   ┌─────────────┐     │
│  │TermuxBootstrap │   │  ProotManager    │   │PayloadManager│    │
│  │   Manager      │   │  (Ubuntu)        │   │  (Offline)   │    │
│  │                │   │                  │   │              │    │
│  │ • download()   │   │ • downloadProot()│   │ • extract()  │    │
│  │ • extract()    │   │ • downloadRootfs()│  │ • install()  │    │
│  │ • setup()      │   │ • runInProot()   │   │              │    │
│  │ • pkgUpdate()  │   │ • launchGateway()│   │              │    │
│  └────────────────┘   └──────────────────┘   └─────────────┘     │
│           │                      │                 │               │
│           └──────────────────────┴─────────────────┘               │
│                                  │                                 │
│                                  ↓                                 │
│  ┌───────────────────────────────────────────────────────────┐     │
│  │                 TerminalSessionManager                    │     │
│  │  - createSession()                                        │     │
│  │  - detectEnvironment()                                    │     │
│  │  - switchSession()                                        │     │
│  └────────────────────┬──────────────────────────────────────┘     │
│                       │                                            │
│                       ↓                                            │
│  ┌───────────────────────────────────────────────────────────┐     │
│  │                    TerminalView                           │     │
│  │  - Terminal visual del usuario                            │     │
│  │  - Muestra bash/sh según lo instalado                     │     │
│  └───────────────────────────────────────────────────────────┘     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## 🔄 Flujo de Instalación - Termux Bootstrap

```
┌─────────────────────────────────────────────────────────────────────┐
│                    FLUJO DE INSTALACIÓN TERMUX                      │
└─────────────────────────────────────────────────────────────────────┘

Usuario abre app
      │
      ↓
┌─────────────────┐
│ MainActivity    │
│ onCreate()      │
└────────┬────────┘
         │
         ↓
    ¿Instalado?
         │
         ├─→ Sí ──→ startApp()
         │
         └─→ No
              │
              ↓
┌──────────────────────────┐
│ showInstallDialog()      │
│                          │
│ [Termux Bootstrap]       │
│ [Opciones Avanzadas]     │
└────────┬─────────────────┘
         │
         ↓ Usuario elige "Termux Bootstrap"
         │
┌────────┴─────────────────────────────────────────────────────────┐
│ installerManager.install("termux-bootstrap", null, listener)     │
└────────┬─────────────────────────────────────────────────────────┘
         │
         ↓
┌────────┴──────────────────────────────────────────────────────────┐
│ TermuxBootstrapManager.install(listener)                          │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  1% │ Detectando arquitectura...                                 │
│     │ → detectArchitecture()                                     │
│     │ → Build.SUPPORTED_ABIS                                     │
│     │ → "aarch64" / "arm" / "x86_64" / "i686"                    │
│     │                                                             │
│  2% │ Descargando bootstrap...                                   │
│     │ → downloadBootstrap(url, file, onProgress)                 │
│     │ → HttpURLConnection                                        │
│     │ → Streaming download (32KB buffer)                         │
│     │ → ~50MB                                                    │
│     │                                                             │
│ 50% │ Extrayendo bootstrap...                                    │
│     │ → extractBootstrap(zipFile, targetDir, onProgress)         │
│     │ → ZipFile (Apache Commons Compress)                        │
│     │ → Preservar permisos (setExecutable)                       │
│     │ → Crear symlinks (Os.symlink)                              │
│     │ → ~5000 archivos                                           │
│     │                                                             │
│ 75% │ Configurando entorno...                                    │
│     │ → setupEnvironment()                                       │
│     │ → Crear resolv.conf (DNS)                                  │
│     │ → Crear profile (PATH, PREFIX, HOME)                       │
│     │ → Permisos en bin/                                         │
│     │                                                             │
│ 80% │ Actualizando repositorios...                               │
│     │ → runPkgUpdate(listener)                                   │
│     │ → bash -c "pkg update -y"                                  │
│     │ → Timeout 2 minutos                                        │
│     │                                                             │
│ 90% │ Instalando paquetes...                                     │
│     │ → installAdditionalPackages(listener)                      │
│     │ → pkg install -y git                                       │
│     │ → pkg install -y curl                                      │
│     │ → pkg install -y wget                                      │
│     │                                                             │
│ 98% │ Verificando instalación...                                 │
│     │ → isInstalled()                                            │
│     │ → Verificar dpkg, apt, bash                                │
│     │ → writeMarker()                                            │
│     │                                                             │
│100% │ ¡Instalación completada!                                   │
│     │ → listener.onSuccess()                                     │
│     │                                                             │
└─────┴───────────────────────────────────────────────────────────┘
         │
         ↓
┌────────┴──────────────────┐
│ MainActivity              │
│ onSuccess()               │
│ → hideProgressDialog()    │
│ → startApp()              │
└────────┬──────────────────┘
         │
         ↓
┌────────┴──────────────────────────────────────────────────────────┐
│ TerminalSessionManager.createSession()                            │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Detecta automáticamente:                                         │
│                                                                   │
│  if (termux-bootstrap instalado) {                                │
│      usar bash de Termux                                          │
│      PATH = /data/.../usr/bin:...                                 │
│      PREFIX = /data/.../usr                                       │
│      pkg install funciona ✓                                       │
│  }                                                                │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
         │
         ↓
┌────────┴──────────────────┐
│ TerminalView              │
│ → Muestra bash de Termux  │
│ → Usuario puede escribir  │
│ → pkg install funciona    │
└───────────────────────────┘
```

## 🔀 Flujo de Decisión - Qué Sistema Usar

```
┌─────────────────────────────────────────────────────────────────────┐
│              DECISIÓN DE SISTEMA DE INSTALACIÓN                     │
└─────────────────────────────────────────────────────────────────────┘

Usuario elige modo de instalación
         │
         ├─→ "termux-bootstrap" ──────────────────────────────────┐
         │                                                         │
         ├─→ "proot" ─────────────────────────────────────────────┤
         │                                                         │
         ├─→ "offline" ───────────────────────────────────────────┤
         │                                                         │
         └─→ "auto" ──────────────────────────────────────────────┤
                                                                   │
                                                                   ↓
┌──────────────────────────────────────────────────────────────────────┐
│                    InstallerManager.install()                        │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  when (mode) {                                                       │
│                                                                      │
│    "termux-bootstrap" → {                                            │
│        TermuxBootstrapManager.install()                              │
│        ↓                                                             │
│        Descarga bootstrap oficial (~50MB)                            │
│        Instala dpkg, apt, pkg, bash                                  │
│        ✓ Recomendado para todos                                      │
│    }                                                                 │
│                                                                      │
│    "proot" → {                                                       │
│        ProotManager.downloadProot()                                  │
│        ProotManager.downloadRootfs()                                 │
│        ↓                                                             │
│        Descarga Ubuntu completo (~250MB)                             │
│        Instala sistema Ubuntu + Node.js                              │
│        ✓ Solo para usuarios avanzados                                │
│    }                                                                 │
│                                                                      │
│    "offline" → {                                                     │
│        if (hasPayloadAsset()) {                                      │
│            PayloadManager.install()                                  │
│            ↓                                                         │
│            Extrae payload bundled (~167MB)                           │
│            ✓ No requiere internet                                    │
│        } else {                                                      │
│            Error: "No hay payload bundled"                           │
│        }                                                             │
│    }                                                                 │
│                                                                      │
│    "auto" → {                                                        │
│        if (hasPayloadAsset()) {                                      │
│            → "offline"                                               │
│        } else {                                                      │
│            → "termux-bootstrap"  ← Preferido                         │
│        }                                                             │
│    }                                                                 │
│                                                                      │
│  }                                                                   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## 🎯 Detección de Entorno - TerminalSessionManager

```
┌─────────────────────────────────────────────────────────────────────┐
│         DETECCIÓN AUTOMÁTICA DE ENTORNO EN TERMINAL                 │
└─────────────────────────────────────────────────────────────────────┘

TerminalSessionManager.createSession()
         │
         ↓
┌────────┴──────────────────────────────────────────────────────────┐
│  Detectar qué está instalado:                                     │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  1. ¿Proot instalado?                                             │
│     if (.proot-installed existe &&                                │
│         openclaw-shell.sh existe) {                               │
│         → Usar openclaw-shell.sh                                  │
│         → Shell: bash de Ubuntu                                   │
│         → Entorno: proot + Ubuntu                                 │
│         → apt funciona (Ubuntu)                                   │
│         ✓ Resistente a Phantom Process Killer                     │
│     }                                                             │
│                                                                   │
│  2. ¿Termux Bootstrap instalado?                                  │
│     if (usr/bin/bash existe &&                                    │
│         usr/bin/dpkg existe &&                                    │
│         usr/bin/apt existe) {                                     │
│         → Usar bash de Termux                                     │
│         → Shell: /data/.../usr/bin/bash                           │
│         → Entorno: Termux completo                                │
│         → pkg install funciona ✓                                  │
│         → apt funciona (Termux)                                   │
│     }                                                             │
│                                                                   │
│  3. ¿Payload instalado?                                           │
│     if (payload/bin/bash existe) {                                │
│         → Usar bash del payload                                   │
│         → Shell: bash con glibc                                   │
│         → Entorno: payload pre-configurado                        │
│     }                                                             │
│                                                                   │
│  4. Fallback                                                      │
│     → Usar /system/bin/sh                                         │
│     → Shell: sh de Android                                        │
│     → Entorno: básico (sin glibc)                                 │
│     ⚠️ Limitado, no puede ejecutar binarios glibc                 │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
         │
         ↓
┌────────┴──────────────────┐
│ TerminalSession creada    │
│ → Shell configurado       │
│ → Entorno configurado     │
│ → PATH configurado        │
│ → Usuario puede escribir  │
└───────────────────────────┘
```

## 📦 Estructura de Archivos Después de Instalar

```
/data/data/com.openclaw.android/
│
├── files/
│   │
│   ├── usr/  ← PREFIX de Termux (instalado por TermuxBootstrapManager)
│   │   │
│   │   ├── bin/
│   │   │   ├── bash          ← Shell principal
│   │   │   ├── sh → bash     ← Symlink
│   │   │   ├── dpkg          ← Gestor de paquetes
│   │   │   ├── apt           ← APT
│   │   │   ├── apt-get → apt ← Symlink
│   │   │   ├── pkg           ← Wrapper de apt
│   │   │   ├── git           ← Git (instalado)
│   │   │   ├── curl          ← cURL (instalado)
│   │   │   ├── wget          ← wget (instalado)
│   │   │   └── ... (más binarios)
│   │   │
│   │   ├── lib/
│   │   │   ├── libc.so → libc.so.6
│   │   │   ├── libc.so.6
│   │   │   └── ... (librerías)
│   │   │
│   │   ├── etc/
│   │   │   ├── resolv.conf   ← DNS configurado
│   │   │   ├── profile       ← Variables de entorno
│   │   │   └── apt/
│   │   │       └── sources.list
│   │   │
│   │   ├── var/
│   │   │   ├── lib/dpkg/     ← Base de datos dpkg
│   │   │   └── cache/apt/    ← Caché de APT
│   │   │
│   │   ├── share/
│   │   │   ├── man/
│   │   │   └── doc/
│   │   │
│   │   └── tmp/              ← Temporal
│   │
│   ├── home/                 ← HOME del usuario
│   │   ├── .bashrc
│   │   ├── .bash_history
│   │   └── ...
│   │
│   ├── bin/                  ← Binarios de proot (si está instalado)
│   │   └── proot
│   │
│   ├── ubuntu-rootfs/        ← Rootfs de Ubuntu (si proot está instalado)
│   │   ├── bin/
│   │   ├── usr/
│   │   └── ...
│   │
│   └── .termux-bootstrap-installed  ← Marcador de instalación
│
└── cache/
    └── termux-bootstrap-aarch64.zip  ← Bootstrap descargado
```

## 🔄 Ciclo de Vida Completo

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CICLO DE VIDA COMPLETO                           │
└─────────────────────────────────────────────────────────────────────┘

1. PRIMERA VEZ
   │
   ├─→ Usuario abre app
   │   └─→ MainActivity.onCreate()
   │       └─→ !installerManager.isInstalled()
   │           └─→ showInstallDialog()
   │               └─→ Usuario elige "Termux Bootstrap"
   │                   └─→ installTermuxBootstrap()
   │                       └─→ TermuxBootstrapManager.install()
   │                           ├─→ Descarga (~50MB)
   │                           ├─→ Extrae (~5000 archivos)
   │                           ├─→ Configura (DNS, profile)
   │                           ├─→ pkg update
   │                           ├─→ pkg install git curl wget
   │                           └─→ Escribe marcador
   │                               └─→ onSuccess()
   │                                   └─→ startApp()
   │
2. SEGUNDA VEZ (y siguientes)
   │
   ├─→ Usuario abre app
   │   └─→ MainActivity.onCreate()
   │       └─→ installerManager.isInstalled() ✓
   │           └─→ startApp()
   │               └─→ TerminalSessionManager.createSession()
   │                   ├─→ Detecta Termux Bootstrap instalado
   │                   ├─→ Configura bash de Termux
   │                   ├─→ Configura PATH, PREFIX, HOME
   │                   └─→ Terminal listo
   │                       └─→ Usuario puede escribir comandos
   │
3. USO NORMAL
   │
   ├─→ Usuario escribe: pkg install nodejs
   │   └─→ bash ejecuta comando
   │       └─→ pkg (wrapper de apt)
   │           └─→ apt install nodejs
   │               └─→ dpkg instala paquete
   │                   └─→ nodejs instalado ✓
   │
   ├─→ Usuario escribe: node --version
   │   └─→ bash ejecuta comando
   │       └─→ /data/.../usr/bin/node
   │           └─→ v22.x.x
   │
   └─→ Usuario escribe: git clone ...
       └─→ bash ejecuta comando
           └─→ /data/.../usr/bin/git
               └─→ Repositorio clonado ✓
```

## 🎨 Interfaz de Usuario

```
┌─────────────────────────────────────────────────────────────────────┐
│                      FLUJO DE UI RECOMENDADO                        │
└─────────────────────────────────────────────────────────────────────┘

PANTALLA 1: Primera Vez
┌─────────────────────────────────┐
│  🦀 OpenClaw Android            │
│                                 │
│  Esta app necesita instalar     │
│  un entorno de terminal.        │
│                                 │
│  ┌─────────────────────────┐   │
│  │ 📦 Termux Bootstrap     │   │
│  │ ~50MB • 2-3 min         │   │
│  │ [    Instalar    ]      │   │
│  └─────────────────────────┘   │
│                                 │
│  [ Opciones Avanzadas ]         │
│                                 │
└─────────────────────────────────┘
         │
         ↓ Usuario toca "Instalar"
         │
PANTALLA 2: Instalando
┌─────────────────────────────────┐
│  Instalando Termux Bootstrap    │
│                                 │
│  ████████████░░░░░░░░░  65%     │
│                                 │
│  Extrayendo bootstrap...        │
│  3250 archivos procesados       │
│                                 │
│  [ No cancelable ]              │
│                                 │
└─────────────────────────────────┘
         │
         ↓ Instalación completa
         │
PANTALLA 3: Éxito
┌─────────────────────────────────┐
│  ✅ Instalación Completada      │
│                                 │
│  Termux Bootstrap instalado     │
│  correctamente.                 │
│                                 │
│  Tamaño: 150MB                  │
│  Paquetes: dpkg, apt, pkg       │
│                                 │
│  [    Continuar    ]            │
│                                 │
└─────────────────────────────────┘
         │
         ↓ Usuario toca "Continuar"
         │
PANTALLA 4: Terminal
┌─────────────────────────────────┐
│  Terminal                    ⚙️ │
├─────────────────────────────────┤
│ $ pkg install nodejs            │
│ Installing nodejs...            │
│ Done.                           │
│ $ node --version                │
│ v22.13.1                        │
│ $ _                             │
│                                 │
│                                 │
│                                 │
└─────────────────────────────────┘
```

---

## 📊 Resumen Visual

```
┌──────────────────────────────────────────────────────────────┐
│                    SISTEMA COMPLETO                          │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  CÓDIGO:                                                     │
│  ✅ TermuxBootstrapManager.kt    (1,100 líneas)             │
│  ✅ TermuxBootstrapExample.kt    (500 líneas)               │
│  ✅ TermuxBootstrapManagerTest.kt (200 líneas)              │
│  ✅ InstallerManager.kt          (modificado)               │
│                                                              │
│  DOCUMENTACIÓN:                                              │
│  ✅ 9 archivos .md completos                                 │
│                                                              │
│  RESULTADO:                                                  │
│  ✅ Terminal Termux completo                                 │
│  ✅ dpkg, apt, pkg funcionales                               │
│  ✅ ~50MB, 2-3 minutos                                       │
│  ✅ Listo para usar                                          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**¡Sistema completo y listo para integrar!** 🚀
