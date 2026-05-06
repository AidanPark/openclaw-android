# 🛠️ Guía de Contribución

¡Gracias por tu interés en contribuir a OpenClaw Android! Esta guía te ayudará a empezar.

---

## 🌱 Primeros Contribuidores

Bienvenido — las contribuciones de todos los tamaños son valoradas. Si es tu primera contribución:

1. **Encuentra un issue.** Busca issues etiquetados con [`good first issue`](https://github.com/AidanPark/openclaw-android/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) — están pensados para recién llegados.

2. **Elige un alcance.** Buenas primeras contribuciones incluyen:
   - Correcciones de typos y documentación
   - Mejoras en scripts shell
   - Correcciones de bugs con pasos de reproducción claros
   - Nuevos tests unitarios para código existente

3. **Sigue el flujo fork → PR** descrito más abajo.

---

## ⚙️ Configuración del Entorno

### Scripts Shell (instalador, actualizador, parches)

```bash
# Clonar el repositorio
git clone https://github.com/AidanPark/openclaw-android.git
cd openclaw-android

# Validar scripts shell
bash -n install.sh
bash -n update-core.sh
bash -n oa.sh
```

Los scripts shell siguen estilo compatible con POSIX con indentación de 4 espacios. Ver `scripts/lib.sh` para convenciones compartidas.

### App Android

```bash
cd android

# Compilar APK debug
./gradlew assembleDebug

# Ejecutar tests unitarios
./gradlew test

# Ejecutar checks de lint
./gradlew ktlintCheck
./gradlew detekt

# Formatear código
./gradlew ktlintFormat
```

**Requisitos previos**: JDK 21, Android SDK (API 36), NDK 28+, Node.js 22+ (para la UI WebView).

### UI WebView

```bash
cd android/www
npm install
npm run build
```

### Activar Git Hooks

```bash
git config core.hooksPath .githooks
```

Esto activa el hook pre-commit que se ejecuta automáticamente antes de cada commit:

| Check | Herramienta | Archivos |
|---|---|---|
| Formato Kotlin | ktlint 14.2.0 | `*.kt` |
| Análisis estático | detekt 1.23.8 | `*.kt` |
| Scripts shell | shellcheck | `*.sh` |
| Markdown | markdownlint-cli2 | `*.md` |
| TypeScript/React | ESLint | `android/www/` |
| Sincronización | diff | `post-setup.sh` raíz vs assets |

---

## 🧪 Tests

El proyecto usa JUnit5 + MockK. Los tests se encuentran en:

```
android/app/src/test/java/com/openclaw/android/
├── AppLoggerTest.kt          # 7 tests — delegación de Log
├── CommandRunnerTest.kt      # 22 tests — runSync, constantes, buildTermuxEnv
├── EnvironmentBuilderTest.kt # 27 tests — todas las variables de entorno
├── BootstrapManagerTest.kt   # 14 tests — detección, wrapper script, ELF
└── VersionCompareTest.kt     # 8 tests — lógica semver para OTA
```

Para ejecutar los tests:

```bash
cd android
./gradlew test
# Reporte: app/build/reports/tests/test/index.html
```

### Reglas para nuevos tests

- Un test por comportamiento, no por método
- Nombres descriptivos en backticks: `` `runSync times out and returns -1` ``
- No mockear lo que puedes probar directamente
- Tests de lógica pura no necesitan Android Context — úsalos en `src/test/`
- Tests que requieren Android Context van en `src/androidTest/`

---

## 🔄 Cómo Contribuir

### 1. Fork y Clone

```bash
git clone https://github.com/<tu-usuario>/openclaw-android.git
cd openclaw-android
```

### 2. Realiza tus Cambios

Todo el trabajo ocurre en `main` — usamos un flujo de rama única sin prefijos.

### 3. Prueba tus Cambios

```bash
# Shell scripts
bash -n <script.sh>

# App Android — build + tests
cd android && ./gradlew assembleDebug test

# Kotlin lint
./gradlew ktlintCheck && ./gradlew detekt
```

### 4. Commit

Los mensajes de commit usan inglés, estilo imperativo, sin prefijo:

```
Fix update-core.sh syntax error
Add multi-session terminal tab bar
Upgrade Node.js to v22.22.0 for FTS5 support
Add BootstrapManagerTest for ELF detection
```

- Empieza con mayúscula, sin punto al final
- Mantén la línea de asunto bajo 50 caracteres
- Usa tiempo presente imperativo ("Fix", no "Fixed" ni "Fixes")

### 5. Abre un Pull Request

Abre un PR contra `main`. Describe:
- Qué hace el cambio
- Por qué es necesario
- Cómo probarlo

---

## 📁 Estructura del Proyecto

```
openclaw-android/
├── install.sh              # Instalador principal (curl | bash)
├── oa.sh                   # CLI de gestión (oa --update, oa --install...)
├── update-core.sh          # Lógica de actualización de OpenClaw
├── post-setup.sh           # Post-instalación (sincronizado con assets)
├── scripts/
│   ├── lib.sh              # Librería compartida de scripts
│   ├── setup-env.sh        # Configuración de entorno bash
│   └── install-*.sh        # Instaladores de herramientas opcionales
├── android/                # APK Android (ver android/README.md)
│   ├── app/src/main/java/com/openclaw/android/
│   │   ├── CommandRunner.kt      # bash -l -c + grun + rutas Termux
│   │   ├── EnvironmentBuilder.kt # Variables de entorno Termux reales
│   │   ├── BootstrapManager.kt   # Bootstrap + detección installed.json
│   │   ├── JsBridge.kt           # 34 métodos @JavascriptInterface
│   │   └── MainActivity.kt       # Permisos + WebView + Terminal
│   └── app/src/test/             # Tests unitarios (JUnit5 + MockK)
└── tests/
    └── verify-install.sh   # Verificación de instalación en dispositivo
```

---

## 🎨 Estilo de Código

| Lenguaje | Estilo | Indentación |
|---|---|---|
| Shell (bash) | Compatible POSIX, convenciones `scripts/lib.sh` | 4 espacios |
| Kotlin | [Convenciones oficiales](https://kotlinlang.org/docs/coding-conventions.html) | 4 espacios |
| XML | Convenciones estándar Android | 2 espacios |
| TypeScript/React | Config ESLint en `android/www/` | 2 espacios |

---

## ⚠️ Consideraciones Clave

| Área | Regla |
|---|---|
| **Ejecución de comandos** | Siempre `bash -l -c "..."` — nunca `sh -c` ni `Runtime.exec()` |
| **Node.js** | Siempre `grun node` o via `openclaw-start.sh` — nunca `node` directamente |
| **Rutas** | Usar constantes de `CommandRunner` (`TERMUX_HOME`, `TERMUX_PREFIX`, etc.) |
| **Detección** | Verificar `installed.json` para instalación completa, no solo `bin/sh` |
| **Permisos** | Solicitar `MANAGE_EXTERNAL_STORAGE` antes de `termux-setup-storage` |
| **Compatibilidad Termux** | Scripts deben funcionar en el entorno de Termux (rutas `$PREFIX`, sin root) |
| **Límite glibc** | Node.js corre bajo glibc-runner; herramientas del sistema usan Bionic libc |
| **Rango Android** | `minSdk 24` (Android 7.0), `targetSdk 28` (bypass W^X) |
| **Idempotencia** | Scripts de instalación y actualización deben ser seguros de ejecutar múltiples veces |

---

## 🐛 Reportar Issues

- **Bugs**: Incluye versión de Android, modelo de dispositivo, versión de Termux, pasos para reproducir
- **Funcionalidades**: Describe el caso de uso y el enfoque propuesto
- **Seguridad**: Ver [SECURITY.md](SECURITY.md) para divulgación responsable

---

## 📄 Licencia

Al contribuir, aceptas que tus contribuciones serán licenciadas bajo la Licencia GPL v3.
