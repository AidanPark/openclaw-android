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

| | Offline (payload) | Online | Proot |
|---|---|---|---|
| **Requiere internet** | ❌ | ✅ | ✅ |
| **Tamaño descarga** | 0 (bundleado) | ~50MB + online | ~80MB |
| **Tiempo** | 1-2 min | 5-10 min | 5-7 min |
| **pkg install** | ✅ | ✅ | ❌ (usa apt) |
| **Phantom Process Killer** | ⚠️ | ⚠️ | ✅ resistente |
| **Recomendado para** | APK con payload | Sin payload | Avanzados |

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

Gestiona todos los permisos con API `suspend`:

```kotlin
suspend fun requestStorage(): Boolean
suspend fun requestNotifications(): Boolean
suspend fun requestManageExternalStorage(): Boolean
fun hasStoragePermission(): Boolean
fun hasNotificationPermission(): Boolean
```

Internamente usa `ActivityResultLaunchers` y `CompletableDeferred<Boolean>`. No usa `onRequestPermissionsResult` (deprecado).

---

## CommandRunner — seguridad

Todos los comandos recibidos desde WebView pasan por `sanitizeCommand()`:

```kotlin
// Bloqueado
sanitizeCommand("rm -rf /")          // → null
sanitizeCommand("ls | bash")         // → null
sanitizeCommand("echo $(cat /etc/passwd)") // → null

// Permitido
sanitizeCommand("openclaw --version") // → "openclaw --version"
sanitizeCommand("ls -la")            // → "ls -la"
sanitizeCommand("git status")        // → "git status"
```

Los comandos internos del sistema usan `runSyncUnsafe` / `runStreamingUnsafe` para saltarse la sanitización.
