# Arquitectura Visual — OpenClaw Android

## Diagrama general

```
┌─────────────────────────────────────────────────────────────────────┐
│                         OPENCLAW ANDROID APP                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                  MainActivity (fachada)                      │   │
│  │                                                             │   │
│  │  ActivityInitializer   → onCreate(), onDestroy()            │   │
│  │  ActivityPermissionHandler → file pickers                   │   │
│  │  ActivityViewSwitcher  → showTerminal(), showWebView()      │   │
│  │  ActivityInstallFlow   → flujo de instalación               │   │
│  │  TerminalTabManager    → pestañas de sesiones               │   │
│  │  WebViewConfigurator   → setupWebView()                     │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
│              ┌──────────────┼──────────────┐                       │
│              ↓              ↓              ↓                       │
│  ┌───────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │InstallerManager│  │TerminalSession│  │ JsBridgeFacade│          │
│  │               │  │   Manager    │  │               │           │
│  │ orchestrates  │  │ 3 modes:     │  │ TerminalBridge│           │
│  │ install flow  │  │ proot/online │  │ SetupBridge   │           │
│  │               │  │ /payload     │  │ PlatformBridge│           │
│  └───────┬───────┘  └──────┬───────┘  │ ToolsBridge   │           │
│          │                 │          │ SystemBridge  │           │
│          ↓                 ↓          │ batchQuery()  │           │
│  ┌───────────────────────────────┐    └───────────────┘           │
│  │    InstallationOrchestrator   │                                 │
│  │                               │                                 │
│  │  sealed class InstallationMode│                                 │
│  │  ├── Payload (offline)        │                                 │
│  │  ├── Online (curl | bash)     │                                 │
│  │  └── Proot (Ubuntu rootfs)    │                                 │
│  └───────────────────────────────┘                                 │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                   EnvironmentResolver                        │   │
│  │  Única fuente de verdad para rutas y variables de entorno   │   │
│  │  resolve(filesDir) → EnvironmentConfig (inmutable)          │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Flujo de instalación

```
Usuario abre app
      │
      ↓
ActivityInitializer.onCreate()
      │
      ↓
ModernPermissionManager.requestStorage()  ← suspend, ActivityResultLauncher
      │
      ↓
InstallerManager → InstallationOrchestrator
      │
      ├─→ hasPayloadAsset()? ──────────────────────────────────────┐
      │                                                             │
      ├─→ isOnlineInstallPresent()? ───────────────────────────────┤
      │                                                             │
      └─→ installViaProot() ───────────────────────────────────────┤
                                                                    │
                                                                    ↓
                                                         installed.json ✓
                                                                    │
                                                                    ↓
                                                    TerminalSessionManager
                                                    detecta modo correcto
                                                                    │
                                                                    ↓
                                                    GlibcRunner → node → openclaw
```

## Flujo de decisión — modo de instalación

```
InstallationOrchestrator.install(mode)
         │
         ├─→ "payload" / "auto" con asset ──────────────────────────┐
         │                                                           │
         │   PayloadInstaller                                        │
         │   1. Extrae payload.tar.gz (streaming, sin temp files)   │
         │   2. InstallValidator verifica node.real, openclaw.mjs   │
         │   3. EnvironmentConfigurator configura DNS + SSL         │
         │   4. InstallMarkerWriter escribe installed.json          │
         │   5. ScriptWriter genera openclaw-start.sh               │
         │                                                           │
         ├─→ "online" ────────────────────────────────────────────── ┤
         │                                                           │
         │   TermuxBootstrapOrchestrator                            │
         │   1. TermuxArchitectureDetector detecta ABI              │
         │   2. TermuxBootstrapDownloader descarga ZIP (~50MB)      │
         │   3. TermuxBootstrapExtractor extrae con permisos        │
         │   4. TermuxEnvironmentConfigurator configura DNS         │
         │   5. TermuxDpkgManager repara dpkg                       │
         │   6. TermuxPkgManager ejecuta pkg update                 │
         │   7. TermuxPackageInstaller instala paquetes opcionales  │
         │   8. TermuxBootstrapMarker escribe marcador              │
         │   → Luego: curl -sL myopenclawhub.com/install | bash    │
         │                                                           │
         └─→ "proot" ─────────────────────────────────────────────── ┘
         
             ProotBinaryDownloader descarga proot
             ProotRootfsDownloader descarga Ubuntu rootfs
             ProotRootfsConfigurator configura entorno
             ProotCommandExecutor lanza gateway en proot
