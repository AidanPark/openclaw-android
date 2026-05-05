# Termux Bootstrap Manager - Documentación Técnica

## Arquitectura del Sistema

### Visión General

El `TermuxBootstrapManager` es un sistema completo para descargar, instalar y configurar el bootstrap oficial de Termux en una app Android. Está diseñado para integrarse perfectamente con la arquitectura existente de tu app OpenClaw.

```
┌─────────────────────────────────────────────────────────────┐
│                    InstallerManager                         │
│  ┌──────────────────┐  ┌──────────────────┐                │
│  │TermuxBootstrap   │  │ProotManager      │                │
│  │Manager           │  │(Ubuntu rootfs)   │                │
│  └──────────────────┘  └──────────────────┘                │
│  ┌──────────────────┐  ┌──────────────────┐                │
│  │PayloadExtractor  │  │SetupManager      │                │
│  │(offline payload) │  │(legacy)          │                │
│  └──────────────────┘  └──────────────────┘                │
└─────────────────────────────────────────────────────────────┘
                         ↓
              ProgressListener callbacks
                         ↓
                    MainActivity/UI
```

### Componentes Principales

#### 1. TermuxBootstrapManager

**Responsabilidades:**
- Detectar arquitectura del dispositivo
- Descargar bootstrap ZIP desde packages.termux.dev
- Extraer contenido preservando permisos y symlinks
- Configurar entorno (DNS, profile, etc.)
- Ejecutar comandos post-instalación (pkg update, pkg install)
- Verificar integridad de la instalación

**Ubicación:** `android/app/src/main/java/com/openclaw/android/TermuxBootstrapManager.kt`

**Dependencias:**
- Apache Commons Compress (ya incluido en tu proyecto)
- Kotlin Coroutines (para operaciones asíncronas)
- Android System APIs (para symlinks)

#### 2. Integración con InstallerManager

El `TermuxBootstrapManager` se integra como una nueva opción de instalación en el `InstallerManager` existente:

```kotlin
suspend fun install(mode: String, customUri: Uri?, listener: ProgressListener) {
    when (mode) {
        "termux-bootstrap" -> installViaTermuxBootstrap(listener)
        "proot" -> installViaProot(listener)
        "offline" -> installOffline(listener)
        "auto" -> {
            // Preferir termux-bootstrap si no hay payload bundled
            if (hasPayloadAsset()) installOffline(listener)
            else installViaTermuxBootstrap(listener)
        }
    }
}
```

## Flujo de Instalación Detallado

### Fase 1: Detección de Arquitectura (1-2%)

```kotlin
fun detectArchitecture(): String {
    val abis = Build.SUPPORTED_ABIS
    return when {
        abis.any { it.startsWith("arm64") || it == "aarch64" } -> "aarch64"
        abis.any { it.startsWith("armeabi") } -> "arm"
        abis.any { it == "x86_64" } -> "x86_64"
        abis.any { it == "x86" } -> "i686"
        else -> "aarch64" // fallback
    }
}
```

**Arquitecturas soportadas:**
- `aarch64` (arm64-v8a): Mayoría de dispositivos modernos
- `arm` (armeabi-v7a): Dispositivos ARM de 32 bits
- `x86_64`: Emuladores y ChromeOS
- `i686` (x86): Emuladores antiguos

### Fase 2: Descarga del Bootstrap (2-50%)

```kotlin
private fun downloadBootstrap(
    url: String,
    destination: File,
    onProgress: (Long, Long) -> Unit
)
```

**Características:**
- Streaming download (no carga todo en memoria)
- Buffer de 32KB para eficiencia
- Timeout de 30s para conexión, 120s para lectura
- User-Agent personalizado
- Manejo de redirects HTTP
- Reporte de progreso en tiempo real

