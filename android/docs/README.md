# Documentación Técnica — OpenClaw Android

> **⚠️ Todo funciona dentro del sandbox de la app** — No requiere apps externas, todo está embebido o descargado a `context.getFilesDir()`.

---

## 📚 Índice de Documentos

### Documentación Principal

| Documento                                                                                      | Descripción                                                                                    |
| ---------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **[TERMUX_BOOTSTRAP_PROOT_PAYLOAD_TECHNICAL.md](TERMUX_BOOTSTRAP_PROOT_PAYLOAD_TECHNICAL.md)** | 🎯 **Documento principal** — Arquitectura completa de 2 sistemas (Termux vs Proot) con sandbox |
| [ARQUITECTURA_VISUAL.md](ARQUITECTURA_VISUAL.md)                                               | Diagramas visuales de la arquitectura de la app                                                |
| [RESUMEN_SISTEMAS.md](RESUMEN_SISTEMAS.md)                                                     | Resumen conciso de componentes de instalación                                                  |

### Instalación y Flujos

| Documento                                    | Descripción                                     |
| -------------------------------------------- | ----------------------------------------------- |
| [INSTALLATION_FLOW.md](INSTALLATION_FLOW.md) | Flujo detallado de instalación, todos los modos |
| [PAYLOAD_ASSET.md](PAYLOAD_ASSET.md)         | Gestión de payload.tar.gz embebido en APK       |
| [ASSETS_STRUCTURE.md](ASSETS_STRUCTURE.md)   | Estructura de archivos en assets/               |

### Solución de Problemas

| Documento                                  | Descripción                                 |
| ------------------------------------------ | ------------------------------------------- |
| [DPKG_FIX.md](DPKG_FIX.md)                 | Manejo de `dpkg --configure -a` interactivo |
| [ENVIRONMENT_VARS.md](ENVIRONMENT_VARS.md) | Variables de entorno críticas               |
| [GLIBC_COMPAT_JS.md](GLIBC_COMPAT_JS.md)   | Compatibilidad glibc/JavaScript             |

---

## 🏗️ Arquitectura en 2 Sistemas

```
┌─────────────────────────────────────────────────────────────┐
│                    OPENCLAW ANDROID                         │
│                   (Todo en sandbox)                          │
│                                                             │
│  ┌─────────────────────┐    ┌─────────────────────┐        │
│  │  SISTEMA TERMUX     │    │  SISTEMA PROOT      │        │
│  │  (Por defecto)      │    │  (Alternativa)      │        │
│  │                     │    │                     │        │
│  │  Terminal básica    │    │  Ubuntu mini        │        │
│  │  + Payload (offline)│    │  + proot            │        │
│  │  o Bootstrap (online│   │  (~80MB)            │        │
│  └─────────────────────┘    └─────────────────────┘        │
│                                                             │
│  ⚠️ Sistemas MUTUAMENTE EXCLUYENTES — Solo uno activo      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Sistema Termux (2 opciones)

| Opción        | Modo    | Contenido                   | Uso                      |
| ------------- | ------- | --------------------------- | ------------------------ |
| **Payload**   | Offline | OpenClaw + Node embebido    | Sin internet, rápido     |
| **Bootstrap** | Online  | curl, bash, apt descargados | Scripts online, flexible |

### Sistema Proot

| Característica | Descripción                         |
| -------------- | ----------------------------------- |
| **Base**       | Ubuntu mini rootfs                  |
| **Runtime**    | proot binario estático              |
| **Ventaja**    | Resistente a Phantom Process Killer |
| **Tamaño**     | ~80MB descargados                   |

---

## 📋 Referencia Rápida

### Modos de Instalación

```kotlin
// Modo Payload (offline, embebido en APK)
orchestrator.install("offline", null, listener)

// Modo Termux Bootstrap (online, curl/bash/apt)
orchestrator.install("termux-bootstrap", null, listener)

// Modo Proot (Ubuntu mini, resistente a Phantom Killer)
orchestrator.install("proot", null, listener)

// Forzar reinstalación limpia
orchestrator.install("force", null, listener)
```

### Archivos de Marcador

| Archivo                       | Sistema          | Significado                 |
| ----------------------------- | ---------------- | --------------------------- |
| `.payload-installed`          | Payload          | OpenClaw embebido listo     |
| `.termux-bootstrap-installed` | Termux Bootstrap | Bootstrap oficial instalado |
| `.proot-installed`            | Proot            | Ubuntu rootfs activo        |

### Permisos Requeridos

| Permiso              | Uso                                          |
| -------------------- | -------------------------------------------- |
| `INTERNET`           | Descargar Bootstrap/Proot (solo modo online) |
| `FOREGROUND_SERVICE` | Mantener procesos en background              |

**Eliminados (no requeridos):**

- ❌ `MANAGE_EXTERNAL_STORAGE`
- ❌ `WRITE_EXTERNAL_STORAGE`
- ❌ Root

---

## 🔧 Solución de Problemas Comunes

### dpkg --configure -a se cuelga

```bash
yes N | dpkg --configure -a --force-confold
```

Ver [DPKG_FIX.md](DPKG_FIX.md) para detalles.

### Conflicto de sistemas

```
Error: "Cannot install Termux: Proot system is already installed"
Solución: Desinstalar primero: InstallationOrchestrator.cleanInstallation()
```

---

## 📝 Notas de Diseño

- **Todo embebido**: Ningún componente requiere apps externas
- **Sandbox completo**: Todo en `context.getFilesDir()`
- **Validaciones**: Bloqueo automático de instalación sobre otro sistema
- **Exclusión mutua**: Termux y Proot no coexisten

---

_Para información completa de arquitectura, ver [TERMUX_BOOTSTRAP_PROOT_PAYLOAD_TECHNICAL.md](TERMUX_BOOTSTRAP_PROOT_PAYLOAD_TECHNICAL.md)_
