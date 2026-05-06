# Documentación Técnica de OpenClaw-Android

Bienvenido a la documentación técnica exhaustiva de **OpenClaw-Android**. Este documento está diseñado para proporcionar un entendimiento profundo de la arquitectura, estructura de archivos y flujos de ejecución internos del proyecto. Está dirigido tanto a usuarios avanzados que desean comprender qué ocurre bajo el capó, como a desarrolladores interesados en contribuir.

---

## 1. Descripción general del proyecto

**OpenClaw-Android** es una solución que permite ejecutar la plataforma OpenClaw (un entorno para agentes y herramientas de IA) directamente en dispositivos Android, con una configuración mínima y sin necesidad de realizar _root_ en el dispositivo.

**🔒 Principio Fundamental: Sandbox Completo**

Todo funciona **dentro del sandbox de la aplicación** (`context.getFilesDir()`). No requiere:

- ❌ App de Termux instalada en el dispositivo
- ❌ Acceso root
- ❌ Permisos de almacenamiento externo (`MANAGE_EXTERNAL_STORAGE` eliminado)
- ❌ Acceso a `/system` o directorios externos

**El problema que resuelve:**
Típicamente, para ejecutar herramientas complejas de Linux en Android, se requería instalar una distribución de Linux completa (1-2 GB) o depender de apps externas como Termux. OpenClaw-Android descarta ambos enfoques.

**Filosofía y Enfoque:**

1. **Sandbox autocontenido**: Todo en `/data/data/com.openclaw.android/files/`
2. **2 Sistemas principales** (mutuamente excluyentes):
   - **Sistema Termux**: Terminal básica + Payload (offline) o Bootstrap (online con curl/bash/apt)
   - **Sistema Proot**: Ubuntu mini aislado, resistente a Phantom Process Killer
3. **glibc-runner**: Entorno mínimo con solo el enlazador dinámico GNU (`ld.so`)
4. **Velocidad nativa**: ~200MB, 3-10 min instalación, sin capas de emulación

---

## 2. Arquitectura de alto nivel

El proyecto implementa **2 sistemas principales** dentro de un sandbox autocontenido. Todo ocurre en `context.getFilesDir()` sin depender de apps externas.

```mermaid
flowchart TD
    subgraph Android["Android System"]
        subgraph Sandbox["OpenClaw App Sandbox"]
            direction TB

            subgraph AppLayer["App Layer"]
                UI[WebView React SPA\nDashboard + Configuración]
                Kotlin[Kotlin Core\nJsBridge + Servicios]
                Term[TerminalView\nEmulador PTY Nativo]
                UI <-->|JsBridge / EventBridge| Kotlin
                Kotlin <-->|Native calls| Term
            end

            subgraph SystemTermux["Sistema Termux"]
                direction TB
                TermuxCore[Terminal Base\nSiempre presente]
                Payload[Payload\nOpenClaw embebido]
                Bootstrap[Bootstrap\ncurl/bash/apt online]
                TermuxCore --> Payload
                TermuxCore --> Bootstrap
            end

            subgraph SystemProot["Sistema Proot"]
                ProotBin[proot binario\nestático]
                Rootfs[Ubuntu mini\nrootfs (~80MB)]
                ProotBin --> Rootfs
            end

            AppLayer --> SystemTermux
            AppLayer --> SystemProot
        end
    end

    style Sandbox fill:#e1f5fe
    style SystemTermux fill:#fff3e0
    style SystemProot fill:#f3e5f5
```

### 2 Sistemas Mutuamente Excluyentes

| Sistema    | Componentes                       | Cuándo usar                | Internet                   |
| ---------- | --------------------------------- | -------------------------- | -------------------------- |
| **Termux** | Terminal base + Payload/Bootstrap | Uso general, desarrollo    | Payload: ❌, Bootstrap: ✅ |
| **Proot**  | proot + Ubuntu mini rootfs        | Resistencia Phantom Killer | ✅                         |

**Regla de exclusión**: Solo un sistema activo a la vez. La app valida y bloquea instalación de uno sobre otro.

### Componentes principales