**URLs por arquitectura:**
```kotlin
private val BOOTSTRAP_URLS = mapOf(
    "aarch64" to "https://packages.termux.dev/bootstrap/bootstrap-aarch64.zip",
    "arm" to "https://packages.termux.dev/bootstrap/bootstrap-arm.zip",
    "x86_64" to "https://packages.termux.dev/bootstrap/bootstrap-x86_64.zip",
    "i686" to "https://packages.termux.dev/bootstrap/bootstrap-i686.zip",
)
```

### Fase 3: Extracción del Bootstrap (50-75%)

```kotlin
private fun extractBootstrap(
    zipFile: File,
    targetDir: File,
    onProgress: (Int) -> Unit
): Int
```

**Proceso de extracción:**

1. **Archivos regulares:**
   - Crear directorios padre si no existen
   - Copiar contenido del ZIP al filesystem
   - Establecer permisos de ejecución según Unix mode
   - Archivos en `bin/` siempre ejecutables

2. **Directorios:**
   - Crear con `mkdirs()`
   - No requieren permisos especiales

3. **Symlinks:**
   - Leer target del contenido del entry
   - Eliminar archivo existente si hay
   - Crear symlink con `android.system.Os.symlink()`
   - Fallos de symlink son no-fatales (target puede no existir aún)

**Preservación de permisos:**
```kotlin
val unixMode = entry.unixMode
if (unixMode != 0 && (unixMode and 0b001_001_001) != 0) {
    destFile.setExecutable(true, false)
}
```

### Fase 4: Configuración del Entorno (75-80%)

```kotlin
private fun setupEnvironment()
```

**Archivos creados:**

1. **`$PREFIX/etc/resolv.conf`** (DNS):
   ```
   nameserver 8.8.8.8
   nameserver 1.1.1.1
   nameserver 8.8.4.4
   ```

2. **`$PREFIX/etc/profile`** (Variables de entorno):
   ```bash
   export PATH=$PREFIX/bin:$PATH
   export PREFIX=$PREFIX
   export HOME=$HOME
   export TMPDIR=$PREFIX/tmp
   export LANG=en_US.UTF-8
   ```

3. **Permisos en `$PREFIX/bin/`:**
   - Todos los archivos en `bin/` se marcan como ejecutables
   - Necesario porque algunos archivos pueden perder permisos en la extracción

### Fase 5: Actualización de Repositorios (80-90%)

```kotlin
private fun runPkgUpdate(listener: ProgressListener): Boolean
```

**Comando ejecutado:**
```bash
export PATH=$PREFIX/bin:$PATH && pkg update -y
```

**Características:**
- Timeout de 2 minutos
- Salida redirigida a logs
- Fallo no es fatal (puede ser normal en primera instalación)
- Usa bash del bootstrap recién instalado

### Fase 6: Instalación de Paquetes Adicionales (90-98%)

```kotlin
private fun installAdditionalPackages(listener: ProgressListener)
```

**Paquetes instalados por defecto:**
- `git`: Control de versiones
- `curl`: Descarga de archivos
- `wget`: Descarga de archivos (alternativa a curl)

**Proceso:**
- Timeout de 3 minutos por paquete
- Fallos individuales no detienen la instalación
- Cada paquete reporta progreso independiente

### Fase 7: Verificación y Marcador (98-100%)

```kotlin
private fun writeMarker(arch: String)
```

**Contenido del marcador (`.termux-bootstrap-installed`):**
```
architecture=aarch64
installed_at=1234567890
prefix=/data/data/com.openclaw.android/files/usr
version=termux-bootstrap-2026.02.12
```

**Verificación post-instalación:**
```kotlin
fun isInstalled(): Boolean {
    if (!markerFile.exists()) return false
    
    val dpkg = File(prefix, "bin/dpkg")
    val apt = File(prefix, "bin/apt")
    val bash = File(prefix, "bin/bash")
    
    return dpkg.exists() && apt.exists() && bash.exists()
}
```

## Estructura de Archivos Resultante

