# OpenClaw Android

> Ejecuta OpenClaw directamente en tu dispositivo Android con terminal nativa PTY, interfaz WebView React y actualizaciones OTA. Versión actual: **0.4.180-DEBUG**.

---

## ¿Qué es esto?

APK autónoma que instala y ejecuta OpenClaw en Android sin necesidad de root. Incluye:

- **Terminal PTY nativa** — sesiones múltiples con emulador completo
- **Interfaz WebView React** — setup, dashboard y configuración
- **Tres modos de instalación** — payload offline, proot+Ubuntu, online curl
- **GlibcRunner** — ejecutor obligatorio para Node.js en Android (via ld-linux-aarch64.so.1)
- **OTA** — actualizaciones de UI sin reinstalar el APK

---

## Requisitos del sistema

| Componente | Versión mínima |
|---|---|
| Android | 7.0 (API 24) |
| Arquitectura | arm64-v8a |
| JDK (build) | 21 |
| Android SDK (build) | API 36 |
| NDK (build) | 28+ |
| Node.js (build UI) | 22+ |

---

## Modos de instalación

### Modo 1 — Payload offline (payload-final.tar.gz, 113MB)
Asset principal bundleado en el APK. Contiene glibc, Node.js y OpenClaw listos para usar.

```
payload/
├── glibc/lib/              ← ld-linux-aarch64.so.1 + .so files
├── lib/node/bin/node.real  ← Node.js ELF (120MB)
├── lib/openclaw/           ← openclaw.mjs + node_modules/
├── certs/cert.pem          ← CA certs
└── patches/glibc-compat.js
```

### Modo 2 — proot + Ubuntu rootfs (online)
Descarga proot y un rootfs Ubuntu mínimo. Instala Node.js y OpenClaw dentro del entorno Ubuntu.

### Modo 3 — Online install (curl)
```bash
curl -sL myopenclawhub.com/install | bash
```
Detectado por: `prefix/bin/bash` + `ocaDir/installed.json` + `ocaDir/node/bin/node.real` + `prefix/glibc/lib/ld-linux-aarch64.so.1` + `prefix/lib/node_modules/openclaw/openclaw.mjs`

---

## Rutas del sistema

```
filesDir/
├── home/
│   ├── payload/                    ← payload extraído (modo offline)
│   │   ├── glibc/lib/              ← ld-linux-aarch64.so.1
│   │   ├── lib/node/bin/node.real  ← Node.js ELF
│   │   ├── lib/openclaw/           ← openclaw.mjs
│   │   └── certs/cert.pem
│   └── .openclaw-android/
│       ├── installed.json          ← marcador de instalación completa
│       └── node/bin/node.real      ← (modo online install)
└── usr/                            ← PREFIX (modo online install)
    ├── bin/bash                    ← bash del online install
    ├── glibc/lib/                  ← glibc del online install
    └── lib/node_modules/openclaw/  ← openclaw del online install
```

---

## Arquitectura

```
APK
├── Native:     TerminalView  — PTY via libtermux.so
├── WebView:    React SPA     — setup, dashboard, settings
├── JsBridge:   34 métodos    — WebView ↔ Kotlin (8 dominios)
├── EventBridge:              — Kotlin → WebView (CustomEvent)
└── OTA:        www.zip       — actualización atómica de UI
```

### Tres modos de terminal (TerminalSessionManager)

| Modo | Shell | Condición |
|---|---|---|
| proot | `openclaw-shell.sh` | proot instalado |
| online install | `prefix/bin/bash` | online install detectado |
| payload/legacy | fallback `/system/bin/sh` | ninguno de los anteriores |

> ⚠️ **Regla crítica:** `LD_LIBRARY_PATH` con `glibc/lib` NUNCA debe estar en el entorno de shells Bionic (`/system/bin/sh`). Causa: `CANNOT LINK EXECUTABLE sh: cannot find libc.so from verneed[0]`. `InstallOverlayController` y `TerminalManager` eliminan esta variable antes de pasar el entorno a `/system/bin/sh`.

### Flujo de ejecución

