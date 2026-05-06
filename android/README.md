# OpenClaw Android

> Ejecuta OpenClaw directamente en tu dispositivo Android con terminal nativa PTY, interfaz WebView React y actualizaciones OTA.

---

## ¿Qué es esto?

APK autónoma que instala y ejecuta OpenClaw en Android sin necesidad de root. Incluye:

- **Terminal PTY nativa** — sesiones múltiples con emulador completo
- **Interfaz WebView React** — setup, dashboard y configuración con code splitting
- **Tres modos de instalación** — payload offline, proot+Ubuntu, online curl
- **GlibcRunner** — ejecutor obligatorio para Node.js en Android (via `ld-linux-aarch64.so.1`)
- **OTA** — actualizaciones de UI sin reinstalar el APK
- **Bridge batch** — múltiples consultas al bridge en una sola llamada

---

## Requisitos del sistema

| Componente          | Versión mínima |
| ------------------- | -------------- |
| Android             | 7.0 (API 24)   |
| Arquitectura        | arm64-v8a      |
| JDK (build)         | 21             |
| Android SDK (build) | API 35         |
| NDK (build)         | 27+            |
| Node.js (build UI)  | 18+            |

---

## Modos de instalación

### Modo 1 — Payload offline (`payload.tar.gz`, bundleado en APK)

Asset principal incluido en el APK. Contiene glibc, Node.js y OpenClaw listos para usar.

```
payload/
├── glibc/lib/              ← ld-linux-aarch64.so.1 + .so files
├── lib/node/bin/node.real  ← Node.js ELF
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
    ├── bin/bash
    ├── glibc/lib/
    └── lib/node_modules/openclaw/
```

---

## Arquitectura

```
APK
├── Native:      TerminalView  — PTY via libtermux.so
├── WebView:     React SPA     — setup, dashboard, settings (lazy chunks)
├── JsBridge:    50+ métodos   — WebView ↔ Kotlin (5 dominios + batch)
├── EventBridge:               — Kotlin → WebView (CustomEvent)
└── OTA:         www.zip       — actualización atómica de UI
```

### Patrón Facade — MainActivity

```
MainActivity (fachada delgada)
├── ActivityInitializer      → onCreate(), onDestroy()
├── ActivityPermissionHandler → file pickers (payload/glibc)
├── ActivityViewSwitcher     → showTerminal(), showWebView()
├── ActivityInstallFlow      → flujo de instalación
├── TerminalTabManager       → pestañas de sesiones
├── TerminalSessionClientImpl → callbacks de sesión
├── TerminalViewClientImpl   → callbacks de vista
└── WebViewConfigurator      → setupWebView()
```

### Tres modos de terminal (TerminalSessionManager)

| Modo           | Shell                     | Condición                 |
| -------------- | ------------------------- | ------------------------- |
| proot          | `openclaw-shell.sh`       | proot instalado           |
| online install | `prefix/bin/bash`         | online install detectado  |
| payload/legacy | fallback `/system/bin/sh` | ninguno de los anteriores |

> ⚠️ **Regla crítica:** `LD_LIBRARY_PATH` con `glibc/lib` NUNCA debe estar en el entorno de shells Bionic (`/system/bin/sh`). Causa: `CANNOT LINK EXECUTABLE sh: cannot find libc.so from verneed[0]`. `InstallOverlayController` y `TerminalManager` eliminan esta variable antes de pasar el entorno a `/system/bin/sh`.

### Flujo de ejecución

