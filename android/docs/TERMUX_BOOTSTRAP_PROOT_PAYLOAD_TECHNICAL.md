# OpenClaw Android - Arquitectura de Instalación

## Principio Fundamental: Sandbox Completo

**⚠️ TODA la instalación y ejecución ocurre DENTRO del sandbox de la aplicación.**

```
┌─────────────────────────────────────────────────────────────┐
│                   ANDROID SYSTEM                            │
│  ┌─────────────────────────────────────────────────────┐  │
│  │          OPENCLAW APP SANDBOX                        │  │
│  │  ┌──────────────────────────────────────────────┐   │  │
│  │  │   context.getFilesDir()                       │   │  │
│  │  │   /data/data/com.openclaw.android/files/      │   │  │
│  │  │                                               │   │  │
│  │  │  ┌──────────────┐  ┌──────────────┐         │   │  │
│  │  │  │   Sistema    │  │   Sistema    │         │   │  │
│  │  │  │   Termux     │  │   Proot      │         │   │  │
│  │  │  │   (Modo 1)   │  │   (Modo 2)   │         │   │  │
│  │  │  └──────────────┘  └──────────────┘         │   │  │
│  │  │                                               │   │  │
│  │  │  ┌────────────────────────────────────────┐  │   │  │
│  │  │  │   TerminalView (WebView)              │  │   │  │
│  │  │  │   - Interfaz embebida                  │  │   │  │
│  │  │  │   - No usa app Termux externa         │  │   │  │
│  │  │  └────────────────────────────────────────┘  │   │  │
│  │  └──────────────────────────────────────────────┘   │  │
│  │                                                        │  │
│  │  ❌ NO usa apps externas                               │  │
│  │  ❌ NO accede a sistema fuera del sandbox            │  │
│  │  ❌ NO requiere permisos especiales                    │  │
│  │  ✅ Todo self-contained en filesDir/                   │  │
│  │                                                        │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Reglas del Sandbox

| Aspecto                 | Implementación                                             |
| ----------------------- | ---------------------------------------------------------- |
| **Ubicación**           | `context.getFilesDir()` - almacenamiento privado de la app |
| **Apps externas**       | ❌ Ninguna - todo embebido o descargado al sandbox         |
| **Acceso sistema**      | ❌ Ninguno - solo dentro del directorio privado            |
| **Termux App**          | ❌ No requerida - usamos bootstrap embebido                |
| **Root**                | ❌ No requerido                                            |
| **Permisos especiales** | ❌ Ninguno - solo internet para modo online                |

---

## Resumen Ejecutivo

OpenClaw Android implementa **2 sistemas principales** de ejecución:

1. **Sistema Termux** - Entorno basado en Termux (por defecto)
2. **Sistema Proot** - Mini Linux Ubuntu independiente

Estos sistemas son **MUTUAMENTE EXCLUYENTES** - solo uno puede estar activo a la vez.

---

## 1. Sistema Termux (Por Defecto)

> **🔒 Todo embebido en la app** - No requiere app Termux externa ni acceso al sistema.

### Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                    SISTEMA TERMUX                           │
│              (Todo dentro del sandbox)                       │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           Terminal Básica (SIEMPRE PRESENTE)        │  │
│  │  - Shell básica EMbebida en APK                    │  │
│  │  - Comandos simples (ls, cat, echo)                │  │
│  │  - NO requiere app Termux externa                  │  │
│  │  - NO requiere acceso a sistema                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                           ↓                                 │
│         ┌─────────────────┴─────────────────┐               │
│         ↓                                   ↓               │
│  ┌──────────────┐                   ┌──────────────┐         │
│  │   Payload    │                   │   Bootstrap │         │
│  │   (Opción A) │                   │   (Opción B)│         │
│  │              │                   │             │         │
│  │ OpenClaw     │                   │ curl, bash  │         │
│  │ EMBEBIDO     │                   │ apt, dpkg   │         │
│  │ en APK       │                   │ DESCARGADO  │         │
│  │              │                   │ al sandbox  │         │
│  │ Node, npm    │                   │ (online)    │         │
│  │ glibc        │                   │             │         │
│  └──────────────┘                   └──────────────┘         │
│                                                             │
│  ✅ Payload: Extraído de assets/ (offline)                   │
│  ✅ Bootstrap: Descargado a filesDir/ (online)               │
│  ❌ Ninguno usa apps externas                                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Flujo de Instalación Termux

```
Usuario abre app
       ↓