```

## Arquitectura del frontend React

```
App.tsx
└── AppProvider (AppContext)
    │
    ├── AppInner
    │   ├── Tab bar (Terminal / Dashboard / Settings)
    │   │
    │   ├── /setup → Setup.tsx
    │   │   ├── Stepper (memo)
    │   │   ├── TipCard (memo)
    │   │   └── Fases: welcome → mode-select → tool-select → installing → done/failed
    │   │
    │   ├── /dashboard → Dashboard.tsx
    │   │   ├── RuntimeItem (memo) × 4
    │   │   ├── CommandRow (memo) × N
    │   │   ├── QuickAction (memo) × 4
    │   │   └── DashboardSkeleton (mientras loading=true)
    │   │
    │   └── /settings → Suspense → SettingsRouter
    │       ├── Settings.tsx (eager)
    │       ├── SettingsTools.tsx (lazy chunk ~5.5KB)
    │       │   ├── ConfirmDialog (modal, reemplaza window.confirm)
    │       │   └── ToolCard (memo)
    │       ├── SettingsPlatforms.tsx (lazy chunk ~2KB)
    │       ├── SettingsKeepAlive.tsx (lazy chunk ~2.5KB)
    │       ├── SettingsStorage.tsx (lazy chunk ~5KB)
    │       ├── SettingsAbout.tsx (lazy chunk ~5KB)
    │       ├── SettingsUpdates.tsx (lazy chunk ~2.6KB)
    │       └── SettingsAdvanced.tsx (lazy chunk ~8KB)
    │
    └── AppContext state
        ├── setupStatus: SetupStatus | null
        ├── envInfo: EnvInfo
        ├── storageInfo: StorageInfo | null
        ├── sessions: SessionInfo[]
        ├── installedTools: InstalledTool[]
        ├── loading: boolean
        ├── refresh() — recarga todo
        └── batchRefresh() — usa batchQuery para eficiencia
```

## Flujo de comunicación WebView ↔ Kotlin

```
KOTLIN → WEBVIEW (eventos)
─────────────────────────────────────────────────────────────────────
eventBridge.emit("setup_progress", mapOf("progress" to 0.5, "message" to "..."))
    │
    └─→ window.dispatchEvent(CustomEvent("native:setup_progress", { detail: {...} }))
            │
            └─→ useNativeEvent("setup_progress", handler)  ← React hook
                    │
                    └─→ AppContext actualiza estado automáticamente


WEBVIEW → KOTLIN (llamadas directas)
─────────────────────────────────────────────────────────────────────
bridge.call("startSetup", "online")
    │
    └─→ window.OpenClaw.startSetup("online")
            │
            └─→ @JavascriptInterface fun startSetup(mode: String)


WEBVIEW → KOTLIN (batch — múltiples en una llamada)
─────────────────────────────────────────────────────────────────────
bridge.batchCall(["getSetupStatus", "getEnvironmentInfo", "getStorageInfo"])
    │
    └─→ window.OpenClaw.batchQuery(callbackId, JSON.stringify([...]))
            │
            └─→ ioScope.launch { ejecuta métodos en paralelo }
                    │
                    └─→ eventBridge.emit("batch_result", { callbackId, results: [...] })
                            │
                            └─→ Promise resuelve con array de resultados
```

## Seguridad — CommandRunner

```
WebView llama: OpenClaw.runCommand("rm -rf /")
                    │
                    ↓
CommandRunner.sanitizeCommand("rm -rf /")
                    │
                    ├─→ ¿Longitud > 10.000? → null (bloqueado)
                    │
                    ├─→ ¿Patrón de inyección?
                    │   (| sh, | bash, && rm -rf, `...`, $(...))
                    │   → null (bloqueado)
                    │
                    ├─→ ¿Comando base en ALLOWED_COMMANDS_PREFIX?
                    │   (openclaw, node, npm, git, apt, pkg, curl, ls, cat, tar...)
                    │   → sanitizeArguments() → comando limpio
                    │
                    └─→ Comando desconocido → null (bloqueado)
                                │
                                ↓
                    return CommandResult(-1, "", "Command blocked for security reasons")
```

## Permisos — ModernPermissionManager

```
ActivityInitializer.requestInitialPermissions()
    │
    └─→ CoroutineScope(Dispatchers.Main).launch {
            │
            ├─→ permissionManager.requestStorage()
            │       │
            │       ├─→ Android 11+: Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            │       │   └─→ storageTreeLauncher (ActivityResultLauncher)
            │       │       └─→ storageDeferred.await() → Boolean
            │       │
            │       ├─→ Android 13: true (no necesita permiso para filesDir)
            │       │
            │       └─→ Android < 11: READ/WRITE_EXTERNAL_STORAGE
            │           └─→ legacyStorageLauncher → storageDeferred.await()
            │
            └─→ permissionManager.requestNotifications()
                    │
                    └─→ Android 13+: POST_NOTIFICATIONS
                        └─→ notificationLauncher → notificationDeferred.await()
        }
```

## Estructura de rutas — EnvironmentResolver

```
EnvironmentResolver.resolve(filesDir)
    │
    └─→ EnvironmentConfig (inmutable)
        ├── filesDir    = context.filesDir
        ├── homeDir     = filesDir/home/
        ├── tmpDir      = filesDir/tmp/
        ├── ocaDir      = homeDir/.openclaw-android/
        ├── payloadDir  = homeDir/payload/ (o homeDir/openclaw-payload/, etc.)
        ├── prefix      = payloadDir/ (o filesDir/usr/)
        ├── glibcLib    = payloadDir/glibc/lib/ (o prefix/glibc/lib/)
        ├── linker      = glibcLib/ld-linux-aarch64.so.1
        ├── nodeBin     = payloadDir/lib/node/bin/node.real (prioridad 1)
        │               = ocaDir/node/bin/node.real (online install)
        │               = payloadDir/glibc/bin/node (legacy)
        ├── openClawMjs = payloadDir/lib/openclaw/openclaw.mjs (prioridad 1)
        │               = prefix/lib/node_modules/openclaw/openclaw.mjs (online)
        └── certPem     = payloadDir/certs/cert.pem (prioridad 1)
                        = prefix/etc/tls/cert.pem (online)
```