```
App inicia
  └─► ModernPermissionManager.requestStorage()
        └─► InstallerManager → InstallationOrchestrator
              ├─► hasPayloadAsset()? → installOffline() (payload.tar.gz)
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
│   │   ├── MainActivity.kt               # Fachada — delega a componentes especializados
│   │   ├── OpenClawService.kt            # Foreground Service (START_STICKY)
│   │   ├── InstallerManager.kt           # Orquestador de alto nivel
│   │   ├── PayloadExtractor.kt           # Extracción streaming tar.gz (sin saturar RAM)
│   │   ├── InstallValidator.kt           # Verifica node.real, openclaw/, certs/cert.pem
│   │   ├── EventBridge.kt                # Eventos Kotlin → WebView
│   │   │   # CommandRunner.kt - ELIMINADO (métodos peligrosos removidos por seguridad)
│   │   ├── UrlResolver.kt                # URLs BuildConfig + config.json remoto
│   │   ├── TerminalManager.kt            # Gestión PTY
│   │   ├── TerminalSessionManager.kt     # 3 modos: proot / online install / payload-legacy
│   │   ├── BootReceiver.kt               # Auto-arranque al iniciar el dispositivo
│   │   ├── AppLogger.kt                  # Logging centralizado
│   │   ├── core/
│   │   │   ├── env/
│   │   │   │   ├── EnvironmentConfig.kt      # Snapshot inmutable de rutas
│   │   │   │   └── EnvironmentResolver.kt    # Única fuente de verdad para rutas y env vars
│   │   │   ├── process/
│   │   │   │   └── GlibcRunner.kt            # Ejecuta ELF via ld-linux-aarch64.so.1
│   │   │   ├── install/
│   │   │   │   ├── InstallationOrchestrator.kt # Orquestador unificado (offline/online/proot)
│   │   │   │   ├── InstallProgress.kt        # Interfaz de progreso
│   │   │   │   ├── PayloadInstaller.kt       # Extracción y validación del payload
│   │   │   │   ├── ScriptWriter.kt           # Genera scripts de lanzamiento
│   │   │   │   ├── InstallStateChecker.kt    # Verifica estado de instalación
│   │   │   │   ├── InstallMarkerWriter.kt    # Escribe installed.json
│   │   │   │   ├── EnvironmentConfigurator.kt # Configura DNS y SSL
│   │   │   │   └── VersionReader.kt          # Lee versiones sin shell
│   │   │   ├── bootstrap/
│   │   │   │   ├── TermuxBootstrapOrchestrator.kt # Instalación Termux Bootstrap
│   │   │   │   ├── TermuxBootstrapDownloader.kt
│   │   │   │   ├── TermuxBootstrapExtractor.kt
│   │   │   │   ├── TermuxBootstrapMarker.kt
│   │   │   │   ├── TermuxArchitectureDetector.kt
│   │   │   │   ├── TermuxDpkgManager.kt
│   │   │   │   ├── TermuxEnvironmentConfigurator.kt
│   │   │   │   ├── TermuxPackageInstaller.kt
│   │   │   │   └── TermuxPkgManager.kt
│   │   │   └── proot/
│   │   │       ├── ProotBinaryDownloader.kt
│   │   │       ├── ProotCommandBuilder.kt
│   │   │       ├── ProotCommandExecutor.kt
│   │   │       ├── ProotConstants.kt
│   │   │       ├── ProotFileDownloader.kt
│   │   │       ├── ProotPathResolver.kt
│   │   │       ├── ProotRootfsConfigurator.kt
│   │   │       └── ProotRootfsDownloader.kt
│   │   ├── bridge/
│   │   │   ├── JsBridgeFacade.kt             # Compone todos los bridges + batchQuery
│   │   │   ├── TerminalBridge.kt             # show/hide, sesiones, write
│   │   │   ├── SetupBridge.kt                # estado instalación, triggers
│   │   │   ├── PlatformBridge.kt             # plataformas
│   │   │   ├── ToolsBridge.kt                # herramientas CLI
│   │   │   └── SystemBridge.kt               # info app, batería, almacenamiento, OTA
│   │   └── ui/
│   │       ├── activity/
│   │       │   ├── ActivityInitializer.kt    # onCreate/onDestroy, inicializa managers
│   │       │   ├── ActivityPermissionHandler.kt # File pickers (payload/glibc)
│   │       │   └── ActivityViewSwitcher.kt   # Terminal ↔ WebView
│   │       ├── install/
│   │       │   ├── ActivityInstallFlow.kt    # Flujo de instalación desde UI
│   │       │   └── InstallOverlayController.kt # Overlay de progreso
│   │       ├── permissions/
│   │       │   └── ModernPermissionManager.kt # Permisos con suspend + ActivityResultLaunchers
│   │       ├── terminal/
│   │       │   ├── TerminalSessionClientImpl.kt
│   │       │   ├── TerminalTabManager.kt
│   │       │   └── TerminalViewClientImpl.kt
│   │       └── webview/
│   │           └── WebViewConfigurator.kt
│   ├── assets/
│   │   ├── www/                          # UI React compilada (fallback)
│   │   ├── payload.tar.gz                # Asset principal — payload offline
│   │   ├── run-openclaw.sh               # Lanzador del gateway OpenClaw
│   │   ├── env-init.sh                   # Inicialización de variables de entorno
│   │   └── glibc-compat.js               # Shim Node.js para compatibilidad glibc
│   └── res/                              # Recursos Android
├── www/                                  # React SPA (UI producción)
│   └── src/
│       ├── App.tsx                       # Root — AppProvider + lazy routes
│       ├── contexts/
│       │   └── AppContext.tsx            # Estado centralizado (setup, env, storage, tools)
│       ├── lib/
│       │   ├── bridge.ts                 # Wrapper tipado JsBridge + batchCall()
│       │   ├── useNativeEvent.ts         # Hook EventBridge para React
│       │   └── router.tsx                # Router hash-based (file:// compatible)
│       ├── hooks/
│       │   └── useAppState.ts            # Hook derivado de AppContext
│       ├── i18n/                         # Internacionalización (EN, ES)
│       └── screens/
│           ├── Dashboard.tsx             # Usa AppContext, componentes memoizados, skeleton
│           ├── Setup.tsx                 # TipCard y Stepper memoizados, useCallback
│           ├── Settings.tsx
│           ├── SettingsTools.tsx         # Diálogo de confirmación modal, ToolCard memoizado
│           ├── SettingsPlatforms.tsx     # Lazy loaded
│           ├── SettingsKeepAlive.tsx     # Lazy loaded
│           ├── SettingsStorage.tsx       # Lazy loaded
│           ├── SettingsAbout.tsx         # Lazy loaded
│           ├── SettingsUpdates.tsx       # Lazy loaded
│           └── SettingsAdvanced.tsx      # Lazy loaded
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
npm run build        # Salida: dist/ (con code splitting automático)
npm run test         # Tests con Vitest
```