┌──────────────────┐
│ Terminal Básica  │ ← Siempre disponible (sin bootstrap)
│ (shell simple)   │
└──────────────────┘
       ↓
   Elige modo:
       ↓
   ┌──────────┬──────────┐
   ↓          ↓          ↓
Payload   Bootstrap   Ninguno
(Offline) (Online)    (solo terminal)
   ↓          ↓
OpenClaw   curl|bash
embebido   scripts
```

### Componentes del Sistema Termux

#### 1.1 Terminal Básico (Base)

**Siempre presente**, no requiere instalación:

- Shell nativa de Android
- Comandos básicos: `ls`, `cat`, `echo`, `pwd`
- **Limitación**: No puede ejecutar `curl`, `bash` scripts complejos, o `apt`

**Uso:** Comandos simples, navegación de archivos.

#### 1.2 Payload - OpenClaw Embebido (Opción A)

**Modo:** Offline (no requiere internet)

**Contenido embebido en APK:**

| Componente         | Descripción            | Tamaño |
| ------------------ | ---------------------- | ------ |
| `node`             | Runtime JavaScript     | ~50MB  |
| `npm`              | Gestor de paquetes     | ~10MB  |
| `glibc`            | Biblioteca C embebida  | ~40MB  |
| `openclaw.mjs`     | Core de OpenClaw       | ~5MB   |
| Scripts auxiliares | Instalación automática | ~2MB   |

**Proceso de instalación:**

1. Extraer `payload.tar.xz` desde assets
2. Colocar en `filesDir/home/payload/`
3. Configurar enlaces simbólicos
4. Escribir marcador `.payload-installed`

**Ventajas:**

- Funciona sin internet
- Instalación rápida (~30 segundos)
- OpenClaw listo inmediatamente

**Desventajas:**

- Aumenta tamaño APK (+167MB)
- No actualizable (requiere nuevo APK)
- Menos flexibilidad

**Archivo:** `@PayloadAssetResolver.kt`, `@PayloadInstaller.kt`

#### 1.3 Termux Bootstrap (Opción B)

**Modo:** Online (requiere internet)

**Descarga desde:** GitHub/Termux packages (~50MB)

**Provee:**

| Herramienta  | Propósito                  |
| ------------ | -------------------------- |
| `curl`       | Descargar scripts          |
| `bash`       | Ejecutar scripts complejos |
| `apt`/`dpkg` | Gestor de paquetes         |
| `git`        | Control de versiones       |
| `wget`       | Descarga alternativa       |

**Proceso de instalación:**

1. Detectar arquitectura (aarch64/arm/x86_64)
2. Descargar `bootstrap-aarch64.zip`
3. Extraer a `filesDir/usr/`
4. Configurar DNS (`resolv.conf`)
5. Ejecutar `pkg update`
6. Escribir marcador `.termux-bootstrap-installed`

**Ventajas:**

- APK pequeño
- Actualizable (`pkg update`)
- Flexibilidad total
- Compatible con scripts estándar

**Desventajas:**

- Requiere internet
- Instalación más lenta (2-3 min)
- Afectado por Phantom Process Killer

**Archivo:** `@TermuxBootstrapManager.kt`, `@TermuxBootstrapOrchestrator.kt`

### Mutua Exclusividad en Termux

```kotlin
// Ejemplo: Intentar instalar Payload cuando existe Bootstrap
val bootstrapInstalled = File(filesDir, ".termux-bootstrap-installed").exists()
val payloadInstalled = File(filesDir, ".payload-installed").exists()