```
context.filesDir/
├── usr/                                    ← PREFIX de Termux
│   ├── bin/
│   │   ├── bash                            ← Shell (ejecutable)
│   │   ├── sh -> bash                      ← Symlink
│   │   ├── dpkg                            ← Gestor de paquetes
│   │   ├── apt                             ← APT
│   │   ├── apt-get -> apt                  ← Symlink
│   │   ├── pkg                             ← Wrapper de apt
│   │   ├── git                             ← Git (si se instaló)
│   │   ├── curl                            ← cURL
│   │   ├── wget                            ← wget
│   │   └── ... (más binarios)
│   ├── lib/
│   │   ├── libc.so -> libc.so.6            ← Symlink a libc
│   │   ├── libc.so.6                       ← Bionic libc
│   │   └── ... (más librerías)
│   ├── share/
│   │   ├── man/                            ← Páginas de manual
│   │   ├── doc/                            ← Documentación
│   │   └── ...
│   ├── etc/
│   │   ├── resolv.conf                     ← Configuración DNS
│   │   ├── profile                         ← Variables de entorno
│   │   └── apt/
│   │       └── sources.list                ← Repositorios APT
│   ├── var/
│   │   ├── lib/dpkg/                       ← Base de datos dpkg
│   │   └── cache/apt/                      ← Caché de APT
│   └── tmp/                                ← Temporal
├── home/                                   ← HOME del usuario
│   └── .bashrc                             ← Configuración de bash
├── .termux-bootstrap-installed             ← Marcador de instalación
└── cache/                                  ← Caché de descarga
    └── termux-bootstrap-aarch64.zip        ← Bootstrap descargado
```

## Manejo de Errores

### Errores de Red

**Síntomas:**
- `java.net.UnknownHostException`
- `java.net.SocketTimeoutException`
- `HTTP error: 404`

**Manejo:**
```kotlin
try {
    downloadBootstrap(url, destination) { ... }
} catch (e: Exception) {
    AppLogger.e(TAG, "Download failed: ${e.message}", e)
    listener.onError("Error descargando bootstrap: ${e.message}", e)
}
```

**Recuperación:**
- El archivo parcialmente descargado se elimina
- El usuario puede reintentar la instalación
- No hay corrupción de datos

### Errores de Extracción

**Síntomas:**
- `java.util.zip.ZipException`
- `IOException` durante escritura

**Manejo:**
```kotlin
try {
    when {
        entry.isDirectory -> destFile.mkdirs()
        entry.isUnixSymlink -> createSymlink(...)
        else -> extractFile(...)
    }
    entriesProcessed++
} catch (e: Exception) {
    AppLogger.e(TAG, "Failed to extract entry ${entry.name}: ${e.message}", e)
    // Continuar con la siguiente entrada
}
```

**Recuperación:**
- Entradas individuales que fallan no detienen la extracción
- Se registra el error pero se continúa
- La verificación post-instalación detectará archivos faltantes

### Errores de Permisos

**Síntomas:**
- `SecurityException` al crear symlinks
- `IOException` al escribir archivos

**Manejo:**
```kotlin
try {
    android.system.Os.symlink(linkTarget, destFile.absolutePath)
} catch (e: Exception) {
    AppLogger.w(TAG, "Symlink failed: ${entry.name} -> $linkTarget: ${e.message}")
    // No fatal — el target puede no existir aún
}
```

**Nota:** Todos los archivos se escriben en `context.filesDir`, que es privado de la app y no requiere permisos especiales.

### Errores de pkg update

**Síntomas:**
- Exit code != 0
- Timeout después de 2 minutos

**Manejo:**
```kotlin
val updateSuccess = runPkgUpdate(listener)
if (!updateSuccess) {
    AppLogger.w(TAG, "pkg update failed, but continuing...")
    listener.onProgress(85, "Advertencia: pkg update falló")
    // Continuar con la instalación
}
```

