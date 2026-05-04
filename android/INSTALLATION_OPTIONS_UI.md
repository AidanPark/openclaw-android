# Opciones de Instalación - Guía de UI

## Flujo Recomendado para tu App

### 1. Pantalla Inicial (Primera Vez)

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│              🦀 OpenClaw Android                    │
│                                                     │
│  Para usar esta app, necesitas instalar un         │
│  entorno de terminal.                              │
│                                                     │
│  ┌───────────────────────────────────────────┐     │
│  │  📦 Termux Bootstrap (Recomendado)        │     │
│  │                                           │     │
│  │  ✓ Ligero (~50MB)                         │     │
│  │  ✓ Terminal Termux completo               │     │
│  │  ✓ Incluye: bash, dpkg, apt, pkg          │     │
│  │  ✓ Instalación rápida (2-3 min)           │     │
│  │                                           │     │
│  │  [  Instalar Termux Bootstrap  ]          │     │
│  └───────────────────────────────────────────┘     │
│                                                     │
│  ┌───────────────────────────────────────────┐     │
│  │  ⚙️ Opciones Avanzadas                    │     │
│  │                                           │     │
│  │  • Proot + Ubuntu (Sistema completo)      │     │
│  │  • Payload Offline (Sin internet)         │     │
│  │                                           │     │
│  │  [  Ver Opciones Avanzadas  ]             │     │
│  └───────────────────────────────────────────┘     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 2. Pantalla de Opciones Avanzadas

```
┌─────────────────────────────────────────────────────┐
│  ← Volver                                           │
│                                                     │
│         Opciones de Instalación Avanzadas          │
│                                                     │
│  ┌───────────────────────────────────────────┐     │
│  │  📦 Termux Bootstrap (Recomendado)        │     │
│  │                                           │     │
│  │  Tamaño: ~50MB                            │     │
│  │  Tiempo: 2-3 minutos                      │     │
│  │  Requiere: Internet                       │     │
│  │                                           │     │
│  │  Incluye:                                 │     │
│  │  • bash, sh, coreutils                    │     │
│  │  • dpkg, apt, pkg                         │     │
│  │  • git, curl, wget                        │     │
│  │                                           │     │
│  │  [  Instalar  ]                           │     │
│  └───────────────────────────────────────────┘     │
│                                                     │
│  ┌───────────────────────────────────────────┐     │
│  │  🐧 Proot + Ubuntu (Avanzado)             │     │
│  │                                           │     │
│  │  Tamaño: ~250MB                           │     │
│  │  Tiempo: 5-7 minutos                      │     │
│  │  Requiere: Internet                       │     │
│  │                                           │     │
│  │  Ventajas:                                │     │
│  │  • Sistema Ubuntu completo                │     │
│  │  • Resistente a Phantom Process Killer    │     │
│  │  • Ideal para procesos largos             │     │
│  │                                           │     │
│  │  ⚠️ Solo para usuarios avanzados          │     │
│  │                                           │     │
│  │  [  Instalar  ]                           │     │
│  └───────────────────────────────────────────┘     │
│                                                     │
│  ┌───────────────────────────────────────────┐     │
│  │  📂 Payload Offline                       │     │
│  │                                           │     │
│  │  Tamaño: Incluido en APK                  │     │
│  │  Tiempo: 1-2 minutos                      │     │
│  │  Requiere: Nada (offline)                 │     │
│  │                                           │     │
│  │  ℹ️ Solo disponible si el APK incluye     │     │
│  │     el payload pre-empaquetado            │     │
│  │                                           │     │
│  │  [  Instalar  ]  (Deshabilitado)          │     │
│  └───────────────────────────────────────────┘     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 3. Código de Ejemplo para MainActivity

```kotlin
class MainActivity : AppCompatActivity() {
    
    private lateinit var installerManager: InstallerManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        installerManager = InstallerManager(this)
        