if (bootstrapInstalled && wantToInstallPayload) {
    // ERROR: No permitido
    throw IllegalStateException(
        "Cannot install Payload: Termux Bootstrap is active. " +
        "Uninstall Bootstrap first or use Proot system."
    )
}
```

---

## 2. Sistema Proot (Alternativa)

> **🔒 Todo embebido/descargado al sandbox** - Mini Linux Ubuntu completamente aislado dentro de la app.

### Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                    SISTEMA PROOT                            │
│              (Todo dentro del sandbox)                       │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │            Mini Linux Ubuntu Completo               │  │
│  │            (Aislado en filesDir/)                   │  │
│  │                                                     │  │
│  │  ┌──────────────┐  ┌──────────────┐                 │  │
│  │  │   proot      │  │  Ubuntu      │                 │  │
│  │  │  EMBEBIDO   │  │  rootfs      │                 │  │
│  │  │  o          │  │  (~80MB)     │                 │  │
│  │  │  DESCARGADO │  │  DESCARGADO  │                 │  │
│  │  │  al sandbox │  │  al sandbox  │                 │  │
│  │  └──────────────┘  └──────────────┘                 │  │
│  │          ↓                  ↓                       │  │
│  │     ┌─────────────────────────────┐                 │  │
│  │     │     Entorno Aislado         │                 │  │
│  │     │  - bash, curl, apt          │                 │  │
│  │     │  - Sistema de archivos      │                 │  │
│  │     │    Ubuntu completo          │                 │  │
│  │     │  - Resistente a Phantom     │                 │  │
│  │     │    Process Killer           │                 │  │
│  │     └─────────────────────────────┘                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ⚠️  SI SE USA PROOT, TERMUX SE DESACTIVA COMPLETAMENTE   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Características

| Aspecto         | Descripción                           |
| --------------- | ------------------------------------- |
| **Base**        | Ubuntu mini rootfs (~80MB)            |
| **Runtime**     | proot binario estático (NDK)          |
| **Instalación** | Online (descarga rootfs)              |
| **Aislamiento** | Completo - sistema de archivos propio |
| **Ventaja**     | Resistente a Phantom Process Killer   |

### Instalación Proot

1. Descargar `proot` binario estático (Termux packages)
2. Descargar `ubuntu-rootfs.tar.xz` (mini Ubuntu ~80MB)
3. Extraer rootfs a `filesDir/ubuntu-rootfs/`
4. Escribir marcador `.proot-installed`

**Archivo:** `@ProotManager.kt`, `@ProotRootfsConfigurator.kt`

### Exclusión Total de Termux

Cuando Proot está activo:

- ❌ Terminal básico Termux no se usa
- ❌ Payload no está disponible
- ❌ Termux Bootstrap no está disponible
- ✅ Todo se ejecuta dentro del rootfs Ubuntu

```kotlin
// Verificación de exclusividad
fun hasConflictingSystem(): Boolean {
    val prootInstalled = File(filesDir, ".proot-installed").exists()

    if (prootInstalled) {
        // Bloquear cualquier instalación Termux
        return true
    }
    return false
}
```

---

## 3. Matriz de Compatibilidad

### Sistemas Mutuamente Excluyentes

```
                    Terminal
                      Básica
                        │
            ┌───────────┴───────────┐
            ↓                       ↓
    ┌──────────────┐      ┌──────────────┐
    │   Termux     │      │    Proot     │
    │   Sistema    │      │   Sistema    │
    │   (Modo 1)   │      │   (Modo 2)   │
    └──────────────┘      └──────────────┘
         │                       │
    ┌────┴────┐                  │
    ↓         ↓                  │
 Payload   Bootstrap             │
(Offline) (Online)               │
    │         │                  │
    └────┬────┘                  │
         │                       │
    ❌ CONFLICTO           ❌ CONFLICTO
    (solo uno)            (Termux vs Proot)
```

### Tabla de Conflictos

| Si está instalado... | No se puede instalar... | Mensaje de error                    |
| -------------------- | ----------------------- | ----------------------------------- |
| Termux Bootstrap     | Payload                 | "Uninstall Bootstrap first"         |
| Payload              | Termux Bootstrap        | "Uninstall Payload first"           |
| Proot                | Termux (cualquiera)     | "Proot active. Uninstall first"     |
| Termux Bootstrap     | Proot                   | "Bootstrap active. Uninstall first" |

---

## 4. API de Instalación

### InstallationOrchestrator

```kotlin
class InstallationOrchestrator(private val context: Context) {

    sealed class InstallationMode {
        object TermuxBootstrap : InstallationMode()  // curl, bash, apt
        object ProotUbuntu : InstallationMode()    // Ubuntu mini
        object OfflinePayload : InstallationMode() // OpenClaw embebido
        object Force : InstallationMode()        // Forzar reinstalación
    }

    suspend fun install(
        mode: String,
        customUri: Uri? = null,
        listener: ProgressListener
    )
}
```

### Uso por Modo

```kotlin
// Modo 1A: Payload (OpenClaw offline embebido)
orchestrator.install("offline", null, listener)

// Modo 1B: Termux Bootstrap (curl, bash, apt online)
orchestrator.install("termux-bootstrap", null, listener)

// Modo 2: Proot Ubuntu (mini Linux)
orchestrator.install("proot", null, listener)