---

## JsBridge API

| Dominio   | Métodos clave | Descripción                                                              |
| --------- | ------------- | ------------------------------------------------------------------------ |
| Terminal  | 8             | show/hide, crear/cambiar/cerrar sesiones, escribir                       |
| Setup     | 12            | estado bootstrap/payload/rootfs, iniciar setup, rutas                    |
| Platform  | 6             | instalar/desinstalar/cambiar plataformas                                 |
| Tools     | 4             | instalar/desinstalar herramientas CLI, estado                            |
| System    | 15+           | info app, batería, permisos, almacenamiento, OTA, comandos               |
| **Batch** | 1             | `batchQuery(callbackId, methods[])` — múltiples consultas en una llamada |

### batchQuery

```typescript
// Frontend — una sola llamada para múltiples estados
const results = await bridge.batchCall([
  "getSetupStatus",
  "getEnvironmentInfo",
  "getStorageInfo",
  "getInstalledTools",
]);
```

```kotlin
// Kotlin — emite native:batch_result con todos los resultados
@JavascriptInterface
fun batchQuery(callbackId: String, requests: String)
```

---

## Seguridad — Ejecución de Comandos

**⚠️ Cambio de seguridad importante:**

Los métodos `runCommand()` y `runCommandAsync` fueron **eliminados** de `SystemBridge` y `JsBridgeFacade`.

### Razón

Por seguridad, la ejecución de comandos arbitrarios desde WebView ya no está disponible:

```kotlin
// ❌ ELIMINADO — No expuesto a JavaScript
// @JavascriptInterface
// fun runCommand(cmd: String): String
// fun runCommandAsync(cmd: String): String
```

### Alternativa

Los comandos internos del sistema usan `runSyncUnsafe` (no expuesto al frontend):

```kotlin
// ✅ Solo para uso interno en Kotlin
CommandRunner.runSyncUnsafe("internal-command")
```