1. **Terminal Base**: Siempre presente, embebida en la app. Comandos simples sin instalación.
2. **Payload**: OpenClaw + Node embebido en APK, extracción offline rápida.
3. **Termux Bootstrap**: Descarga curl/bash/apt al sandbox para scripts online.
4. **Proot**: Ubuntu mini aislado completo, máxima estabilidad.
5. **glibc-runner**: Enlazador dinámico que permite ejecutar Node.js Linux en Android.

---

## 3. Estructura del proyecto

El repositorio sigue un enfoque modular, separando la infraestructura de instalación de la aplicación nativa Android.

```text
openclaw-android/
├── .github/          # Workflows de CI/CD para GitHub Actions
├── .githooks/        # Hooks de Git para automatización pre-commit
├── .vscode/          # Configuración recomendada para Visual Studio Code
├── android/          # Código fuente de la Aplicación Android Nativa (APK)
├── docs/             # Documentación, guías y READMEs multi-idioma
├── patches/          # Parches de compatibilidad para ejecución nativa (glibc)
├── platforms/        # Instaladores y configuración por plataforma (openclaw)
├── scripts/          # Lógica modular, respaldos y utilidades de sistema
├── tests/            # Scripts de verificación de integridad
├── install.sh        # Orquestador principal (Modo Termux)
├── oa.sh             # CLI principal (compatible con App y Termux)
└── update.sh         # Script de actualización de alto nivel
└── ...               # Otros archivos de configuración (Gradle, Git, Licencias)
```

---

## 4. Guía detallada por carpetas

### `android/` (App Android Nativa)

**🔒 Sandbox Autocontenido**: Todo funciona dentro de `context.getFilesDir()`. No requiere app Termux externa.

- **Estructura Interna:** `app/src/main/java/com/openclaw/android/`
- **Clases Kotlin principales:**
  - `MainActivity.kt`: Contenedor principal con fachada delgada que delega a componentes especializados.
  - `InstallationOrchestrator.kt`: **Orquestador unificado** de los 2 sistemas (Termux con Payload/Bootstrap, y Proot). Gestiona validaciones de exclusión mutua.
  - `TermuxBootstrapManager.kt`: Fachada para instalación del bootstrap de Termux (online, curl/bash/apt).
  - `ProotManager.kt`: Gestión del sistema Proot (Ubuntu mini aislado).
  - `PayloadAssetResolver.kt` / `PayloadInstaller.kt`: Extracción del payload embebido en APK.
  - `JsBridgeFacade.kt`: API segura WebView↔Kotlin. **⚠️ Métodos `runCommand()` eliminados por seguridad**.
  - `SystemBridge.kt`: Info de sistema sin comandos shell expuestos.
  - `TerminalSessionManager.kt`: 3 modos de terminal: proot / online install / payload.
  - `GlibcRunner.kt`: Ejecutor de ELF via `ld-linux-aarch64.so.1`.

- **Seguridad:**
  - ❌ `runCommand()` / `runCommandAsync()` eliminados de bridges
  - ❌ `MANAGE_EXTERNAL_STORAGE` eliminado del manifest
  - ✅ Todo en sandbox privado
  - ✅ Coroutines con `Dispatchers.IO` para operaciones bloqueantes

- **Interfaz WebView React (`android/www/`):** SPA con HashRouter (compatible `file://`), sistema OTA para actualizaciones atómicas.
- **Terminal PTY (`terminal-emulator/` y `terminal-view/`):** Emulador nativo basado en fork de ReTerminal.

### `.github/`

Contiene los flujos de trabajo de GitHub Actions (`workflows/`) como `android-build.yml` (construye el APK y publica releases) y `code-quality.yml` (analiza la calidad del código, linting, tests). También incluye la configuración de Dependabot para mantener actualizadas las dependencias del proyecto.

### `.githooks/`

Aloja scripts que se ejecutan automáticamente durante el ciclo de vida de Git. El archivo `pre-commit` asegura que los estándares de código, linters y validaciones de seguridad se ejecuten antes de permitir un commit.

### `.vscode/`

Contiene los ajustes específicos para Visual Studio Code (`settings.json`), configurando reglas de formateo y validación de sintaxis para los diferentes lenguajes utilizados en el repositorio.

### `docs/`

Carpeta de documentación complementaria y recursos visuales. Incluye:

