package com.openclaw.android

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ejemplo de integración del TermuxBootstrapManager.
 *
 * Este archivo muestra cómo usar el sistema de bootstrap de Termux
 * en diferentes escenarios de tu app.
 */

// ═══════════════════════════════════════════════════════════════════════════
// Ejemplo 1: Instalación automática al iniciar la app
// ═══════════════════════════════════════════════════════════════════════════

class BootstrapExampleActivity : AppCompatActivity() {

    private lateinit var installerManager: InstallerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        installerManager = InstallerManager(this)

        // Verificar si necesita instalación
        if (!installerManager.isInstalled()) {
            showInstallDialog()
        } else {
            // Ya está instalado, continuar con la app
            onBootstrapReady()
        }
    }

    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Instalación Requerida")
            .setMessage(
                "Esta app necesita instalar el entorno Termux (~50MB).\n\n" +
                "Opciones disponibles:\n" +
                "• Termux Bootstrap: Ligero, con dpkg/apt (~50MB)\n" +
                "• Proot + Ubuntu: Robusto, entorno completo (~250MB)\n" +
                "• Payload Offline: Rápido, sin internet (si está bundled)"
            )
            .setPositiveButton("Termux Bootstrap") { _, _ ->
                installBootstrap("termux-bootstrap")
            }
            .setNeutralButton("Proot + Ubuntu") { _, _ ->
                installBootstrap("proot")
            }
            .setNegativeButton("Auto (Recomendado)") { _, _ ->
                installBootstrap("auto")
            }
            .setCancelable(false)
            .show()
    }

    private fun installBootstrap(mode: String) {
        // Aquí deberías mostrar un overlay de progreso en tu UI real
        // Por simplicidad, solo mostramos logs
        
        lifecycleScope.launch {
            installerManager.install(mode, null, object : InstallerManager.ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    runOnUiThread {
                        // Actualizar UI de progreso
                        AppLogger.i("Bootstrap", "[$percent%] $message")
                    }
                }

                override fun onSuccess() {
                    runOnUiThread {
                        AppLogger.i("Bootstrap", "Instalación completada")
                        onBootstrapReady()
                    }
                }

                override fun onError(message: String, cause: Throwable?) {
                    runOnUiThread {
                        if (cause != null) AppLogger.e("Bootstrap", "Error: $message", cause)
                        else AppLogger.e("Bootstrap", "Error: $message")
                        showErrorDialog(message)
                    }
                }
            })
        }
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error de Instalación")
            .setMessage(message)
            .setPositiveButton("Reintentar") { _, _ -> showInstallDialog() }
            .setNegativeButton("Salir") { _, _ -> finish() }
            .show()
    }

    private fun onBootstrapReady() {
        AppLogger.i("Bootstrap", "Entorno listo, iniciando app...")
        // Continuar con el flujo normal de tu app
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Ejemplo 2: Uso directo del TermuxBootstrapManager
// ═══════════════════════════════════════════════════════════════════════════

class DirectBootstrapExample(private val context: Context) {

    private val bootstrapManager = TermuxBootstrapManager(context)

    /**
     * Verifica el estado del bootstrap e instala si es necesario.
     */
    suspend fun ensureBootstrapInstalled(): Boolean {
        // Verificar si ya está instalado
        if (bootstrapManager.isInstalled()) {
            AppLogger.i("Bootstrap", "Ya está instalado")
            return true
        }

        // Instalar
        var success = false
        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                AppLogger.d("Bootstrap", "[$percent%] $message")
            }

            override fun onSuccess() {
                AppLogger.i("Bootstrap", "Instalación exitosa")
                success = true
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) AppLogger.e("Bootstrap", "Error: $message", cause)
                else AppLogger.e("Bootstrap", "Error: $message")
                success = false
            }
        })

        return success
    }

    /**
     * Obtiene información detallada del bootstrap.
     */
    fun getBootstrapInfo(): String {
        val status = bootstrapManager.getStatus()
        return buildString {
            appendLine("Estado del Bootstrap:")
            appendLine("  Instalado: ${status["installed"]}")
            appendLine("  Arquitectura: ${status["architecture"]}")
            appendLine("  Ruta PREFIX: ${status["prefixPath"]}")
            appendLine("  Tamaño: ${status["prefixSizeMB"]} MB")
            appendLine("  dpkg: ${if (status["dpkgExists"] == true) "✓" else "✗"}")
            appendLine("  apt: ${if (status["aptExists"] == true) "✓" else "✗"}")
            appendLine("  bash: ${if (status["bashExists"] == true) "✓" else "✗"}")
        }
    }

    /**
     * Desinstala el bootstrap (útil para recuperación de errores).
     */
    fun uninstall() {
        bootstrapManager.uninstall()
        AppLogger.i("Bootstrap", "Bootstrap desinstalado")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Ejemplo 3: Ejecutar comandos después de la instalación
// ═══════════════════════════════════════════════════════════════════════════

class BootstrapCommandRunner(private val context: Context) {

    private val bootstrapManager = TermuxBootstrapManager(context)

    /**
     * Ejecuta un comando usando el bash del bootstrap.
     */
    fun runCommand(command: String, onOutput: (String) -> Unit): Int {
        if (!bootstrapManager.isInstalled()) {
            AppLogger.e("Bootstrap", "Bootstrap no está instalado")
            return -1
        }

        val prefix = File(context.filesDir, "usr")
        val bash = File(prefix, "bin/bash")
        val homeDir = File(context.filesDir, "home")

        if (!bash.exists()) {
            AppLogger.e("Bootstrap", "bash no encontrado en ${bash.absolutePath}")
            return -1
        }

        val fullCommand = listOf(
            bash.absolutePath,
            "-c",
            "export PATH=${prefix.absolutePath}/bin:\$PATH && $command"
        )

        return try {
            val pb = ProcessBuilder(fullCommand)
            pb.environment().apply {
                put("HOME", homeDir.absolutePath)
                put("PREFIX", prefix.absolutePath)
                put("TMPDIR", File(prefix, "tmp").absolutePath)
                put("PATH", "${prefix.absolutePath}/bin:/system/bin:/bin")
                put("LANG", "en_US.UTF-8")
            }
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()

            // Leer salida
            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d("Command", line)
                    onOutput(line)
                }
            }
            outputThread.start()

            val exitCode = process.waitFor()
            outputThread.join(5000)

            AppLogger.i("Command", "Comando terminó con código $exitCode")
            exitCode

        } catch (e: Exception) {
            AppLogger.e("Command", "Error ejecutando comando: ${e.message}", e)
            -1
        }
    }

    /**
     * Instala un paquete usando pkg.
     */
    fun installPackage(packageName: String, onOutput: (String) -> Unit): Boolean {
        AppLogger.i("Bootstrap", "Instalando paquete: $packageName")
        val exitCode = runCommand("pkg install -y $packageName", onOutput)
        return exitCode == 0
    }

    /**
     * Actualiza los repositorios.
     */
    fun updateRepositories(onOutput: (String) -> Unit): Boolean {
        AppLogger.i("Bootstrap", "Actualizando repositorios...")
        val exitCode = runCommand("pkg update -y", onOutput)
        return exitCode == 0
    }

    /**
     * Lista los paquetes instalados.
     */
    fun listInstalledPackages(onOutput: (String) -> Unit): Boolean {
        AppLogger.i("Bootstrap", "Listando paquetes instalados...")
        val exitCode = runCommand("dpkg -l", onOutput)
        return exitCode == 0
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Ejemplo 4: Integración con JsBridge (para llamadas desde WebView)
// ═══════════════════════════════════════════════════════════════════════════

class BootstrapJsBridge(private val context: Context) {

    private val bootstrapManager = TermuxBootstrapManager(context)

    /**
     * Verifica si el bootstrap está instalado.
     * Llamable desde JavaScript: window.bootstrap.isInstalled()
     */
    @android.webkit.JavascriptInterface
    fun isInstalled(): Boolean {
        return bootstrapManager.isInstalled()
    }

    /**
     * Obtiene el estado del bootstrap como JSON.
     * Llamable desde JavaScript: window.bootstrap.getStatus()
     */
    @android.webkit.JavascriptInterface
    fun getStatus(): String {
        val status = bootstrapManager.getStatus()
        return com.google.gson.Gson().toJson(status)
    }

    /**
     * Instala el bootstrap (debe ejecutarse en background).
     * Llamable desde JavaScript: window.bootstrap.install()
     */
    @android.webkit.JavascriptInterface
    fun install() {
        // Nota: Este método debe ejecutarse en un hilo de background
        // y notificar al WebView del progreso via JavaScript callbacks
        Thread {
            kotlinx.coroutines.runBlocking {
                bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
                    override fun onProgress(percent: Int, message: String) {
                        // Notificar al WebView
                        notifyWebView("onBootstrapProgress", percent, message)
                    }

                    override fun onSuccess() {
                        notifyWebView("onBootstrapSuccess")
                    }

                    override fun onError(message: String, cause: Throwable?) {
                        notifyWebView("onBootstrapError", message)
                    }
                })
            }
        }.start()
    }

    /**
     * Ejecuta un comando y retorna la salida.
     * Llamable desde JavaScript: window.bootstrap.runCommand("pkg list-installed")
     */
    @android.webkit.JavascriptInterface
    fun runCommand(command: String): String {
        val runner = BootstrapCommandRunner(context)
        val output = StringBuilder()
        runner.runCommand(command) { line ->
            output.appendLine(line)
        }
        return output.toString()
    }

    private fun notifyWebView(event: String, vararg args: Any) {
        // Implementar notificación al WebView
        // Ejemplo: webView.evaluateJavascript("window.onBootstrapEvent('$event', ...)", null)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Ejemplo 5: Uso en un Service (instalación en background)
// ═══════════════════════════════════════════════════════════════════════════

class BootstrapInstallService : android.app.Service() {

    private lateinit var bootstrapManager: TermuxBootstrapManager

    override fun onCreate() {
        super.onCreate()
        bootstrapManager = TermuxBootstrapManager(this)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // Crear notificación de foreground service
        val notification = createNotification("Instalando Termux Bootstrap...", 0)
        startForeground(1, notification)

        // Instalar en background
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    // Actualizar notificación
                    val notification = createNotification(message, percent)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) 
                        as android.app.NotificationManager
                    notificationManager.notify(1, notification)
                }

                override fun onSuccess() {
                    AppLogger.i("Service", "Bootstrap instalado exitosamente")
                    stopSelf()
                }

                override fun onError(message: String, cause: Throwable?) {
                    if (cause != null) AppLogger.e("Service", "Error: $message", cause)
                    else AppLogger.e("Service", "Error: $message")
                    stopSelf()
                }
            })
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    private fun createNotification(message: String, progress: Int): android.app.Notification {
        val channelId = "bootstrap_install"
        
        // Crear canal de notificación (Android 8+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Bootstrap Installation",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Termux Bootstrap")
            .setContentText(message)
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }
}
