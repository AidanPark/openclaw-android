# 📋 Changelog

Todos los cambios notables de este proyecto están documentados aquí.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/), y este proyecto sigue [Semantic Versioning](https://semver.org/).

---

## [App v0.5.1 / UI v1.1.1] — 2026-05-02

### 🐛 Corregido — Error crítico: `libc.so` corrupto

**Síntoma:** `CANNOT LINK EXECUTABLE "sh": file offset for the library "libc.so" >= file size: 0 >= 0`

**Causa raíz:** Al extraer el payload tar.gz en Android, los symlinks (`libc.so -> libc.so.6`) se extraen como archivos vacíos (0 bytes) en lugar de symlinks reales. El dynamic linker de Android falla al intentar cargar un `libc.so` de 0 bytes.

**Correcciones:**

- **`PayloadExtractor.kt`**: Nuevo método `repairGlibcSymlinks()` que detecta y repara archivos `.so` de 0 bytes convirtiéndolos en symlinks correctos (`libc.so -> libc.so.6`, `libm.so -> libm.so.6`, etc.)
- **`PayloadExtractor.kt`**: Durante la extracción, los archivos `libc.so` de 0 bytes se omiten (son symlinks rotos en el tar) para que el symlink real se cree correctamente
- **`InstallerManager.kt`**: `completeInstallation()` ahora llama a `repairGlibcSymlinks()` antes de `applyPermissions()` y la validación
- **`InstallValidator.kt`**: El check de shell ahora acepta `/system/bin/sh` (siempre disponible en Android 7+) como shell válido — el payload no necesita incluir bash/sh
- **`InstallValidator.isStructurallyComplete()`**: Actualizado para aceptar el shell del sistema Android

### 🐛 Corregido — `curl | bash` falla en Android

**Síntoma:** `sh: bash: inaccessible or not found`

**Causa raíz:** Android no tiene `bash` en `/usr/bin/env bash`. Solo tiene `/system/bin/sh` (mksh/toybox).

**Corrección:**

- **`scripts/install-remote.sh`**: Nuevo script de instalación remoto 100% compatible con POSIX `sh`. Funciona con:
  - `curl -sL myopenclawhub.com/install | sh`
  - `curl -sL myopenclawhub.com/install | bash` (si bash disponible)
  - Android `/system/bin/sh`, Termux bash, Linux sh/dash/bash
  - Detecta automáticamente el entorno (App sandbox / Termux / Linux)
  - Repara symlinks glibc automáticamente
  - Usa `#!/system/bin/sh` en wrappers de Node.js (no `#!/usr/bin/env bash`)

### 🐛 Corregido (UI)

- **`runtime-grid`**: Clase CSS faltante — Dashboard roto. Corregido.
- **`var(--primary)` / `var(--primary-rgb)`**: Variables CSS no definidas. Añadidas como alias de `--accent`.
- **`@keyframes pulse`**: Animación del FAB no definida. Añadida.
- **Iconos duplicados en Quick Actions**: "Sesiones" y "Nueva sesión" tenían el mismo icono 📋. Corregido.
- **Status hardcodeado en español**: "detectado" ahora usa `t('env_detected')`.
- **`openssh-server` descripción incorrecta**: Usaba `tool_ttyd`. Corregido con `tool_ssh_server`.

### ✨ Añadido

- **`env_detected`**: Nueva clave i18n (EN/ES)
- **`tool_ssh_server`**: Nueva clave i18n (EN/ES)
- **`scripts/install-remote.sh`**: Script de instalación remoto POSIX sh compatible

---

## [App v0.5.0 / Script v1.1.0] — 2026-05-02

### ✨ Añadido (Arquitectura Híbrida)

- **InstallerManager**: Nueva lógica centralizada que gestiona instalaciones *Online* (GitHub OTA) y *Offline* (Payload local).
- **PayloadExtractor**: Motor de descompresión por streaming basado en Apache Commons Compress. Permite extraer entornos pesados (~200MB) sin saturar la RAM ni causar "pantallas negras".
- **Dashboard v2**: Rediseño completo de la interfaz de inicio. Ahora el Dashboard es la vista principal con banners de estado interactivos.
- **is_app_mode**: Detección inteligente en `lib.sh` para ajustar el comportamiento de los scripts cuando se ejecutan dentro del APK.
- **pkg_safe**: Envoltorio para comandos de sistema que evita crashes en la App al intentar usar herramientas exclusivas de Termux.

### 🔄 Cambiado

- **Build System**: Downgrade a **Gradle 8.11.1** y **AGP 8.7.0** para asegurar compatibilidad total con el entorno de construcción local.
- **Kotlin 2.1.0**: Actualización del runtime de Kotlin.
- **Estructura**: Movida la documentación secundaria y READMEs multi-idioma a `docs/` para limpiar la raíz del repositorio.

### 🗑️ Eliminado (Limpieza de Repositorio)

- **Scripts Legados**: Eliminados `bootstrap.sh`, `install-minimal.sh`, `install-tools.sh` y scripts de assets obsoletos. La lógica ahora está centralizada en `InstallerManager`.
- **Clases Obsoletas**: Removidos `OpenClawSetup.kt` y `BootstrapManager.kt`.

---

## [App v0.4.1 / Script v1.0.27] — 2026-04-28

### ✨ Añadido

- **Tests**: Suite de tests ampliada de 22 a 45+ tests unitarios
  - `BootstrapManagerTest` — detección de instalación, wrapper script, ELF magic, rutas
  - `VersionCompareTest` — lógica semver para checks de actualización OTA
  - `CommandRunnerTest` — timeout, stderr, workDir fallback, buildTermuxEnv completo
  - `EnvironmentBuilderTest` — 27 tests cubriendo todas las variables de entorno
- **Optimización JsBridge**: Reemplazado `CoroutineScope(Dispatchers.IO)` por scope compartido `ioScope` — evita crear un nuevo thread pool por cada operación async
- **getInstalledTools**: Corregidas rutas de detección de herramientas npm — ahora usa `OPENCLAW_BIN` en lugar de la ruta obsoleta `node/bin/`
- **Dependencias**: Actualizado gson 2.12.1 → 2.13.2, ktlint 14.1.0 → 14.2.0

### 🐛 Corregido

- **JsBridge.getInstalledTools**: Eliminada dependencia de `EnvironmentBuilder.build(activity)` — ahora usa `CommandRunner.TERMUX_PREFIX` directamente (consistente con el resto del código)
- **Rutas npm bin**: Corregida detección de claude, gemini, codex, opencode — apuntaban a `node/bin/` (ruta antigua) en lugar de `OPENCLAW_BIN`

---

## [Script v1.0.27] — 2026-04-13

### 🐛 Corregido

- Corregido `oa --update` mostrando error de sintaxis tras auto-actualización — el proceso shell en ejecución continuaba leyendo el archivo `oa` reemplazado, causando un error de parseo en la línea 240 del nuevo archivo. Se añadió `exit 0` tras completar la actualización para evitar esto ([#110](https://github.com/AidanPark/openclaw-android/issues/110))

---

## [Script v1.0.26] — 2026-04-12

### 🔄 Cambiado

- Cambio de Codex CLI desde `@openai/codex` upstream a `@mmmbuto/codex-cli-termux` optimizado para Termux (fork DioNanos/codex-termux). El paquete upstream incluye un binario musl estático cuyo resolver DNS tiene hardcoded `/etc/resolv.conf` — archivo que no existe en Android — causando conexiones de red poco fiables. El fork compila como binario Bionic dinámico que usa el stack DNS nativo de Android, corrigiendo el patrón `Stream disconnected` / `error sending request`. El nombre del comando CLI (`codex`) no cambia. ([#108](https://github.com/AidanPark/openclaw-android/issues/108))

### 🐛 Corregido

- Corregido el launcher de Codex CLI fallando en el namespace `com.openclaw.android` — el symlink `$PREFIX/bin/codex` creado por npm apunta a una cadena de launchers JS que calcula mal las rutas bajo el namespace no estándar de Android. Se reemplaza el symlink con un wrapper bash que establece `LD_LIBRARY_PATH` y ejecuta directamente `codex.bin`. ([#108](https://github.com/AidanPark/openclaw-android/issues/108))

---

## [Script v1.0.25] — 2026-04-11

### 🐛 Corregido

- Corregido `bad interpreter: Permission denied` al ejecutar herramientas CLI instaladas globalmente con npm (codex, claude, clawdhub, etc.) directamente desde shell en Android/Termux. La causa raíz es el shebang `#!/usr/bin/env node` en los entry points `.js`, que Android no puede resolver. Corrección en dos capas: (1) hook npm wrapper reescribe shebangs automáticamente tras cada `npm install -g`, (2) llamadas de defensa en profundidad en scripts de instalación/actualización.

---

## [Script v1.0.24] — 2026-04-11

### 🐛 Corregido

- Eliminada la contaminación permanente de `~/.npmrc` del usuario durante la instalación — anteriormente `post-setup.sh` detectaba acceso lento a `registry.npmjs.org` y escribía `registry=https://registry.npmmirror.com` en `~/.npmrc`, afectando todos los proyectos npm del usuario para siempre sin auto-recuperación. Ahora el instalador usa la variable de entorno `NPM_CONFIG_REGISTRY` con scope de sesión y cachea el registro elegido en `~/.openclaw-android/.npm-registry`. ([#107](https://github.com/AidanPark/openclaw-android/issues/107))
- Cubiertos los tres paths de instalación para la detección del registro npm — App Install (`post-setup.sh`), Termux Install (`install.sh`) y Update (`update-core.sh`).

---

## [Script v1.0.23] — 2026-04-11

### 🐛 Corregido

- Preservado el `~/.gitconfig` existente del usuario durante el post-setup — anteriormente `cat > ~/.gitconfig` sobreescribía toda la configuración del usuario (nombre, email, aliases). Ahora usa `git config --global` para establecer solo las claves `http.sslCAInfo` y `url.https://github.com/.insteadOf` manteniendo las entradas del usuario intactas. ([#107](https://github.com/AidanPark/openclaw-android/issues/107))

---

## [Script v1.0.22] — 2026-04-10

### ✨ Añadido

- Auto-wrapping de binarios ELF: detecta binarios glibc via PT_INTERP y los enruta a través de ld.so, permitiendo que binarios nativos instalados con npx como codex-acp se ejecuten en Android ([#103](https://github.com/AidanPark/openclaw-android/issues/103))
- Resolución de shebangs: maneja scripts `#!/usr/bin/env` sin libtermux-exec.so resolviendo intérpretes desde PATH en JavaScript
- Intercepción de invocación shell: detecta el patrón `spawn('sh', ['-c', 'cmd'])` usado por npm/npx y resuelve comandos directamente
- Despliegue de librerías glibc suplementarias: incluye libcap.so.2 para soporte de binarios nativos de terceros
- Atajo DNS localhost: devuelve 127.0.0.1 inmediatamente para lookups de localhost sin consultar DNS externo ([#105](https://github.com/AidanPark/openclaw-android/issues/105))
- Crea `$PREFIX/glibc/etc/hosts` si no existe, asegurando que getaddrinfo pueda resolver localhost ([#105](https://github.com/AidanPark/openclaw-android/issues/105))

### 🔄 Cambiado

- Eliminada la restauración de LD_PRELOAD en glibc-compat.js — libtermux-exec.so lo re-inyecta via hook execve, crasheando procesos hijo glibc con errores "Could not find a PHDR"
- Siempre usar shell Termux para exec/execSync en todas las versiones de Android (anteriormente solo Android 7-8)

---

## [Script v1.0.21] — 2026-04-07

### 🐛 Corregido

- Corregido `oa --backup` saliendo con código de error 1 por `tmpdir: unbound variable` — el trap usaba una variable local que salía del scope
- Corregido `oa --backup` / `oa --restore` y `ask_yn` fallando en entornos sin tty (SSH pipe, no interactivo) — fallback a stdin cuando `/dev/tty` no está disponible

---

## [Script v1.0.20] — 2026-04-06

### 🐛 Corregido

- Corregida la restauración de dependencias bloqueada por fallo de build de sharp — `npm install` dentro del directorio openclaw dispara el build nativo de sharp que falla en Termux, bloqueando todas las demás dependencias. Ahora ejecuta `postinstall-bundled-plugins.mjs` directamente con `npm_config_ignore_scripts=true`. ([#92](https://github.com/AidanPark/openclaw-android/issues/92))
- Corregida la restauración de dependencias omitida cuando openclaw ya está en la última versión — verifica la presencia de `@buape/carbon` en lugar del flag `OPENCLAW_UPDATED`

---

## [Script v1.0.19] — 2026-04-06

### 🐛 Corregido

- Corregidas las dependencias de canal faltantes tras instalación con `--ignore-scripts` — reinstala dependencias dentro del directorio del paquete openclaw para restaurar módulos opcionales como `@buape/carbon`, `grammy`. ([#92](https://github.com/AidanPark/openclaw-android/issues/92))

---

## [Script v1.0.18] — 2026-04-04

### 🐛 Corregido

- Corregido `process.execPath` apuntando a `ld-linux-aarch64.so.1` en lugar del wrapper node — glibc-compat.js tenía la ruta incorrecta (`node/bin/node` en lugar de `bin/node`), causando fallos en spawns de procesos hijo de OpenClaw 4.2 con `--disable-warning=ExperimentalWarning`. ([#88](https://github.com/AidanPark/openclaw-android/issues/88))
- Añadida variable de entorno `_OA_WRAPPER_PATH` al wrapper node — elimina la adivinanza de rutas en glibc-compat.js
- Corregido verify-compat.sh verificando rutas de wrapper incorrectas
- Corregido `install.sh` con PATH de sesión sin `$BIN_DIR` — comandos node/npm podían fallar al resolver tras el Paso 5
- Corregido npm wrapper escribiendo a través de symlink y corrompiendo `openclaw.mjs`. ([#89](https://github.com/AidanPark/openclaw-android/issues/89))

---

## [Script v1.0.17] — 2026-04-03

### 🐛 Corregido

- Corregido falso positivo "glibc node wrapper not found" en verificación de instalación — verify-install.sh y status.sh referenciaban la ruta antigua `node/bin/` en lugar de la nueva `bin/`. ([#87](https://github.com/AidanPark/openclaw-android/issues/87))
- Añadida constante `BIN_DIR` a lib.sh para prevenir drift de rutas hardcodeadas en scripts de verificación

---

## [Script v1.0.16] — 2026-04-02

### 🐛 Corregido

- Auto-reparcheo del wrapper CLI de openclaw tras `npm install/update -g openclaw` — previene rotura del shebang `/usr/bin/env` en Termux. ([#86](https://github.com/AidanPark/openclaw-android/issues/86))
- Movidos los wrappers node/npm/npx a directorio dedicado `bin/` seguro frente a sobreescrituras de npm
- Corregida la creación faltante del wrapper `bin/node` en el path de reparación ya instalado

---

## [Script v1.0.15] — 2026-04-01

### 🐛 Corregido

- Corregido `dns.promises.lookup` no parcheado en glibc-compat.js — el guard SSRF de web_search de OpenClaw usa `node:dns/promises` que evitaba el fix DNS de c-ares, causando `getaddrinfo EAI_AGAIN` en hosts sin `resolv.conf`. ([#83](https://github.com/AidanPark/openclaw-android/issues/83))

---

## [Script v1.0.14] — 2026-04-01

### 🐛 Corregido

- Auto-deshabilitado de Bonjour/mDNS en runtime cuando solo la interfaz loopback es visible — Android/Termux no puede enviar multicast, causando logs repetidos "Announcement failed as of socket errors!" en el gateway. ([#84](https://github.com/AidanPark/openclaw-android/issues/84))

---

## [Script v1.0.13] — 2026-03-31

### ✨ Añadido

- Playwright como herramienta de instalación opcional (`oa --install`) — instala `playwright-core`, auto-configura la ruta de Chromium y variables de entorno
- Auto-reparcheo del wrapper CLI de openclaw tras `npm install/update -g openclaw`

### 🔄 Cambiado

- Bump Gson 2.12.1 → 2.13.2
- Bump androidx.core:core-ktx 1.17.0 → 1.18.0
- Bump ktlint gradle plugin 14.1.0 → 14.2.0
- Bump Gradle wrapper 9.3.1 → 9.4.1
- Bump eslint 9.39.4 → 10.0.3
- Bump globals 16.5.0 → 17.4.0
- Bump eslint-plugin-react-refresh 0.4.24 → 0.5.2
- Bump GitHub Actions: checkout v4→v6, setup-node v4→v6, setup-java v4→v5, upload-artifact v4→v7, download-artifact v4→v8

---

## [App v0.4.0 / Script v1.0.12] — 2026-03-30

### ✨ Añadido

- App: soporte i18n — Inglés, Coreano (한국어), Chino (中文) con auto-detección
- App: selector de idioma en Ajustes
- README en chino (README.zh.md) con enlace de descarga desde mirror de China
- Fallback a mirror de GitHub para China/redes restringidas (ghfast.top, ghproxy.net)
- Auto-cambio de registro npm a npmmirror.com cuando npmjs.org no es alcanzable
- `AppLogger` — wrapper centralizado de logging, reemplaza todas las llamadas a `android.util.Log`
- Infraestructura de tests unitarios (JUnit5 + MockK, 22 tests)
- Workflow CI de calidad de código (shellcheck, sync check, markdownlint, kotlin lint, unit tests)
- shellcheck y markdownlint en hook pre-commit
- Verificación de sincronización de post-setup.sh en pre-commit

### 🔄 Cambiado

- Resueltas las 48 violaciones detekt — sin baseline necesaria
- Resueltas las 43 violaciones shellcheck en todos los scripts
- Resueltas las 125 violaciones markdownlint en todos los documentos
- Refactorizados BootstrapManager, JsBridge, MainActivity para reducir complejidad
- Bump versión app a v0.4.0 (versionCode 9)
- Bump versión script a v1.0.12

---

## [App v0.3.x / Script v1.0.6] — 2026-03-10

### 🔄 Cambiado

- Limpieza de instalación existente al reinstalar

---

## [App v0.3.0 / Script v1.0.5] — 2026-03-06

### ✨ Añadido

- APK Android autónoma con UI WebView, terminal nativa y barra de teclas extra
- Barra de pestañas multi-sesión con navegación por deslizamiento
- Auto-inicio en arranque via BootReceiver
- Soporte de automatización del navegador Chromium (`scripts/install-chromium.sh`)
- Comando `oa --install` para instalar herramientas opcionales de forma independiente

### 🐛 Corregido

- Error de sintaxis en `update-core.sh` (fi extra en línea 237)
- Procesamiento de imágenes sharp con fallback WASM para el límite glibc/bionic

### 🔄 Cambiado

- Cambio del modo de entrada del terminal a `TYPE_NULL` para comportamiento estricto de terminal

---

## [Script v1.0.4] — 2025-12-15

### 🔄 Cambiado

- Actualización de Node.js a v22.22.0 para soporte FTS5 (`node:sqlite` static bundle)
- Mostrar versión en todos los mensajes de omisión y completado de actualización

### 🗑️ Eliminado

- Soporte oh-my-opencode (OpenCode usa Bun interno, plugins basados en PATH no detectados)

### 🐛 Corregido

- El glob de versión de actualización seleccionaba la más antigua en lugar de la más reciente
- Fallos de build de módulos nativos durante actualización

---

## [Script v1.0.0] — 2025-08-15

### ✨ Añadido

- Lanzamiento inicial
- Ejecución basada en glibc-runner (sin proot-distro requerido)
- Instalador de un comando (`curl | bash`)
- Wrapper glibc de Node.js para binarios Linux estándar en Android
- Conversión de rutas para compatibilidad con Termux
- Herramientas opcionales: tmux, code-server, OpenCode, AI CLIs
- Verificación post-instalación
