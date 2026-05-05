# Implementación Completa — OpenClaw Android

> Este documento describe el estado actual del sistema tras la refactorización completa.
> Para el historial de cambios, ver [REFACTORING_PLAN.md](REFACTORING_PLAN.md).

---

## Sistema de instalación

El sistema de instalación está unificado en `InstallationOrchestrator` con tres modos:

### Modo offline (payload bundleado)

`PayloadInstaller` extrae `payload.tar.gz` de los assets del APK:

1. Extracción streaming (sin archivos temporales, sin saturar RAM)
2. `InstallValidator` verifica `node.real`, `openclaw.mjs`, `ld-linux-aarch64.so.1`
3. `EnvironmentConfigurator` configura DNS y SSL
4. `InstallMarkerWriter` escribe `installed.json`
5. `ScriptWriter` genera `openclaw-start.sh`

### Modo online (curl | bash)

`TermuxBootstrapOrchestrator` instala el entorno base:

1. `TermuxArchitectureDetector` detecta ABI del dispositivo
2. `TermuxBootstrapDownloader` descarga `bootstrap-<arch>.zip` (~50MB)
3. `TermuxBootstrapExtractor` extrae con permisos correctos y symlinks
4. `TermuxEnvironmentConfigurator` configura DNS, profile, resolv.conf
5. `TermuxDpkgManager` repara dpkg con `yes N | dpkg --configure -a --force-confold`
6. `TermuxPkgManager` ejecuta `pkg update` con hasta 3 reintentos
7. `TermuxPackageInstaller` instala paquetes opcionales (git, curl, wget)
8. `TermuxBootstrapMarker` escribe `.termux-bootstrap-installed`

Luego el terminal ejecuta `curl -sL myopenclawhub.com/install | bash`.

### Modo proot (Ubuntu rootfs)

Componentes en `core/proot/`:

1. `ProotBinaryDownloader` descarga el binario proot
2. `ProotRootfsDownloader` descarga Ubuntu rootfs (~80MB)
3. `ProotRootfsConfigurator` configura el entorno Ubuntu
4. `ProotCommandExecutor` lanza el gateway dentro del contenedor proot

---

## Sistema de permisos

`ModernPermissionManager` gestiona todos los permisos con API `suspend`:

```kotlin
// En ActivityInitializer.requestInitialPermissions()
CoroutineScope(Dispatchers.Main).launch {
    permissionManager.requestStorage()      // suspend — espera resultado
    permissionManager.requestNotifications() // suspend — espera resultado
    (activity as? MainActivity)?.onStoragePermissionsGranted()
}
```

Internamente usa `CompletableDeferred<Boolean>` para conectar los `ActivityResultLaunchers` con las corutinas. No usa `onRequestPermissionsResult` (deprecado).

---

## Sistema de bridge

`JsBridgeFacade` expone 50+ métodos `@JavascriptInterface` organizados en 5 dominios:

| Bridge | Responsabilidad |
|---|---|
| `TerminalBridge` | show/hide, sesiones, write |
| `SetupBridge` | estado instalación, triggers |
| `PlatformBridge` | plataformas |
| `ToolsBridge` | herramientas CLI |
| `SystemBridge` | app info, batería, almacenamiento, OTA, comandos |

Más `batchQuery(callbackId, requests)` para múltiples consultas en una llamada.

---

## Sistema de seguridad

`CommandRunner.sanitizeCommand()` valida todos los comandos recibidos desde WebView:

- Longitud máxima: 10.000 caracteres
- Patrones de inyección bloqueados: `| sh`, `| bash`, `&& rm -rf`, `` `...` ``, `$(...)`, etc.
- Whitelist de ~40 comandos permitidos: `openclaw`, `node`, `npm`, `git`, `apt`, `pkg`, `curl`, `ls`, `cat`, `tar`, etc.

Los comandos internos del sistema usan `runSyncUnsafe` / `runStreamingUnsafe`.

---

## Frontend React

### Estado centralizado

`AppContext` es la única fuente de verdad:

```typescript
const { setupStatus, envInfo, storageInfo, installedTools, refresh } = useAppContext()
```

Se refresca automáticamente en eventos nativos: `session_changed`, `setup_progress`, `install_progress`.

### Code splitting

7 pantallas de settings se cargan bajo demanda con `lazy + Suspense`:

```
dist/assets/SettingsPlatforms-*.js    2.05 kB
dist/assets/SettingsKeepAlive-*.js    2.53 kB
dist/assets/SettingsUpdates-*.js      2.62 kB
dist/assets/SettingsStorage-*.js      4.89 kB
dist/assets/SettingsAbout-*.js        5.08 kB
dist/assets/SettingsTools-*.js        5.53 kB
dist/assets/SettingsAdvanced-*.js     8.16 kB
dist/assets/index-*.js              241.35 kB  (chunk principal)
```

### Componentes memoizados

- `Dashboard`: `RuntimeItem`, `CommandRow`, `QuickAction`, `DashboardSkeleton`
- `Setup`: `TipCard`, `Stepper`
- `SettingsTools`: `ToolCard`, `ConfirmDialog`

---

## Build y verificación

```bash
# Android
cd android
./gradlew assembleDebug
./gradlew test

# Frontend
cd android/www
npm run build   # tsc -b && vite build
npm test        # vitest --run
```

Ambos deben completar sin errores antes de cualquier release.