        // Verificar si necesita instalación
        if (!installerManager.isInstalled()) {
            showInstallationOptions()
        } else {
            startTerminal()
        }
    }
    
    private fun showInstallationOptions() {
        // Mostrar diálogo con opciones
        val options = arrayOf(
            "Termux Bootstrap (Recomendado)",
            "Opciones Avanzadas"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Instalación Requerida")
            .setMessage("Elige cómo instalar el entorno de terminal:")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> installTermuxBootstrap()
                    1 -> showAdvancedOptions()
                }
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showAdvancedOptions() {
        val options = arrayOf(
            "Termux Bootstrap (~50MB, recomendado)",
            "Proot + Ubuntu (~250MB, avanzado)",
            "Payload Offline (si está disponible)"
        )
        
        val descriptions = arrayOf(
            "Terminal Termux completo con dpkg, apt, pkg",
            "Sistema Ubuntu completo, resistente a Phantom Process Killer",
            "Instalación offline desde APK (si está bundled)"
        )
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Opciones de Instalación")
        
        // Verificar qué opciones están disponibles
        val hasPayload = installerManager.hasPayloadAsset()
        
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> installTermuxBootstrap()
                1 -> installProot()
                2 -> {
                    if (hasPayload) {
                        installOffline()
                    } else {
                        Toast.makeText(
                            this,
                            "Payload no disponible en este APK",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        
        builder.setNegativeButton("Volver") { _, _ ->
            showInstallationOptions()
        }
        
        builder.show()
    }
    
    private fun installTermuxBootstrap() {
        showProgressDialog("Instalando Termux Bootstrap...")
        
        lifecycleScope.launch {
            installerManager.install("termux-bootstrap", null, 
                object : InstallerManager.ProgressListener {
                    override fun onProgress(percent: Int, message: String) {
                        runOnUiThread {
                            updateProgress(percent, message)
                        }
                    }
                    
                    override fun onSuccess() {
                        runOnUiThread {
                            hideProgressDialog()
                            Toast.makeText(
                                this@MainActivity,
                                "Termux Bootstrap instalado correctamente",
                                Toast.LENGTH_SHORT
                            ).show()
                            startTerminal()
                        }
                    }
                    
                    override fun onError(message: String, cause: Throwable?) {
                        runOnUiThread {
                            hideProgressDialog()
                            showErrorDialog(message)
                        }
                    }
                })
        }
    }
    
    private fun installProot() {
        // Mostrar advertencia antes de instalar proot
        AlertDialog.Builder(this)
            .setTitle("⚠️ Instalación Avanzada")
            .setMessage(
                "Proot + Ubuntu es una opción avanzada que:\n\n" +
                "• Descarga ~250MB\n" +
                "• Tarda 5-7 minutos\n" +
                "• Instala un sistema Ubuntu completo\n\n" +
                "Solo recomendado si:\n" +
                "• Necesitas resistencia al Phantom Process Killer\n" +
                "• Ejecutas procesos de larga duración\n" +
                "• Tienes experiencia con Linux\n\n" +
                "¿Continuar?"
            )
            .setPositiveButton("Sí, instalar") { _, _ ->
                showProgressDialog("Instalando Proot + Ubuntu...")
                
                lifecycleScope.launch {
                    installerManager.install("proot", null,
                        object : InstallerManager.ProgressListener {
                            override fun onProgress(percent: Int, message: String) {
                                runOnUiThread {
                                    updateProgress(percent, message)
                                }
                            }
                            
                            override fun onSuccess() {
                                runOnUiThread {
                                    hideProgressDialog()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Proot + Ubuntu instalado correctamente",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    startTerminal()
                                }
                            }
                            
                            override fun onError(message: String, cause: Throwable?) {
                                runOnUiThread {
                                    hideProgressDialog()
                                    showErrorDialog(message)
                                }
                            }
                        })
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                showAdvancedOptions()
            }
            .show()
    }
    
    private fun installOffline() {
        showProgressDialog("Instalando desde payload offline...")
        
        lifecycleScope.launch {
            installerManager.install("offline", null,
                object : InstallerManager.ProgressListener {
                    override fun onProgress(percent: Int, message: String) {
                        runOnUiThread {
                            updateProgress(percent, message)
                        }
                    }
                    
                    override fun onSuccess() {
                        runOnUiThread {
                            hideProgressDialog()
                            Toast.makeText(
                                this@MainActivity,
                                "Payload instalado correctamente",
                                Toast.LENGTH_SHORT
                            ).show()
                            startTerminal()
                        }
                    }
                    
                    override fun onError(message: String, cause: Throwable?) {
                        runOnUiThread {
                            hideProgressDialog()
                            showErrorDialog(message)
                        }
                    }
                })
        }
    }
    
    private fun startTerminal() {
        // Iniciar el terminal
        // Tu código existente para mostrar el terminal
    }
    
    // Métodos auxiliares para UI
    private var progressDialog: AlertDialog? = null
    
    private fun showProgressDialog(title: String) {
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val statusText = view.findViewById<TextView>(R.id.status_text)
        
        progressDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setCancelable(false)
            .create()
        
        progressDialog?.show()
    }
    
    private fun updateProgress(percent: Int, message: String) {
        progressDialog?.findViewById<ProgressBar>(R.id.progress_bar)?.progress = percent
        progressDialog?.findViewById<TextView>(R.id.status_text)?.text = message
    }
    
    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }
    
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error de Instalación")
            .setMessage(message)
            .setPositiveButton("Reintentar") { _, _ ->
                showInstallationOptions()
            }
            .setNegativeButton("Salir") { _, _ ->
                finish()
            }
            .show()
    }
}
```

## Resumen de Diferencias

| Característica | Termux Bootstrap | Proot + Ubuntu | Payload Offline |
|----------------|------------------|----------------|-----------------|
| **Tamaño** | ~50MB | ~250MB | ~167MB (bundled) |
| **Tiempo** | 2-3 min | 5-7 min | 1-2 min |
| **Internet** | ✅ Requiere | ✅ Requiere | ❌ No requiere |
| **Terminal** | Termux completo | Ubuntu bash | Pre-configurado |
| **dpkg/apt** | ✅ Sí | ✅ Sí | ❌ No |
| **pkg** | ✅ Sí | ❌ No (usa apt) | ❌ No |
| **Phantom Process Killer** | ⚠️ Afectado | ✅ Resistente | ⚠️ Afectado |
| **Complejidad** | Baja | Alta | Baja |
| **Recomendado para** | Uso general | Usuarios avanzados | Sin internet |

## Recomendación Final

1. **Por defecto**: Mostrar solo "Termux Bootstrap" como opción principal
2. **Avanzado**: Poner "Proot + Ubuntu" en opciones avanzadas con advertencia
3. **Terminal**: `TerminalSessionManager` detecta automáticamente qué está instalado y usa el shell apropiado

El usuario no necesita saber los detalles técnicos, solo:
- "¿Quieres el terminal estándar?" → Termux Bootstrap
- "¿Eres usuario avanzado y necesitas más robustez?" → Proot + Ubuntu