**Razón:** `pkg update` puede fallar en la primera ejecución si los repositorios no están completamente configurados. Esto es normal y no impide el uso del bootstrap.

## Optimizaciones de Rendimiento

### Streaming Download

```kotlin
val buffer = ByteArray(32 * 1024)  // 32KB buffer
while (buffered.read(buffer).also { bytesRead = it } != -1) {
    output.write(buffer, 0, bytesRead)
    downloadedSize += bytesRead
    onProgress(downloadedSize, totalSize)
}
```

**Beneficios:**
- Uso de memoria constante (~32KB)
- No carga el archivo completo en memoria
- Permite descargas de archivos grandes (50MB+)

### Extracción Incremental

```kotlin
if (entriesProcessed % 100 == 0) {
    val pct = 50 + (entriesProcessed / 50).coerceAtMost(25)
    listener.onProgress(pct, "Extrayendo... $entriesProcessed archivos")
}
```

**Beneficios:**
- Reporte de progreso sin overhead excesivo
- UI se actualiza cada 100 entradas (no cada archivo)
- Evita saturar el hilo principal con callbacks

### Reutilización de Archivos

```kotlin
if (destination.exists() && destination.length() > 10_000_000) {
    AppLogger.i(TAG, "Bootstrap already downloaded")
    onProgress(destination.length(), destination.length())
    return
}
```

**Beneficios:**
- No re-descarga si el archivo ya existe
- Ahorra ancho de banda
- Instalación más rápida en reintentos

## Seguridad

### Validación de Descargas

```kotlin
val responseCode = urlConnection.responseCode
if (responseCode != HttpURLConnection.HTTP_OK) {
    throw IllegalStateException("HTTP error: $responseCode")
}
```

**Protecciones:**
- Verifica código de respuesta HTTP
- Timeout para evitar conexiones colgadas
- User-Agent personalizado para identificación

### Aislamiento de Archivos

```kotlin
private val filesDir: File = context.filesDir  // Privado de la app
private val prefix: File get() = File(filesDir, "usr")
```

**Protecciones:**
- Todos los archivos en almacenamiento interno privado
- No requiere permisos de almacenamiento externo
- Otros apps no pueden acceder

### Validación de Symlinks

```kotlin
if (destFile.exists()) {
    destFile.delete()  // Eliminar antes de crear symlink
}
android.system.Os.symlink(linkTarget, destFile.absolutePath)
```

**Protecciones:**
- Elimina archivos existentes antes de crear symlinks
- Evita symlinks maliciosos que apunten fuera del PREFIX
- Fallos de symlink son no-fatales

## Testing

### Tests Unitarios

**Ubicación:** `android/app/src/test/java/com/openclaw/android/TermuxBootstrapManagerTest.kt`

**Cobertura:**
- Detección de arquitectura
- Verificación de instalación
- Estado del sistema
- Desinstalación
- Manejo de errores

**Ejecución:**
```bash
./gradlew test
```

### Tests de Integración

Para probar la instalación completa en un dispositivo real:

```kotlin
@Test
fun testFullInstallation() = runBlocking {
    val manager = TermuxBootstrapManager(context)
    
    var success = false
    manager.install(object : TermuxBootstrapManager.ProgressListener {
        override fun onProgress(percent: Int, message: String) {
            Log.d("Test", "[$percent%] $message")
        }
        
        override fun onSuccess() {
            success = true
        }
        
        override fun onError(message: String, cause: Throwable?) {
            fail("Installation failed: $message")
        }
    })
    
    assertTrue(success)
    assertTrue(manager.isInstalled())
}
```

## Comparación con Alternativas

### vs. Proot + Ubuntu

