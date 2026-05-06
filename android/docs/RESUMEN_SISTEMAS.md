# Resumen de sistemas — OpenClaw Android

## Componentes de instalación

### InstallationOrchestrator (punto de entrada único)

Reemplaza los antiguos `SetupManager` y `RootfsManager`. Recibe el modo y delega al instalador correcto.

```kotlin
// InstallerManager delega a InstallationOrchestrator
installerManager.install("auto", null, listener)
```

---

### PayloadInstaller — modo offline

Extrae `payload.tar.gz` bundleado en el APK.

- Extracción streaming (sin archivos temporales)
- Validación con `InstallValidator`
- Configuración de entorno con `EnvironmentConfigurator`
- Escribe `installed.json`

**Cuándo se usa**: APK con payload bundleado, sin internet necesario.

---

### TermuxBootstrapOrchestrator — modo online (entorno base)

Descarga e instala el bootstrap oficial de Termux (~50MB).

Componentes:

- `TermuxArchitectureDetector` — detecta ABI
- `TermuxBootstrapDownloader` — descarga ZIP
- `TermuxBootstrapExtractor` — extrae con permisos y symlinks
- `TermuxEnvironmentConfigurator` — configura DNS, profile
- `TermuxDpkgManager` — repara dpkg (responde N automáticamente)
- `TermuxPkgManager` — `pkg update` con reintentos
- `TermuxPackageInstaller` — instala paquetes opcionales
- `TermuxBootstrapMarker` — escribe `.termux-bootstrap-installed`

Luego el terminal ejecuta `curl -sL myopenclawhub.com/install | bash`.

**Cuándo se usa**: APK sin payload, requiere internet.

---

### ProotCommandExecutor — modo proot

Instala Ubuntu rootfs y lanza el gateway dentro de proot.

Componentes en `core/proot/`:

- `ProotBinaryDownloader`, `ProotRootfsDownloader`
- `ProotRootfsConfigurator`, `ProotCommandExecutor`

**Cuándo se usa**: Usuarios avanzados que necesitan resistencia al Phantom Process Killer.

---

### TerminalSessionManager — siempre necesario

No instala nada. Detecta qué sistema está instalado y crea la sesión con el shell correcto:

```
¿.proot-installed + openclaw-shell.sh?  → bash de Ubuntu (proot)
¿online install presente?               → bash de Termux + glibc
¿payload instalado?                     → bash/sh del payload
Fallback                                → /system/bin/sh
```

---

## Comparación de modos

|                            | Offline (payload) | Online         | Proot         |
| -------------------------- | ----------------- | -------------- | ------------- |
| **Requiere internet**      | ❌                | ✅             | ✅            |
| **Tamaño descarga**        | 0 (bundleado)     | ~50MB + online | ~80MB         |
| **Tiempo**                 | 1-2 min           | 5-10 min       | 5-7 min       |
| **pkg install**            | ✅                | ✅             | ❌ (usa apt)  |
| **Phantom Process Killer** | ⚠️                | ⚠️             | ✅ resistente |
| **Recomendado para**       | APK con payload   | Sin payload    | Avanzados     |

---

## Flujo de detección de estado

`InstallerManager.isInstalled()` verifica en orden:

1. ¿Proot instalado? → `.proot-installed` + `openclaw-shell.sh`
2. ¿Online install presente? → `node.real` + `installed.json` + `openclaw.mjs` + `ld-linux-aarch64.so.1`
3. ¿Payload instalado? → `prefix/` + `.installed`

`InstallerManager.isReady()` — entorno completamente funcional:

- Verifica que `ld-linux-aarch64.so.1` existe y tiene tamaño > 100KB
- Verifica que `node.real` existe y tiene tamaño > 1MB
- Verifica que `openclaw.mjs` existe

---

## ModernPermissionManager

Gestiona permisos con API `suspend`:

```kotlin
suspend fun requestNotifications(): Boolean
fun hasNotificationPermission(): Boolean
```

**Nota:** `MANAGE_EXTERNAL_STORAGE` y permisos de almacenamiento externo fueron eliminados. Todo funciona dentro del sandbox privado de la app.

Internamente usa `ActivityResultLaunchers` y `CompletableDeferred<Boolean>`.

---

## Seguridad — Comandos

**⚠️ Métodos `runCommand()` y `runCommandAsync` eliminados de SystemBridge**

Por seguridad, la ejecución de comandos arbitrarios desde WebView ya no está disponible:

```kotlin
// ❌ ELIMINADO — No expuesto a JavaScript
// @JavascriptInterface
// fun runCommand(cmd: String): String

// ✅ Comandos internos usan runSyncUnsafe (no expuesto)
// Solo para uso interno del sistema, nunca desde WebView
```

**Principio de seguridad:** Ningún comando shell puede ser ejecutado desde el frontend React. Todo procesamiento crítico ocurre en Kotlin nativo.
