# Documentación Técnica — OpenClaw Android

## Índice

| Documento | Descripción |
|-----------|-------------|
| [INSTALLATION_FLOW.md](INSTALLATION_FLOW.md) | Flujo completo de instalación, todos los modos, diagramas |
| [DPKG_FIX.md](DPKG_FIX.md) | Manejo del prompt interactivo de dpkg --configure -a |
| [ENVIRONMENT_VARS.md](ENVIRONMENT_VARS.md) | Variables de entorno críticas y por qué son necesarias |
| [PAYLOAD_ASSET.md](PAYLOAD_ASSET.md) | Qué es payload.tar.gz, cómo se gestiona y extrae |

## Resumen rápido

### Flujo de instalación

```
InstallationOrchestrator
    │
    ├── Modo offline  → PayloadInstaller (payload.tar.gz bundleado en APK)
    ├── Modo online   → TermuxBootstrapOrchestrator + curl | bash en terminal
    └── Modo proot    → ProotRootfsDownloader + ProotCommandExecutor
```

### Modos disponibles

| Modo | Cuándo usar |
|------|-------------|
| `auto` | APK con payload bundled — instalación sin internet |
| `online` | APK sin payload — instala OpenClaw desde internet |
| `proot` | Usuarios avanzados, resistencia a Phantom Process Killer |

### Archivos de marcador

| Archivo | Indica |
|---------|--------|
| `.termux-bootstrap-installed` | Bootstrap de Termux instalado |
| `.installed` | Payload de OpenClaw instalado |
| `.proot-installed` | Ubuntu via proot instalado |
| `home/.openclaw-android/installed.json` | Instalación online completada |

### Problema más común

**dpkg --configure -a se cuelga** → Ver [DPKG_FIX.md](DPKG_FIX.md)

Solución rápida:
```bash
yes N | dpkg --configure -a --force-confold
```

### Arquitectura de permisos

`ModernPermissionManager` gestiona todos los permisos con API `suspend`:

```kotlin
// Solicitar almacenamiento (Android 11+: MANAGE_EXTERNAL_STORAGE)
val granted = permissionManager.requestStorage()

// Solicitar notificaciones (Android 13+)
val granted = permissionManager.requestNotifications()
```

### Bridge batch

Para obtener múltiples estados en una sola llamada:

```typescript
const results = await bridge.batchCall([
  'getSetupStatus',
  'getEnvironmentInfo',
  'getStorageInfo',
])
```

Equivale a llamar `window.OpenClaw.batchQuery(callbackId, JSON.stringify([...]))` y esperar el evento `native:batch_result`.