// Forzar limpieza y reinstalar
orchestrator.install("force", null, listener)
```

### Validación de Conflictos

```kotlin
private fun hasConflictingSystem(): Boolean {
    val termuxInstalled = File(context.filesDir, ".termux-bootstrap-installed").exists() ||
                         File(context.filesDir, "usr/bin/bash").exists()

    val prootInstalled = File(context.filesDir, ".proot-installed").exists() ||
                          File(context.filesDir, "ubuntu-rootfs").exists()

    val payloadInstalled = File(context.filesDir, ".payload-installed").exists() &&
                          File(context.filesDir, "home/payload").exists()

    // Más de uno instalado = conflicto
    return (termuxInstalled && prootInstalled) ||
           (termuxInstalled && payloadInstalled) ||
           (prootInstalled && payloadInstalled)
}
```

---

## 5. Estados del Sistema

### Diagrama de Estados

```
┌──────────────┐
│   VACÍO      │ ← Estado inicial
│ (sin sistema)│
└──────┬───────┘
       │
   ┌───┴───┬──────────┐
   ↓       ↓          ↓
Payload Bootstrap   Proot
   │       │          │
   └───────┴──────────┘
           │
           ↓
    ┌──────────────┐
    │  INSTALADO   │
    │  (un solo    │
    │   sistema)   │
    └──────────────┘
```

### Detección de Estado

```kotlin
fun detectSource(): String {
    return when {
        File(filesDir, ".proot-installed").exists() -> "proot"
        File(filesDir, ".termux-bootstrap-installed").exists() -> "termux-bootstrap"
        File(filesDir, ".payload-installed").exists() -> "payload"
        else -> "none"
    }
}
```

---

## 6. Casos de Uso

### Caso 1: Usuario quiere OpenClaw rápido sin internet

```
Estado inicial: VACÍO
Acción: Instalar Payload
Resultado: OpenClaw disponible offline
Terminal: Básica (Payload no afecta terminal)
```

### Caso 2: Usuario quiere usar scripts de instalación online

```
Estado inicial: VACÍO
Acción: Instalar Termux Bootstrap
Resultado: curl, bash, apt disponibles
Terminal: Completa con herramientas Termux
Puede ejecutar: curl -sL ejemplo.com/install | bash
```

### Caso 3: Usuario quiere sistema resistente (Phantom Process Killer)

```
Estado inicial: VACÍO
Acción: Instalar Proot
Resultado: Ubuntu mini aislado
Terminal: Proot (Termux desactivado)
Resistencia: ✅ Phantom Process Killer no afecta
```

### Caso 4: Usuario quiere cambiar de sistema

```
Estado inicial: Termux Bootstrap instalado
Acción: Clean install → Instalar Proot
Pasos:
  1. InstallationOrchestrator.cleanInstallation()
  2. InstallationOrchestrator.install("proot", ...)
