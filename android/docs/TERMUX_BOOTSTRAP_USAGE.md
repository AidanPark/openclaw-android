# Termux Bootstrap Manager - Guía de Uso

## Descripción

El `TermuxBootstrapManager` proporciona un sistema robusto para descargar e instalar el bootstrap oficial de Termux en tu app Android. Este sistema reconstruye el entorno base de Termux embebido, proporcionando `dpkg`, `apt` y otras herramientas esenciales que actualmente faltan en tu entorno.

## Características

✅ **Descarga automática del bootstrap oficial de Termux**
- Detecta automáticamente la arquitectura del dispositivo (aarch64, arm, x86_64, i686)
- Descarga el bootstrap correspondiente desde `packages.termux.dev`
- Tamaño: ~50MB (mucho más ligero que proot con Ubuntu ~250MB)

✅ **Extracción robusta**
- Usa Apache Commons Compress (ya incluido en tu proyecto)
- Preserva permisos de ejecución (modo 0755)
- Maneja symlinks correctamente
- No corrompe archivos si se interrumpe

✅ **Configuración automática**
- Configura DNS (resolv.conf)
- Crea archivos de perfil
- Ejecuta `pkg update` automáticamente
- Instala paquetes adicionales (git, curl, wget)

✅ **Integración perfecta**
- Se integra con tu `InstallerManager` existente
- Compatible con tu arquitectura de callbacks
- Ejecuta en `Dispatchers.IO` (no bloquea UI)
- Reporta progreso detallado

## Uso Básico

### 1. Instalación desde InstallerManager

```kotlin
// En tu Activity o ViewModel
val installerManager = InstallerManager(context)

lifecycleScope.launch {
    installerManager.install("termux-bootstrap", null, object : InstallerManager.ProgressListener {
        override fun onProgress(percent: Int, message: String) {
            // Actualizar UI con progreso
            runOnUiThread {
                progressBar.progress = percent
                statusText.text = message
            }
        }

        override fun onSuccess() {
            runOnUiThread {
                Toast.makeText(context, "Bootstrap instalado correctamente", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(message: String, cause: Throwable?) {
            runOnUiThread {
                Toast.makeText(context, "Error: $message", Toast.LENGTH_LONG).show()
            }
        }
    })
}
```

### 2. Uso directo del TermuxBootstrapManager

```kotlin
val bootstrapManager = TermuxBootstrapManager(context)

// Verificar si ya está instalado
if (bootstrapManager.isInstalled()) {
    Log.i("Bootstrap", "Ya está instalado")
} else {
    // Instalar
    lifecycleScope.launch {
        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                Log.d("Bootstrap", "[$percent%] $message")
            }

            override fun onSuccess() {
                Log.i("Bootstrap", "Instalación completada")
            }

            override fun onError(message: String, cause: Throwable?) {
                Log.e("Bootstrap", "Error: $message", cause)
            }
        })
    }
}
```

### 3. Verificar estado

```kotlin
val bootstrapManager = TermuxBootstrapManager(context)
val status = bootstrapManager.getStatus()

Log.i("Bootstrap", """
    Instalado: ${status["installed"]}
    Arquitectura: ${status["architecture"]}
    Ruta PREFIX: ${status["prefixPath"]}
    Tamaño: ${status["prefixSizeMB"]} MB
    dpkg disponible: ${status["dpkgExists"]}
    apt disponible: ${status["aptExists"]}
    bash disponible: ${status["bashExists"]}
""".trimIndent())
```

### 4. Ejecutar comandos después de la instalación

```kotlin
// Una vez instalado, puedes ejecutar comandos pkg
val bash = File(context.filesDir, "usr/bin/bash")
val prefix = File(context.filesDir, "usr")

val command = listOf(
    bash.absolutePath,
    "-c",
    "export PATH=${prefix.absolutePath}/bin:\$PATH && pkg install git"
)

val pb = ProcessBuilder(command)
pb.environment().apply {
    put("HOME", File(context.filesDir, "home").absolutePath)
    put("PREFIX", prefix.absolutePath)
    put("PATH", "${prefix.absolutePath}/bin:/system/bin")
}

val process = pb.start()
process.inputStream.bufferedReader().forEachLine { line ->
    Log.d("pkg", line)
}
val exitCode = process.waitFor()
```

## Integración con tu UI

### Ejemplo de Activity con overlay de progreso

```kotlin
class MainActivity : AppCompatActivity() {
    
    private lateinit var installerManager: InstallerManager
    private lateinit var progressOverlay: View
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        installerManager = InstallerManager(this)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        
        // Verificar si necesita instalación
        if (!installerManager.isInstalled()) {
            installBootstrap()
        }
    }
    
    private fun installBootstrap() {
        progressOverlay.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            installerManager.install("termux-bootstrap", null, object : InstallerManager.ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    runOnUiThread {
                        progressBar.progress = percent
                        statusText.text = message
                    }
                }
                
                override fun onSuccess() {
                    runOnUiThread {
                        progressOverlay.visibility = View.GONE
                        Toast.makeText(
                            this@MainActivity,
                            "Bootstrap instalado correctamente",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                
                override fun onError(message: String, cause: Throwable?) {
                    runOnUiThread {
                        progressOverlay.visibility = View.GONE
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Error de instalación")
                            .setMessage(message)
                            .setPositiveButton("Reintentar") { _, _ -> installBootstrap() }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            })
        }
    }
}
```

