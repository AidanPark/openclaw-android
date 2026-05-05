# Refactorización — OpenClaw Android

## Estado: COMPLETADO ✅

Este documento registra la refactorización completa realizada. El código está en producción.

---

## Resumen de cambios

### Archivos eliminados

| Archivo | Motivo |
|---|---|
| `SetupManager.kt` | Duplicaba lógica de `ProotManager`. Reemplazado por `InstallationOrchestrator` |
| `RootfsManager.kt` | Duplicaba extracción. Integrado en `PayloadInstaller` |
| `EnvironmentBuilder.kt` | Era un shim sobre `EnvironmentResolver`. Eliminado, todos los callers usan `EnvironmentResolver` directamente |
| `TermuxBootstrapExample.kt` | Solo era un ejemplo, no usable en producción |
| `PermissionsController.kt` | Reemplazado por `ModernPermissionManager` |
| `JsBridge.kt` | Era un wrapper delgado sobre `JsBridgeFacade`. Eliminado, se usa `JsBridgeFacade` directamente |

---

## Fase 1 — Módulos core ✅

### `core/env/`
- **`EnvironmentConfig.kt`** — snapshot inmutable de todas las rutas
- **`EnvironmentResolver.kt`** — única fuente de verdad para rutas y variables de entorno. Detecta payload en orden de prioridad, construye el mapa de env vars completo

### `core/process/`
- **`GlibcRunner.kt`** — ejecuta binarios ELF via `ld-linux-aarch64.so.1`. NUNCA usa `/system/bin/sh` para invocar node

### `core/install/`
- **`InstallationOrchestrator.kt`** — orquestador unificado que reemplaza `SetupManager` + `RootfsManager`
- **`PayloadInstaller.kt`** — extracción streaming del payload, sin archivos temporales
- **`InstallProgress.kt`** — interfaz de progreso
- **`InstallStateChecker.kt`** — verifica estado de instalación
- **`InstallMarkerWriter.kt`** — escribe `installed.json`
- **`EnvironmentConfigurator.kt`** — configura DNS y SSL
- **`ScriptWriter.kt`** — genera `openclaw-start.sh`
- **`VersionReader.kt`** — lee versiones sin shell (evita `CANNOT LINK EXECUTABLE`)

### `core/bootstrap/`
Termux Bootstrap descompuesto en responsabilidades únicas:
- `TermuxArchitectureDetector`, `TermuxBootstrapDownloader`, `TermuxBootstrapExtractor`
- `TermuxBootstrapMarker`, `TermuxBootstrapOrchestrator`
- `TermuxDpkgManager`, `TermuxEnvironmentConfigurator`
- `TermuxPackageInstaller`, `TermuxPkgManager`

### `core/proot/`
Proot descompuesto en responsabilidades únicas:
- `ProotBinaryDownloader`, `ProotCommandBuilder`, `ProotCommandExecutor`
- `ProotConstants`, `ProotFileDownloader`, `ProotPathResolver`
- `ProotRootfsConfigurator`, `ProotRootfsDownloader`

---

## Fase 2 — JsBridge refactorizado ✅

`JsBridgeFacade` compone 5 bridges de dominio:

```
JsBridgeFacade
├── TerminalBridge   — show/hide, sesiones, write
├── SetupBridge      — estado instalación, triggers
├── PlatformBridge   — plataformas
├── ToolsBridge      — herramientas CLI
└── SystemBridge     — app info, batería, almacenamiento, OTA, comandos
```

Añadido `batchQuery(callbackId, requests)`:
- Acepta array JSON de nombres de métodos
- Ejecuta todos en `ioScope` (Dispatchers.IO)
- Emite `native:batch_result` con array de resultados
- Permite al frontend obtener múltiples estados en una sola llamada

---

## Fase 3 — MainActivity refactorizado ✅

`MainActivity` es ahora una fachada delgada (~200 líneas). Delega a:

| Componente | Responsabilidad |
|---|---|
| `ActivityInitializer` | `onCreate()`, `onDestroy()`, inicializa todos los managers |
| `ActivityPermissionHandler` | File pickers para payload y glibc |
| `ActivityViewSwitcher` | Cambio Terminal ↔ WebView |
| `ActivityInstallFlow` | Flujo de instalación desde UI |
| `TerminalTabManager` | Pestañas de sesiones |
| `TerminalSessionClientImpl` | Callbacks de sesión PTY |
| `TerminalViewClientImpl` | Callbacks de vista terminal |
| `WebViewConfigurator` | Setup del WebView y registro del bridge |