```
App inicia
  └─► requestStoragePermissions()
        └─► InstallerManager.install()
              ├─► hasPayloadAsset()? → installOffline() (payload-final.tar.gz)
              ├─► isOnlineInstallPresent()? → configureOnlineInstall()
              └─► installViaProot() → proot + Ubuntu rootfs
                    └─► installed.json ✓
                          └─► TerminalSessionManager (modo correcto)
                                └─► GlibcRunner → ld-linux-aarch64.so.1 → node → openclaw
```

---

## Estructura del proyecto

```
android/
├── app/src/main/
│   ├── java/com/openclaw/android/
│   │   ├── MainActivity.kt               # Contenedor WebView + TerminalView + permisos
│   │   ├── OpenClawService.kt            # Foreground Service (START_STICKY)
│   │   ├── InstallerManager.kt           # Orquestador: offline/online/proot, isOnlineInstallPresent()
│   │   ├── PayloadExtractor.kt           # Extracción streaming tar.gz (sin saturar RAM)
│   │   ├── PayloadManager.kt             # Fachada de compatibilidad sobre InstallerManager
│   │   ├── InstallValidator.kt           # Verifica lib/node/bin/node.real, lib/openclaw/, certs/cert.pem
│   │   ├── JsBridge.kt                   # 34 métodos @JavascriptInterface
│   │   ├── EventBridge.kt                # Eventos Kotlin → WebView
│   │   ├── CommandRunner.kt              # bash -l -c + rutas + wrapper
│   │   ├── EnvironmentBuilder.kt         # Variables de entorno (shim sobre EnvironmentResolver)
│   │   ├── UrlResolver.kt                # URLs BuildConfig + config.json remoto
│   │   ├── TerminalManager.kt            # Gestión PTY (LD_LIBRARY_PATH solo prefix/lib)
│   │   ├── TerminalSessionManager.kt     # 3 modos: proot / online install / payload-legacy
│   │   ├── BootReceiver.kt               # Auto-arranque al iniciar el dispositivo
│   │   ├── AppLogger.kt                  # Logging centralizado
│   │   ├── core/
│   │   │   ├── env/
│   │   │   │   ├── EnvironmentConfig.kt      # Snapshot inmutable de rutas
│   │   │   │   └── EnvironmentResolver.kt    # Resuelve rutas + detecta online install + resolveGlibcLib()
│   │   │   ├── process/
│   │   │   │   └── GlibcRunner.kt            # Ejecuta ELF via ld-linux-aarch64.so.1
│   │   │   └── install/
│   │   │       ├── InstallProgress.kt        # Interfaz de progreso
│   │   │       ├── ScriptWriter.kt           # Genera scripts de lanzamiento
│   │   │       ├── DnsAndSslSetup.kt         # Configura DNS y SSL (certs/cert.pem primero)
│   │   │       └── VersionReader.kt          # Lee versiones sin shell (evita CANNOT LINK EXECUTABLE)
│   │   ├── bridge/
│   │   │   ├── TerminalBridge.kt             # show/hide, sesiones, write
│   │   │   ├── SetupBridge.kt                # estado instalación, triggers
│   │   │   ├── PlatformBridge.kt             # plataformas
│   │   │   ├── ToolsBridge.kt                # herramientas (usa VersionReader)
│   │   │   ├── SystemBridge.kt               # info app, batería, almacenamiento, OTA
│   │   │   └── JsBridgeFacade.kt             # compone todos los bridges
│   │   └── ui/
│   │       ├── install/
│   │       │   └── InstallOverlayController.kt  # Elimina LD_LIBRARY_PATH/LD_PRELOAD para /system/bin/sh
│   │       └── permissions/
│   │           └── PermissionsController.kt
│   ├── assets/
│   │   ├── www/                          # UI React compilada (fallback)
│   │   ├── payload-final.tar.gz          # Asset principal (113MB) — payload offline
│   │   ├── oa-backup.tar.gz              # Asset de respaldo (145MB)
│   │   ├── run-openclaw.sh               # Lanzador del gateway OpenClaw
│   │   ├── env-init.sh                   # Inicialización de variables de entorno
│   │   └── glibc-compat.js               # Shim Node.js para compatibilidad glibc
│   └── res/                              # Recursos Android
├── app/src/test/java/com/openclaw/android/
│   ├── AppLoggerTest.kt                  # 7 tests — delegación de Log
│   ├── CommandRunnerTest.kt              # 22 tests — runSync, constantes, env
│   ├── EnvironmentBuilderTest.kt         # 27 tests — variables de entorno
│   ├── BootstrapManagerTest.kt           # 14 tests — detección, wrapper, ELF
│   └── VersionCompareTest.kt             # 8 tests — lógica semver OTA
├── www/                                  # React SPA (UI producción)
│   └── src/
│       ├── lib/bridge.ts                 # Wrapper tipado JsBridge (34 métodos)
│       ├── lib/useNativeEvent.ts         # Hook EventBridge para React
│       ├── lib/router.tsx                # Router hash-based (file:// compatible)
│       ├── components/                   # Componentes reutilizables
│       ├── i18n/                         # Internacionalización (EN, ES) — incluye git_not_available, storage_payload, etc.
│       └── screens/                      # Dashboard (versiones reales via getEnvironmentInfo), Settings, Setup
├── terminal-emulator/                    # Emulador PTY (fork ReTerminal)
└── terminal-view/                        # Renderizado terminal (fork ReTerminal)
```