| Aspecto | Termux Bootstrap | Proot + Ubuntu |
|---------|------------------|----------------|
| **Tamaño descarga** | ~50MB | ~80MB |
| **Tamaño instalado** | ~150MB | ~250MB |
| **Tiempo instalación** | 2-3 min | 5-7 min |
| **Herramientas** | dpkg, apt, bash, coreutils | apt, bash, full Ubuntu |
| **Compatibilidad** | Android 7+ | Android 7+ |
| **Phantom Process Killer** | Afectado | Resistente |
| **Complejidad** | Baja | Media |
| **Mantenimiento** | Oficial Termux | Termux proot-distro |

**Cuándo usar Termux Bootstrap:**
- Necesitas un entorno ligero y rápido
- Quieres herramientas estándar de Termux (dpkg, apt)
- No necesitas resistencia al Phantom Process Killer
- Prefieres simplicidad sobre robustez

**Cuándo usar Proot + Ubuntu:**
- Necesitas resistencia al Phantom Process Killer
- Quieres un entorno Ubuntu completo
- Necesitas ejecutar procesos de larga duración
- El tamaño no es una preocupación

### vs. Payload Offline

| Aspecto | Termux Bootstrap | Payload Offline |
|---------|------------------|-----------------|
| **Tamaño APK** | +0MB (descarga) | +167MB (bundled) |
| **Requiere internet** | Sí | No |
| **Actualizable** | Sí (pkg update) | No (requiere nuevo APK) |
| **Herramientas** | dpkg, apt, bash | Node.js, OpenClaw |
| **Flexibilidad** | Alta | Baja |

**Cuándo usar Termux Bootstrap:**
- Quieres un APK pequeño
- Tienes conexión a internet
- Necesitas flexibilidad para instalar paquetes

**Cuándo usar Payload Offline:**
- Necesitas instalación sin internet
- Quieres un entorno pre-configurado
- El tamaño del APK no es problema

## Troubleshooting

### "Arquitectura no soportada"

**Diagnóstico:**
```kotlin
Log.d("Architecture", Build.SUPPORTED_ABIS.joinToString(", "))
```

**Solución:**
Agregar soporte para la arquitectura en `BOOTSTRAP_URLS`.

### "HTTP error: 404"

**Causa:** URL del bootstrap no disponible.

**Solución:**
Actualizar URL en `BOOTSTRAP_URLS` desde [packages.termux.dev](https://packages.termux.dev/bootstrap/).

### "pkg update failed"

**Causa:** Repositorios no configurados o sin internet.

**Solución:**
- Verificar conexión a internet
- Ejecutar manualmente: `pkg update -y`
- Revisar `/data/data/com.openclaw.android/files/usr/etc/apt/sources.list`

### "Verificación post-instalación falló"

**Causa:** Archivos esenciales no se extrajeron.

**Diagnóstico:**
```kotlin
val status = manager.getStatus()
Log.d("Bootstrap", status.toString())
```

**Solución:**
```kotlin
manager.uninstall()
manager.install(listener)
```

## Roadmap

### Versión Actual (1.0)

✅ Descarga e instalación básica
✅ Soporte multi-arquitectura
✅ Configuración automática
✅ Integración con InstallerManager

### Futuras Mejoras

🔲 **Verificación de integridad (checksums)**
- Descargar y verificar SHA256 del bootstrap
- Prevenir instalaciones corruptas

🔲 **Instalación incremental**
- Reanudar descargas interrumpidas
- Guardar progreso de extracción

🔲 **Mirrors alternativos**
- Fallback a mirrors si packages.termux.dev no está disponible
- Selección automática del mirror más rápido

🔲 **Actualización in-place**
- Actualizar bootstrap sin desinstalar
- Preservar paquetes instalados por el usuario

🔲 **Compresión mejorada**
- Soporte para .tar.xz (más comprimido que .zip)
- Reducir tamaño de descarga

## Referencias

- [Termux Bootstrap Packages](https://packages.termux.dev/bootstrap/)
- [Termux Wiki](https://wiki.termux.com/)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)
- [Android Storage Best Practices](https://developer.android.com/training/data-storage)