---

## Fase 4 — Permisos modernos ✅

`ModernPermissionManager` reemplaza `PermissionsController`:

- API `suspend` — `requestStorage()`, `requestNotifications()`, `requestManageExternalStorage()`
- `ActivityResultLaunchers` — sin `onRequestPermissionsResult` deprecado
- `CompletableDeferred<Boolean>` — conecta launchers con corutinas
- Diálogos de rationale integrados
- `hasStoragePermission()`, `hasNotificationPermission()` para verificación sin solicitar

---

## Fase 5 — CommandRunner con seguridad ✅

Añadidas tres capas de validación en `sanitizeCommand()`:

1. **Longitud máxima** — rechaza > 10.000 caracteres
2. **Patrones de inyección** — bloquea `| sh`, `| bash`, `&& rm -rf`, `` `...` ``, `$(...)`, etc.
3. **Whitelist** — `ALLOWED_COMMANDS_PREFIX` con ~40 comandos permitidos

`runSync` y `runStreaming` usan `sanitizeCommand` automáticamente.
`runSyncUnsafe` y `runStreamingUnsafe` disponibles para uso interno del sistema.

---

## Fase 6 — Frontend React refactorizado ✅

### `AppContext.tsx`
- Estado centralizado: `setupStatus`, `envInfo`, `storageInfo`, `sessions`, `installedTools`
- `refresh()` — recarga todo en paralelo con `Promise.all`
- `batchRefresh()` — usa `batchQuery` para eficiencia
- Auto-refresco en eventos: `session_changed`, `setup_progress`, `install_progress`

### `bridge.ts`
- `batchCall(methods[])` — devuelve `Promise<Array<{method, data, success}>>` 
- Timeout de 10 segundos con cleanup automático del listener

### `App.tsx`
- Envuelto en `AppProvider`
- `lazy + Suspense` para 7 pantallas de settings
- Estado derivado de `AppContext` en lugar de llamadas directas al bridge

### `Dashboard.tsx`
- Consume `useAppContext()` — sin llamadas directas al bridge
- `RuntimeItem`, `CommandRow`, `QuickAction` memoizados con `memo`
- `DashboardSkeleton` como componente separado

### `Setup.tsx`
- `TipCard` y `Stepper` memoizados
- Todos los handlers con `useCallback`
- Cleanup correcto del timeout en `handleCheckConnection`

### `SettingsTools.tsx`
- `ConfirmDialog` modal reemplaza `window.confirm`
- `ToolCard` memoizado
- Handlers con `useCallback`

---

## Resultado del build

```
dist/index.html                              0.62 kB
dist/assets/index-*.css                     17.29 kB │ gzip:  3.88 kB
dist/assets/SettingsPlatforms-*.js           2.05 kB │ gzip:  0.87 kB
dist/assets/SettingsKeepAlive-*.js           2.53 kB │ gzip:  0.90 kB
dist/assets/SettingsUpdates-*.js             2.62 kB │ gzip:  1.07 kB
dist/assets/SettingsStorage-*.js             4.89 kB │ gzip:  1.70 kB
dist/assets/SettingsAbout-*.js               5.08 kB │ gzip:  1.65 kB
dist/assets/SettingsTools-*.js               5.53 kB │ gzip:  2.06 kB
dist/assets/SettingsAdvanced-*.js            8.16 kB │ gzip:  2.57 kB
dist/assets/index-*.js                     241.35 kB │ gzip: 74.04 kB
```

Chunk principal reducido de 266 KB a 241 KB. Los 7 chunks de settings se cargan solo cuando el usuario navega a esa pantalla.

---

## Archivos estables (sin cambios)

```
AppLogger.kt              ✅
BootReceiver.kt           ✅
EventBridge.kt            ✅
OpenClawService.kt        ✅
PayloadExtractor.kt       ✅
InstallValidator.kt       ✅
OpenClawManager.kt        ✅
ProotManager.kt           ✅
TerminalManager.kt        ✅
TerminalSessionManager.kt ✅
UrlResolver.kt            ✅
```

---

*Última actualización: Mayo 2026*