- `disable-phantom-process-killer.md`: Guía crítica para evitar que Android mate procesos pesados en background (Phantom Process Killer).
- `termux-ssh-guide.md`: Instrucciones para acceder al entorno vía SSH.
- `troubleshooting.md`: Guía de solución de problemas comunes.
- `images/`: Recursos gráficos como capturas de pantalla de la aplicación y diagramas.

### `patches/`

Contiene archivos vitales para asegurar que las aplicaciones Linux se ejecuten sin problemas dentro del contenedor glibc en Android.

- `glibc-compat.js`: Inyectado en Node.js para mitigar fallos específicos de resolución de red, paths o variables de entorno cuando se corre bajo glibc-runner.
- `argon2-stub.js`: Modifica o salta la compilación de argon2 (utilizada por code-server) que comúnmente falla al compilar dependencias nativas en la arquitectura del teléfono.
- `systemctl`: Un script "stub" (falso) para aplicaciones que intentan usar `systemd` para manejar demonios, devolviendo códigos de éxito falsos para que la instalación no falle.
- `termux-compat.h` / `spawn.h`: Cabeceras C inyectadas para compilar herramientas usando Bionic libc de Termux en lugar de glibc estándar.
- `apply-patches.sh`: El script que se encarga de inyectar estos parches en sus lugares correspondientes dentro de `node_modules` o en la jerarquía del sistema de archivos de Termux.

### `platforms/openclaw/`

Define cómo debe instalarse y configurarse el motor OpenClaw. La arquitectura de instalación está pensada como _plugins_ de plataforma.

- `config.env`: Declara metadatos de la plataforma y booleanos de dependencias (`PLATFORM_NEEDS_GLIBC`, `PLATFORM_NEEDS_NODEJS`).
- `install.sh` / `uninstall.sh` / `update.sh`: Lógica específica para descargar los paquetes NPM, instalar dependencias (`clawdhub`, `sharp`) e ignorar scripts post-install problemáticos.
- `env.sh`: Define y exporta las variables de entorno críticas necesarias para que la plataforma funcione en runtime.
- `verify.sh` / `status.sh`: Analiza la integridad de la plataforma instalada.

### `scripts/`

El "músculo" de los instaladores. Aloja piezas modulares llamadas por `install.sh`.

- `lib.sh`: Librería con funciones de uso común (impresión con color, lectura de prompts, detección de arquitectura).
- `check-env.sh`: Script de verificación previa al vuelo (pre-flight) para comprobar que la CPU es compatible (aarch64) y hay espacio suficiente.
- `install-infra-deps.sh`: Instala requerimientos base del sistema usando `pkg` (git, utilidades principales).
- `install-glibc.sh`: Instala `glibc-runner` mediante el gestor de paquetes `pacman` de Termux.
- `install-nodejs.sh`: Descarga el tarball oficial de Node.js `linux-arm64` (glibc) y configura un wrapper para que siempre se ejecute a través de `grun` (ld.so).
- `install-chromium.sh` / `build-sharp.sh` / `install-code-server.sh`: Instaladores para herramientas opcionales (L3).
- `backup.sh`: Herramienta para realizar copias de seguridad de las configuraciones y restaurarlas (`oa --backup` / `oa --restore`).

### `tests/`

Contiene `verify-install.sh` y `verify-compat.sh`, scripts que se ejecutan al final del proceso de instalación para garantizar que `ld.so`, Node.js, `npm`, y las rutas del sistema fueron configuradas exitosamente. Emiten alertas (WARN/FAIL) si algo no funcionó.

---

## 5. Archivos raíz destacados

- **`install.sh`:** Orquestador principal para instalaciones manuales en Termux.
- **`oa.sh`:** CLI unificada (vía `oa`). Ahora incluye detección inteligente de entorno (`is_app_mode`) para funcionar sin fallos en el terminal de la App.
- **`update.sh` / `update-core.sh`:** Actualizan el repositorio y la plataforma. Se han reforzado con `pkg_safe` para evitar crashes en la App.
- **`uninstall.sh`:** Remoción limpia del entorno.
- **`build.gradle.kts`:** Configurado con AGP 8.7.0 y Gradle 8.11.1 para máxima estabilidad en el build.
- **`build.gradle.kts` / `settings.gradle.kts`:** Scripts de construcción del entorno Gradle (utilizando Kotlin DSL) necesarios para compilar la aplicación Android.
- **`CHANGELOG.md`:** Registro detallado de cambios y versiones del proyecto.
- **`TODO.md`:** Registro de tareas pendientes de la comunidad y del desarrollador.
- **`.gitignore` / `.editorconfig`:** Reglas estándar para excluir archivos de Git y establecer configuraciones consistentes de formato y codificación para el editor.

