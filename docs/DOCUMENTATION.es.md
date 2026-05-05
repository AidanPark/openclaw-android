# Documentación Técnica de OpenClaw-Android

Bienvenido a la documentación técnica exhaustiva de **OpenClaw-Android**. Este documento está diseñado para proporcionar un entendimiento profundo de la arquitectura, estructura de archivos y flujos de ejecución internos del proyecto. Está dirigido tanto a usuarios avanzados que desean comprender qué ocurre bajo el capó, como a desarrolladores interesados en contribuir.

---

## 1. Descripción general del proyecto

**OpenClaw-Android** es una solución que permite ejecutar la plataforma OpenClaw (un entorno para agentes y herramientas de IA) directamente en dispositivos Android, con una configuración mínima y sin necesidad de realizar _root_ en el dispositivo.

**El problema que resuelve:**
Típicamente, para ejecutar herramientas complejas de Linux (como Node.js con dependencias nativas o binarios precompilados) en Android, se requería instalar una distribución de Linux completa utilizando herramientas como `proot-distro`. Esto introduce una sobrecarga considerable de almacenamiento (1-2 GB) y un impacto negativo en el rendimiento debido a la capa de traducción de `proot`.

**Filosofía y Enfoque:**
OpenClaw-Android descarta la necesidad de una distribución de Linux completa. En su lugar, utiliza **glibc-runner**, un entorno mínimo que proporciona únicamente el enlazador dinámico de GNU C Library (`ld.so`) dentro de Termux (que nativamente usa Bionic libc). Al ejecutar binarios compilados para Linux (como Node.js oficial para linux-arm64) directamente a través del enlazador dinámico `ld.so`, se logra una velocidad nativa, reduciendo drásticamente el uso de almacenamiento a aproximadamente ~200MB y logrando tiempos de instalación de entre 3 a 10 minutos.

---

## 2. Arquitectura de alto nivel

El proyecto está dividido en dos grandes dominios que se comunican entre sí: el **Entorno de Ejecución en Termux (Shell/Scripts)** y la **Aplicación Android (APK)**.

```mermaid
flowchart TD
    subgraph App["App Android (APK)"]
        UI[WebView React SPA\nInterfaz de usuario y OTA]
        Kotlin[Clases Kotlin\nGestión del sistema, Servicios en background, JsBridge]
        Term[TerminalView\nEmulador de Terminal PTY]
        UI <-->|JsBridge / EventBridge| Kotlin
        Kotlin <-->|Bash -l -c| Term
    end

    subgraph Termux["Entorno Sandbox (Archivos de la App)"]
        Boot[InstallerManager.kt\n(Online/Offline)]
        Core[Scripts / Plataformas / Parches]
        Glibc[glibc-runner\nEnlazador Dinámico ld.so]
        Node[Node.js Linux-arm64]
        OpenClaw[Gateway OpenClaw]

        Boot --> Core
        Core --> Glibc
        Glibc --> Node
        Node --> OpenClaw
    end

    App <==>|Ejecuta comandos, lee estado| Termux
```

1. **Scripts de Instalación y Core:** Agnósticos de la plataforma, preparan la infraestructura (Termux, paquetes de red).
2. **Entorno L2 (glibc + Node.js):** Proporciona la compatibilidad binaria requerida, utilizando `grun` (glibc-runner).
3. **Plataforma OpenClaw:** Instalada mediante scripts específicos que aplican parches de compatibilidad (bypass de systemd, resolución de DNS, etc.).
4. **La App Android:** Un contenedor independiente que incluye una PTY (Pseudo-Terminal), y una SPA en React para gestionar la instalación, configuración y ejecución de la plataforma, sin que el usuario interactúe directamente con la aplicación de Termux (aunque utilice su entorno por debajo).

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
**Atención Especial:** Esta carpeta contiene el código de la aplicación Android independiente. Permite a los usuarios tener una interfaz y consola integradas sin abrir la app de Termux.

- **Estructura Interna:** `app/src/main/java/com/openclaw/android/`
- **Clases Kotlin principales:**
  - `MainActivity.kt`: Contenedor principal que maneja permisos, aloja la WebView y el TerminalView.
  - `InstallerManager.kt`: **Cerebro de la instalación.** Gestiona el flujo híbrido (Online vía GitHub o Offline vía Assets), utiliza `PayloadExtractor` para streaming de archivos grandes y crea los wrappers binarios en `/usr/bin`.
  - `PayloadExtractor.kt`: Motor de descompresión basado en Apache Commons Compress que permite extraer el entorno sin saturar la memoria RAM del dispositivo.
  - `JsBridge.kt`: API que conecta React con Kotlin. Incluye métodos para iniciar instalaciones, detectar estado del runtime y ejecutar scripts de forma asíncrona.
  - `EnvironmentBuilder.kt`: Construye las variables de entorno críticas (LD_LIBRARY_PATH, PATH, etc.) para que los binarios glibc funcionen en el sandbox de Android.
  - `TerminalSessionManager.kt`: Orquesta sesiones de terminal (PTY) y previene conflictos de librerías entre el sistema Android y el sandbox.