**Principio:** Ningún comando shell puede ser ejecutado desde el frontend React. Todo procesamiento crítico ocurre en Kotlin nativo.

---

## Permisos Android

| Permiso                  | Uso                                                     |
| ------------------------ | ------------------------------------------------------- |
| `INTERNET`               | Descarga bootstrap y actualizaciones (solo modo online) |
| `FOREGROUND_SERVICE`     | Mantener terminal activa en background                  |
| `WAKE_LOCK`              | Evitar suspensión durante instalación                   |
| `RECEIVE_BOOT_COMPLETED` | Auto-inicio del gateway al arrancar                     |
| `POST_NOTIFICATIONS`     | Android 13+ — notificaciones del servicio               |

**Eliminados (no requeridos):**

- ❌ `MANAGE_EXTERNAL_STORAGE` — Todo funciona en sandbox privado
- ❌ `READ/WRITE_EXTERNAL_STORAGE` — No se accede a almacenamiento externo

### ModernPermissionManager

Maneja permisos con API `suspend` y `ActivityResultLaunchers`:

```kotlin
// Solicitar notificaciones (Android 13+)
val granted = permissionManager.requestNotifications()

// Verificar sin solicitar
val hasNotifications = permissionManager.hasNotificationPermission()
```

**Nota:** Los permisos de almacenamiento fueron eliminados. Todo funciona dentro del sandbox privado de la app (`context.getFilesDir()`).

---

## Estado centralizado — AppContext (React)

`AppContext` es la única fuente de verdad del frontend. Todos los componentes consumen estado desde aquí en lugar de llamar directamente al bridge:

```typescript
const { setupStatus, envInfo, storageInfo, installedTools, refresh } =
  useAppContext();
```

Se refresca automáticamente al recibir eventos nativos: `session_changed`, `setup_progress`, `install_progress`.

---

## Decisiones de diseño

| Decisión                                         | Motivo                                                               |
| ------------------------------------------------ | -------------------------------------------------------------------- |
| `targetSdk 28`                                   | Bypass W^X — permite exec en `/data/data/`                           |
| `minSdk 24`                                      | Requisito bootstrap apt-android-7                                    |
| `EnvironmentResolver` como única fuente de rutas | Elimina rutas hardcodeadas y duplicación                             |
| `InstallationOrchestrator` unificado             | Reemplaza SetupManager + RootfsManager eliminados                    |
| `ModernPermissionManager` con suspend            | API limpia, sin callbacks anidados                                   |
| `batchQuery` en JsBridge                         | Reduce llamadas WebView↔Kotlin de N a 1                              |
| `AppContext` centralizado                        | Evita prop drilling y llamadas duplicadas al bridge                  |
| `lazy + Suspense` en settings                    | Code splitting — chunks de 2–8 KB cargados bajo demanda              |
| Componentes `memo` en Dashboard                  | Evita re-renders innecesarios en actualizaciones de estado           |
| Eliminación de `runCommand/runCommandAsync`      | Seguridad: comandos shell ya no expuestos a WebView                  |
| Hash routing                                     | `file://` no soporta History API                                     |
| Sin CSS framework                                | Bundle mínimo para entrega OTA                                       |
| Scope IO compartido                              | Un solo `CoroutineScope(Dispatchers.IO + SupervisorJob)` en JsBridge |

---

## Dependencias clave

| Librería           | Versión | Uso                              |
| ------------------ | ------- | -------------------------------- |
| AGP                | 9.1.0   | Build system Android             |
| Kotlin             | 2.2.21  | Lenguaje principal               |
| kotlinx-coroutines | 1.10.2  | Operaciones async                |
| gson               | 2.13.2  | Serialización JSON en batchQuery |
| React              | 19      | UI WebView                       |
| Vite               | 7       | Build + code splitting           |
| Vitest             | latest  | Tests frontend                   |
| JUnit5             | 6.0.3   | Tests unitarios Kotlin           |
| MockK              | 1.14.9  | Mocking en tests                 |
| detekt             | 1.23.8  | Análisis estático                |
| ktlint             | 14.2.0  | Formato de código                |

---

## Licencia

GPL v3