Resultado: Proot activo, Termux eliminado
```

---

## 7. Referencia de Archivos

### Marcadores de Instalación

| Archivo                       | Sistema          | Significado                      |
| ----------------------------- | ---------------- | -------------------------------- |
| `.termux-bootstrap-installed` | Termux Bootstrap | Bootstrap oficial instalado      |
| `.payload-installed`          | Payload          | OpenClaw embebido listo          |
| `.proot-installed`            | Proot            | Ubuntu rootfs activo             |
| `.rootfs-extracted`           | Proot (legado)   | Rootfs extraído (compatibilidad) |

### Estructura de Directorios

```
context.filesDir/
├── usr/                          ← Termux Bootstrap (si instalado)
│   ├── bin/bash                 ← Shell completa
│   ├── bin/curl                 ← Descarga HTTP
│   ├── bin/apt                  ← Gestor paquetes
│   └── ...
│
├── home/payload/                 ← Payload OpenClaw (si instalado)
│   ├── bin/node                 ← Node.js embebido
│   ├── glibc/lib/               ← Bibliotecas C
│   └── lib/node_modules/        ← OpenClaw + dependencias
│
├── ubuntu-rootfs/                ← Proot rootfs (si instalado)
│   ├── bin/bash                 ← Ubuntu bash
│   ├── usr/bin/apt              │   Ubuntu apt
│   └── ...                      ← Sistema Ubuntu completo
│
└── bin/proot                     ← Proot binario (si instalado)
```

---

## 8. Seguridad y Validaciones

### Validaciones Implementadas

```kotlin
// En InstallationOrchestrator.kt
when (installationMode) {
    is InstallationMode.TermuxBootstrap -> {
        if (!isForce && hasConflictingSystem()) {
            listener.onError(
                "Cannot install Termux: ${getConflictMessage()}",
                null
            )
            return
        }
        installTermuxBootstrap(listener)
    }
    // ... similar para otros modos
}
```

### Mensajes de Conflicto

| Sistema Existente | Mensaje al Usuario                                            |
| ----------------- | ------------------------------------------------------------- |
| Termux Bootstrap  | "Termux Bootstrap is already installed. Uninstall it first."  |
| Proot             | "Proot is already installed. Uninstall it first."             |
| Payload           | "Payload (offline) is already installed. Uninstall it first." |

---

## 9. Resumen de Decisiones de Diseño

### Por qué 2 sistemas principales?

| Sistema    | Propósito                      | Caso de uso ideal                        |
| ---------- | ------------------------------ | ---------------------------------------- |
| **Termux** | Entorno estándar Android/Linux | Desarrollo, scripts, flexibilidad        |
| **Proot**  | Aislamiento + resistencia      | Producción, procesos largos, estabilidad |

### Por qué Payload + Bootstrap son excluyentes?

- **Payload** = OpenClaw específico, embebido, offline
- **Bootstrap** = Entorno genérico, online, extensible
- Si ambos coexisten: conflicto de rutas, variables de entorno, y mantenimiento

### Por qué Proot excluye Termux?

- Proot provee su **propio** sistema de archivos Ubuntu
- Termux y Proot tienen diferentes estructuras de directorios
- Mejor aislamiento = mayor estabilidad

---

## 10. Sandbox y Aislamiento (Seguridad)

### Principio: Todo Dentro de la App

```
❌ NO requiere app Termux instalada en el dispositivo
❌ NO requiere root o permisos especiales
❌ NO accede a /system o directorios externos
✅ TODO funciona en /data/data/com.openclaw.android/files/
```

### Estructura de Aislamiento

```
/data/data/com.openclaw.android/
├── files/                    ← SANDOX PRINCIPAL
│   ├── usr/                 ← Termux Bootstrap (si instalado)
│   ├── home/payload/        ← Payload OpenClaw (si instalado)
│   ├── ubuntu-rootfs/       ← Proot rootfs (si instalado)
│   └── bin/proot            ← Proot binario (si instalado)
│
├── cache/                   ← Descargas temporales
├── shared_prefs/           ← Configuración Android
└── databases/              ← Datos de app
```

### Permisos Requeridos

| Permiso              | Uso                             | Requerido para        |
| -------------------- | ------------------------------- | --------------------- |
| `INTERNET`           | Descargar Bootstrap/Proot       | Modo Online           |
| `FOREGROUND_SERVICE` | Mantener procesos en background | Ejecución persistente |

**NO requiere:**

- ❌ `MANAGE_EXTERNAL_STORAGE` (eliminado)
- ❌ `WRITE_EXTERNAL_STORAGE`
- ❌ Root
- ❌ Acceso a `/system/bin`

### Comparación: OpenClaw vs App Termux Externa

| Aspecto            | OpenClaw (Embedded)         | App Termux (Externa)   |
| ------------------ | --------------------------- | ---------------------- |
| **Instalación**    | Un solo APK                 | App separada requerida |
| **Sandbox**        | Aislado en filesDir/        | Directorio propio      |
| **Dependencia**    | Ninguna externa             | Requiere app instalada |
| **Tamaño inicial** | Pequeño (descarga opcional) | Grande (completa)      |
| **Control**        | Total por la app            | Limitado por OS        |

---

## 11. Archivos Fuente

| Componente           | Archivos                                                                                                                         |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| **Termux Bootstrap** | `@TermuxBootstrapManager.kt`, `@TermuxBootstrapOrchestrator.kt`, `@TermuxBootstrapDownloader.kt`, `@TermuxBootstrapExtractor.kt` |
| **Payload**          | `@PayloadAssetResolver.kt`, `@PayloadInstaller.kt`, `@InstallationOrchestrator.kt`                                               |
| **Proot**            | `@ProotManager.kt`, `@ProotPathResolver.kt`, `@ProotCommandBuilder.kt`                                                           |
| **Orquestación**     | `@InstallationOrchestrator.kt` (validaciones de conflicto)                                                                       |

---

_*Documentación generada para OpenClaw Android - Arquitectura de 2 Sistemas (Termux vs Proot) - Todo dentro del Sandbox*_