- **Interfaz WebView React (`android/www/`):** Una aplicación de una sola página (SPA) construida en React. Utiliza un enrutador basado en Hash (`HashRouter` porque el esquema `file://` no soporta el History API) y proporciona los paneles de configuración y _dashboard_. El empaquetado final (`www.zip`) soporta un sistema de **OTA** (Over-The-Air) para actualizar atómicamente la interfaz sin necesidad de recompilar e instalar una nueva versión del APK.
- **Terminal PTY (`terminal-emulator/` y `terminal-view/`):** Subsistema basado en C++ (libtermux.so) y Java para emular un entorno de terminal real.
- **Sistema de Arranque:** El `BootReceiver.kt` detecta el encendido del dispositivo y, si está configurado, reinicia automáticamente el `OpenClawService` y el gateway.

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

## 6. Flujo de instalación y ejecución

La magia de OpenClaw-Android ocurre en una secuencia de pasos altamente orquestada:

### Proceso de Instalación (Vía Termux o App)
1. **Punto de Entrada (`bootstrap.sh`):** El usuario ejecuta `curl -sL myopenclawhub.com/install | bash`. El script comprueba que esté en Termux, maneja los certificados SSL y descarga el resto del repositorio en una carpeta temporal.
2. **Chequeos Previos (Paso 1):** `install.sh` ejecuta `scripts/check-env.sh`. Verifica la arquitectura `aarch64` y el espacio disponible.
3. **Selección y Prompts (Pasos 2-3):** Se carga `platforms/openclaw/config.env` y se le pregunta al usuario qué herramientas extra (tmux, chromium, etc.) desea instalar.
4. **Infraestructura Base (Paso 4):** Se ejecuta `install-infra-deps.sh` actualizando los repositorios de Termux (`pkg update`).
5. **Entorno de Runtime (Paso 5):** **Crucial:** Si la plataforma requiere glibc, se ejecuta `install-glibc.sh`. Esto instala `pacman` y, a través de este, `glibc-runner`. Seguidamente, `install-nodejs.sh` descarga el Node.js oficial y crea un _wrapper_ ejecutable para Node que inyecta `grun` (ej. `/path/to/grun /path/to/node`).
6. **Instalación de la Plataforma (Paso 6):** Se ejecuta `platforms/openclaw/install.sh`. Se instala OpenClaw vía NPM de manera global, y se ejecutan parches (ej: `apply-patches.sh`) para asegurar su funcionamiento.
7. **Herramientas Adicionales y CLI (Pasos 7 y 8):** Se copian los scripts interactivos como `oa.sh` a las rutas binarias (`$PREFIX/bin/oa`) y se corren los scripts de tests (`verify-install.sh`).

### Ejecución Cotidiana
Cuando un usuario ejecuta `openclaw gateway` en Termux, o cuando la App Android invoca el servicio en background:
1. El comando ingresa a través del binario instalado por NPM, que usa el wrapper de Node.
2. El wrapper de Node asegura que el comando se pase a `grun` (glibc-runner).
3. `grun` utiliza `ld-linux-aarch64.so.1` para cargar el entorno de Node.js sin depender de la libc nativa de Android (Bionic).
4. El proceso de OpenClaw corre a velocidad nativa, manejando las peticiones locales.

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
  - Prohíbe estrictamente reportar vulnerabilidades en *issues* públicos. Las vulnerabilidades deben reportarse privadamente a través de los GitHub Security Advisories.

---

## 8. Licencia

El código principal de OpenClaw-Android (scripts y lógica central) está liberado bajo la **Licencia MIT**. Esto permite que el software sea usado, copiado, modificado, fusionado, publicado o distribuido libremente, con la única condición de incluir siempre el aviso de derechos de autor y la propia licencia MIT.

*Nota:* Algunas secciones relativas a adaptaciones de terminal dentro del código Android pueden estar vinculadas a GPL v3 en partes específicas heredadas de otros proyectos, pero la licencia maestra del repositorio como un todo es MIT.