## Arquitecturas Soportadas

El sistema detecta automáticamente la arquitectura del dispositivo usando `Build.SUPPORTED_ABIS`:

| Arquitectura | Dispositivos | Bootstrap URL |
|--------------|--------------|---------------|
| **aarch64** (arm64-v8a) | Mayoría de teléfonos modernos | `bootstrap-aarch64.zip` |
| **arm** (armeabi-v7a) | Dispositivos ARM de 32 bits | `bootstrap-arm.zip` |
| **x86_64** | Emuladores, ChromeOS | `bootstrap-x86_64.zip` |
| **i686** (x86) | Emuladores antiguos | `bootstrap-i686.zip` |

## Estructura del Bootstrap Instalado

Después de la instalación, tendrás la siguiente estructura en `context.filesDir`:

```
filesDir/
├── usr/                          ← PREFIX de Termux
│   ├── bin/
│   │   ├── bash                  ← Shell
│   │   ├── dpkg                  ← Gestor de paquetes
│   │   ├── apt                   ← APT
│   │   ├── pkg                   ← Wrapper de apt
│   │   ├── git                   ← Git (si se instaló)
│   │   └── ...
│   ├── lib/                      ← Librerías
│   ├── share/                    ← Datos compartidos
│   ├── etc/
│   │   ├── resolv.conf           ← Configuración DNS
│   │   └── profile               ← Variables de entorno
│   └── tmp/                      ← Temporal
├── home/                         ← HOME del usuario
└── .termux-bootstrap-installed   ← Marcador de instalación
```

## Comparación con otras opciones

| Característica | Termux Bootstrap | Proot + Ubuntu | Payload Offline |
|----------------|------------------|----------------|-----------------|
| **Tamaño descarga** | ~50MB | ~80MB | ~167MB (bundled) |
| **Tamaño instalado** | ~150MB | ~250MB | ~400MB |
| **Herramientas** | dpkg, apt, bash | apt, bash, full Ubuntu | Node.js, OpenClaw |
| **Tiempo instalación** | 2-3 min | 5-7 min | 1-2 min |
| **Compatibilidad** | Android 7+ | Android 7+ | Android 7+ |
| **Phantom Process Killer** | Afectado | Resistente | Afectado |
| **Uso de red** | Requiere internet | Requiere internet | No requiere |

## Solución de Problemas

### Error: "Arquitectura no soportada"

**Causa**: El dispositivo tiene una arquitectura no reconocida.

**Solución**: Verifica `Build.SUPPORTED_ABIS` en tu dispositivo:
```kotlin
Log.d("Architecture", Build.SUPPORTED_ABIS.joinToString(", "))
```

### Error: "HTTP error: 404"

**Causa**: La URL del bootstrap no está disponible.

**Solución**: Verifica que la URL en `BOOTSTRAP_URLS` sea correcta. Puedes actualizarla a una versión más reciente desde [packages.termux.dev](https://packages.termux.dev/bootstrap/).

### Error: "pkg update failed"

**Causa**: Problemas de red o repositorios no disponibles.

**Solución**: 
1. Verifica la conexión a internet
2. El sistema continúa la instalación aunque `pkg update` falle
3. Puedes ejecutar `pkg update` manualmente después

### Error: "Verificación post-instalación falló"

**Causa**: Archivos esenciales no se extrajeron correctamente.

**Solución**:
```kotlin
// Desinstalar y reintentar
bootstrapManager.uninstall()
bootstrapManager.install(listener)
```

## Desinstalación

Para eliminar completamente el bootstrap:

```kotlin
val bootstrapManager = TermuxBootstrapManager(context)
bootstrapManager.uninstall()
```

Esto eliminará:
- Todo el directorio PREFIX (`usr/`)
- El marcador de instalación
- Archivos de configuración

## Notas Técnicas

### Permisos

El sistema NO requiere permisos especiales. Todo se instala en el almacenamiento interno de la app (`context.filesDir`).

### Threads

Todos los métodos de instalación ejecutan en `Dispatchers.IO`. Los callbacks de progreso también se ejecutan en background threads, por lo que debes usar `runOnUiThread` o `withContext(Dispatchers.Main)` para actualizar la UI.

### Memoria

El sistema usa streaming para la descarga y extracción, manteniendo el uso de memoria bajo (~32KB buffers). No carga archivos completos en memoria.

### Interrupción

Si la instalación se interrumpe (app cerrada, dispositivo reiniciado), el sistema detectará que la instalación está incompleta y permitirá reintentar sin corrupción de datos.

## Próximos Pasos

Después de instalar el bootstrap, puedes:

1. **Instalar paquetes adicionales**:
   ```bash
   pkg install nodejs python git vim
   ```

2. **Ejecutar scripts**:
   ```bash
   bash /path/to/script.sh
   ```

3. **Usar npm/pip**:
   ```bash
   npm install -g openclaw
   pip install requests
   ```

4. **Integrar con tu terminal embebido**:
   El bootstrap proporciona un bash completo que puedes usar en tu `TerminalManager`.

## Soporte

Si encuentras problemas:

1. Revisa los logs con tag `TermuxBootstrapManager`
2. Verifica el estado con `getStatus()`
3. Intenta desinstalar y reinstalar
4. Reporta el issue con los logs completos