---

## Build

### APK debug

```bash
cd android
./gradlew assembleDebug
# Salida: app/build/outputs/apk/debug/app-debug.apk
```

### APK release

```bash
# Configurar local.properties con keystore
./gradlew assembleRelease
```

### Tests unitarios

```bash
cd android
./gradlew test
# Reporte: app/build/reports/tests/test/index.html
```

### UI WebView

```bash
cd android/www
npm install
npm run build        # Salida: dist/
npm run build:zip    # Salida: www.zip (para OTA)
```

---

## JsBridge API

| Dominio | Métodos | Descripción |
|---|---|---|
| Terminal | 8 | show/hide, crear/cambiar/cerrar sesiones, escribir |
| Setup | 3 | estado bootstrap + openclaw, iniciar setup |
| Platform | 6 | instalar/desinstalar/cambiar plataformas |
| Tools | 5 | instalar/desinstalar herramientas CLI |
| Commands | 4 | sync/async, testGrunNode, launchGateway |
| Updates | 3 | check/apply OTA, info APK |
| System | 7 | info app, batería, permisos, almacenamiento |
| Storage | 1 | termux-setup-storage |

---

## Decisiones de diseño

| Decisión | Motivo |
|---|---|
| `targetSdk 28` | Bypass W^X — permite exec en `/data/data/` |
| `minSdk 24` | Requisito bootstrap apt-android-7 |
| `bash -l -c` siempre | Carga entorno login completo de Termux |
| `grun` obligatorio | Node.js en Android necesita glibc-runner |
| `installed.json` | Detección fiable de instalación completa |
| Scope IO compartido | Un solo `CoroutineScope(Dispatchers.IO)` en JsBridge — evita crear thread pools por operación |
| Hash routing | `file://` no soporta History API |
| Sin CSS framework | Bundle mínimo para entrega OTA |
| Rutas Termux reales | `/data/data/com.termux/files/` — independiente del paquete app |

---

## Permisos Android

| Permiso | Uso |
|---|---|
| `INTERNET` | Descarga bootstrap y actualizaciones |
| `FOREGROUND_SERVICE` | Mantener terminal activa en background |
| `WAKE_LOCK` | Evitar suspensión durante instalación |
| `RECEIVE_BOOT_COMPLETED` | Auto-inicio del gateway al arrancar |
| `READ/WRITE_EXTERNAL_STORAGE` | Android 6–10 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ (termux-setup-storage) |

---

## Dependencias clave

| Librería | Versión | Uso |
|---|---|---|
| AGP | 9.1.0 | Build system Android |
| Kotlin | 2.2.21 | Lenguaje principal |
| kotlinx-coroutines | 1.10.2 | Operaciones async |
| gson | 2.13.2 | Serialización JSON |
| JUnit5 | 6.0.3 | Tests unitarios |
| MockK | 1.14.9 | Mocking en tests |
| detekt | 1.23.8 | Análisis estático |
| ktlint | 14.2.0 | Formato de código |

---

## Licencia

GPL v3