---

## 6. Flujos de Instalación

### Flujo A: App APK (Recomendado)

El APK contiene todo lo necesario. No requiere apps externas ni comandos shell.

```
Usuario abre App
    ↓
InstallationOrchestrator.detectMode()
    ↓
┌─────────────────┬──────────────────┬──────────────────┐
│   Modo Payload  │ Modo Bootstrap   │   Modo Proot     │
│   (Offline)     │   (Online)       │   (Online)       │
│                 │                  │                  │
│ Extrae desde    │ Descarga         │ Descarga         │
│ assets/         │ bootstrap zip    │ proot+rootfs     │
│                 │                  │                  │
│ ~30 segundos    │ ~3 minutos       │ ~5 minutos       │
│ Sin internet    │ Requiere net     │ Requiere net     │
└─────────────────┴──────────────────┴──────────────────┘
    ↓
EnvironmentConfigurator.setup()
    ↓
Dashboard WebView React abierto
```

**Validaciones de exclusión mutua:**

```kotlin
// InstallationOrchestrator verifica antes de instalar
if (hasConflictingSystem()) {
    throw IllegalStateException("Cannot install: Another system active")
}
```

### Flujo B: Scripts de Shell (Desarrollo/Termux Externo)

Para desarrollo o usuarios que prefieren Termux externo:

1. `curl -sL myopenclawhub.com/install | bash` → descarga repo
2. `install.sh` → orquesta scripts en `scripts/`
3. `install-glibc.sh` → instala glibc-runner vía pacman
4. `install-nodejs.sh` → descarga Node.js linux-arm64
5. `platforms/openclaw/install.sh` → instala OpenClaw vía NPM
6. Parches aplicados (`glibc-compat.js`, etc.)

**Nota:** Este flujo requiere app Termux instalada (solo para desarrollo).

### Ejecución Cotidiana (App APK)

1. `MainActivity` inicia `OpenClawService` (foreground)
2. `GlibcRunner` ejecuta: `ld-linux-aarch64.so.1 → node → openclaw`
3. `JsBridgeFacade` expone API segura a WebView (sin comandos shell)
4. Dashboard React consume estado vía `AppContext`

---

## 7. Contribución y desarrollo

OpenClaw-Android da la bienvenida activa a los contribuidores. Los siguientes documentos rigen el proceso:

- **`CONTRIBUTING.md`:**
  - Fomenta la contribución buscando _issues_ etiquetados como `good first issue`.
  - Recomienda enfocarse en áreas como correcciones de documentación, mejoras en shell scripts o tests unitarios.
  - El flujo de trabajo requerido es realizar un _Fork_ del proyecto y abrir un _Pull Request_.
- **`CODE_OF_CONDUCT.md`:**
  - Garantiza un espacio seguro y libre de acoso, independientemente de edad, género, experiencia o nacionalidad.
  - Promueve la empatía, el feedback constructivo y desaprueba terminantemente ataques personales o lenguajes despectivos.
- **`SECURITY.md`:**
  - Específica qué versiones reciben soporte (App v0.4.x, Script v1.0.x).
  - Prohíbe estrictamente reportar vulnerabilidades en _issues_ públicos. Las vulnerabilidades deben reportarse privadamente a través de los GitHub Security Advisories.

---

## 8. Licencia

El código principal de OpenClaw-Android (scripts y lógica central) está liberado bajo la **Licencia MIT**. Esto permite que el software sea usado, copiado, modificado, fusionado, publicado o distribuido libremente, con la única condición de incluir siempre el aviso de derechos de autor y la propia licencia MIT.

_Nota:_ Algunas secciones relativas a adaptaciones de terminal dentro del código Android pueden estar vinculadas a GPL v3 en partes específicas heredadas de otros proyectos, pero la licencia maestra del repositorio como un todo es MIT.
