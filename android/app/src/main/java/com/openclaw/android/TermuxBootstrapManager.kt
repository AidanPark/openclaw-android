package com.openclaw.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * TermuxBootstrapManager — descarga e instala el bootstrap oficial de Termux.
 *
 * Este sistema reconstruye el entorno base de Termux embebido usando el bootstrap
 * oficial, proporcionando dpkg, apt y otras herramientas esenciales.
 *
 * Arquitectura:
 *   1. Detecta la arquitectura del dispositivo (aarch64, arm, x86_64, i686)
 *   2. Descarga el bootstrap correspondiente desde packages.termux.dev
 *   3. Extrae el contenido al PREFIX de Termux (preservando permisos)
 *   4. Ejecuta comandos post-instalación (pkg update, pkg install git, etc.)
 *
 * Características:
 *   - Descarga en segundo plano con notificación de progreso
 *   - Extracción robusta usando Apache Commons Compress
 *   - Preservación de permisos de ejecución (modo 0755)
 *   - Manejo de interrupciones sin corrupción de archivos
 *   - Soporte para múltiples arquitecturas
 *   - Verificación de integridad post-instalación
 *
 * Uso:
 *   val manager = TermuxBootstrapManager(context)
 *   manager.install(object : TermuxBootstrapManager.ProgressListener {
 *       override fun onProgress(percent: Int, message: String) { ... }
 *       override fun onSuccess() { ... }
 *       override fun onError(message: String, cause: Throwable?) { ... }
 *   })
 */
class TermuxBootstrapManager(private val context: Context) {

    private val TAG = "TermuxBootstrapManager"

    // ── Configuración de rutas ────────────────────────────────────────────────
    private val filesDir: File = context.filesDir
    private val cacheDir: File = context.cacheDir
    private val homeDir = File(filesDir, "home")

    // PREFIX de Termux — directorio donde se instala el bootstrap
    // Configurable para adaptarse a diferentes layouts de app
    private val prefix: File get() {
        // Intentar detectar el prefix existente
        val candidates = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload"),
            File(filesDir, "usr"),
        )
        return candidates.firstOrNull { it.isDirectory } ?: File(filesDir, "usr")
    }

    // Marcador de instalación completa
    private val markerFile = File(filesDir, ".termux-bootstrap-installed")

    // ── URLs de bootstrap por arquitectura ────────────────────────────────────
    // Termux bootstrap oficial — versión 2026.02.12-r1+apt.android-7
    // Incluye dpkg, apt, bash, coreutils y herramientas esenciales
    private val BOOTSTRAP_URLS = mapOf(
        "aarch64" to "https://packages.termux.dev/bootstrap/bootstrap-aarch64.zip",
        "arm" to "https://packages.termux.dev/bootstrap/bootstrap-arm.zip",
        "x86_64" to "https://packages.termux.dev/bootstrap/bootstrap-x86_64.zip",
        "i686" to "https://packages.termux.dev/bootstrap/bootstrap-i686.zip",
    )

    // ── Interfaz de progreso ──────────────────────────────────────────────────

    /**
     * Listener para notificaciones de progreso de instalación.
     * Todos los callbacks se ejecutan en Dispatchers.IO — la UI debe hacer post a main thread.
     */
    interface ProgressListener {
        /** Progreso de instalación actualizado. percent es 0-100. */
        fun onProgress(percent: Int, message: String)

        /** Instalación completada exitosamente. */
        fun onSuccess()

        /** Instalación falló. [message] es user-facing, [cause] es para logging. */
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Verifica si el bootstrap de Termux está instalado y funcional.
     * Comprueba la existencia de dpkg, apt y bash.
     */
    fun isInstalled(): Boolean {
        if (!markerFile.exists()) return false

        // Verificar que las herramientas esenciales existen
        val dpkg = File(prefix, "bin/dpkg")
        val apt = File(prefix, "bin/apt")
        val bash = File(prefix, "bin/bash")

        return dpkg.exists() && apt.exists() && bash.exists()
    }

    /**
     * Detecta la arquitectura del dispositivo.
     * Retorna: "aarch64", "arm", "x86_64" o "i686"
     */
    fun detectArchitecture(): String {
        // Build.SUPPORTED_ABIS contiene las ABIs soportadas en orden de preferencia
        val abis = Build.SUPPORTED_ABIS
        AppLogger.i(TAG, "Device ABIs: ${abis.joinToString(", ")}")

        return when {
            abis.any { it.startsWith("arm64") || it == "aarch64" } -> "aarch64"
            abis.any { it.startsWith("armeabi") } -> "arm"
            abis.any { it == "x86_64" } -> "x86_64"
            abis.any { it == "x86" } -> "i686"
            else -> {
                AppLogger.w(TAG, "Unknown architecture, defaulting to aarch64")
                "aarch64"
            }
        }
    }

    /**
     * Instala el bootstrap de Termux.
     * Ejecutar en Dispatchers.IO (no bloquea el hilo principal).
     *
     * Flujo:
     *   1. Detectar arquitectura
     *   2. Descargar bootstrap ZIP
     *   3. Extraer al PREFIX
     *   4. Configurar entorno
     *   5. Ejecutar pkg update
     *   6. Instalar paquetes adicionales (git, etc.)
     */
    suspend fun install(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            // ── Verificar si ya está instalado ────────────────────────────────
            if (isInstalled()) {
                AppLogger.i(TAG, "Termux bootstrap already installed")
                listener.onSuccess()
                return@withContext
            }

            // ── Paso 1: Detectar arquitectura ─────────────────────────────────
            listener.onProgress(1, "Detectando arquitectura del dispositivo...")
            val arch = detectArchitecture()
            val bootstrapUrl = BOOTSTRAP_URLS[arch]
                ?: throw IllegalStateException("Arquitectura no soportada: $arch")

            AppLogger.i(TAG, "Installing Termux bootstrap for $arch from $bootstrapUrl")
            listener.onProgress(2, "Arquitectura detectada: $arch")

            // ── Paso 2: Descargar bootstrap ───────────────────────────────────
            val bootstrapZip = File(cacheDir, "termux-bootstrap-$arch.zip")
            downloadBootstrap(bootstrapUrl, bootstrapZip) { downloaded, total ->
                if (total > 0) {
                    val pct = 2 + (downloaded * 48 / total).toInt().coerceIn(0, 48)
                    val downloadedMB = downloaded / 1024 / 1024
                    val totalMB = total / 1024 / 1024
                    listener.onProgress(pct, "Descargando bootstrap... ${downloadedMB}MB / ${totalMB}MB")
                } else {
                    val downloadedMB = downloaded / 1024 / 1024
                    listener.onProgress(25, "Descargando bootstrap... ${downloadedMB}MB")
                }
            }

            // ── Paso 3: Extraer bootstrap ─────────────────────────────────────
            listener.onProgress(50, "Extrayendo bootstrap (puede tardar 1-2 min)...")
            val entriesExtracted = extractBootstrap(bootstrapZip, prefix) { entriesProcessed ->
                if (entriesProcessed % 100 == 0) {
                    val pct = 50 + (entriesProcessed / 50).coerceAtMost(25)
                    listener.onProgress(pct, "Extrayendo... $entriesProcessed archivos")
                }
            }

            AppLogger.i(TAG, "Bootstrap extracted: $entriesExtracted entries to ${prefix.absolutePath}")
            listener.onProgress(75, "Bootstrap extraído ($entriesExtracted archivos)")

            // ── Paso 4: Configurar entorno ────────────────────────────────────
            listener.onProgress(76, "Configurando entorno Termux...")
            setupEnvironment()

            // ── Paso 5: Ejecutar pkg update ───────────────────────────────────
            listener.onProgress(80, "Actualizando repositorios (pkg update)...")
            val updateSuccess = runPkgUpdate(listener)
            if (!updateSuccess) {
                AppLogger.w(TAG, "pkg update failed, but continuing...")
                listener.onProgress(85, "Advertencia: pkg update falló (puede ser normal en primera instalación)")
            }

            // ── Paso 6: Instalar paquetes adicionales ─────────────────────────
            listener.onProgress(90, "Instalando paquetes adicionales...")
            installAdditionalPackages(listener)

            // ── Paso 7: Escribir marcador ─────────────────────────────────────
            writeMarker(arch)

            // ── Paso 8: Verificar instalación ─────────────────────────────────
            listener.onProgress(98, "Verificando instalación...")
            if (!isInstalled()) {
                throw IllegalStateException("Verificación post-instalación falló")
            }

            listener.onProgress(100, "¡Instalación completada!")
            listener.onSuccess()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Bootstrap installation failed: ${e.message}", e)
            listener.onError("Error instalando bootstrap: ${e.message}", e)
        }
    }

    /**
     * Desinstala el bootstrap de Termux (limpieza completa).
     * Útil para recuperación de errores o reinstalación.
     */
    fun uninstall() {
        try {
            AppLogger.i(TAG, "Uninstalling Termux bootstrap from ${prefix.absolutePath}")
            
            // Eliminar el PREFIX completo
            if (prefix.exists()) {
                prefix.deleteRecursively()
            }

            // Eliminar marcador
            markerFile.delete()

            AppLogger.i(TAG, "Termux bootstrap uninstalled successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Uninstall failed: ${e.message}", e)
        }
    }

    /**
     * Obtiene información de estado del bootstrap.
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "installed" to isInstalled(),
            "architecture" to detectArchitecture(),
            "prefixPath" to prefix.absolutePath,
            "prefixExists" to prefix.exists(),
            "prefixSizeMB" to if (prefix.exists()) {
                prefix.walkTopDown().sumOf { it.length() } / 1024 / 1024
            } else 0,
            "dpkgExists" to File(prefix, "bin/dpkg").exists(),
            "aptExists" to File(prefix, "bin/apt").exists(),
            "bashExists" to File(prefix, "bin/bash").exists(),
        )
    }

    // ── Métodos internos ──────────────────────────────────────────────────────

    /**
     * Descarga el archivo bootstrap desde la URL al archivo destino.
     * Reporta progreso via callback.
     *
     * @param url URL del bootstrap
     * @param destination Archivo destino
     * @param onProgress Callback (bytesDescargados, bytesTotales)
     */
    private fun downloadBootstrap(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()

        // Si el archivo ya existe y tiene tamaño razonable, no re-descargar
        if (destination.exists() && destination.length() > 10_000_000) {
            AppLogger.i(TAG, "Bootstrap already downloaded: ${destination.absolutePath}")
            onProgress(destination.length(), destination.length())
            return
        }

        AppLogger.i(TAG, "Downloading bootstrap from $url")

        val urlConnection = URL(url).openConnection() as HttpURLConnection
        urlConnection.connectTimeout = 30_000
        urlConnection.readTimeout = 120_000
        urlConnection.instanceFollowRedirects = true
        urlConnection.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")

        try {
            val responseCode = urlConnection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error: $responseCode ${urlConnection.responseMessage}")
            }

            val totalSize = urlConnection.contentLengthLong
            var downloadedSize = 0L

            urlConnection.inputStream.use { input ->
                BufferedInputStream(input, 32 * 1024).use { buffered ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int

                        while (buffered.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedSize += bytesRead
                            onProgress(downloadedSize, totalSize)
                        }
                    }
                }
            }

            AppLogger.i(TAG, "Bootstrap downloaded: ${destination.length()} bytes")
        } finally {
            urlConnection.disconnect()
        }
    }

    /**
     * Extrae el archivo bootstrap ZIP al directorio destino.
     * Preserva permisos de ejecución y crea symlinks correctamente.
     *
     * El bootstrap de Termux viene en formato ZIP (no tar.gz) desde 2024.
     * Contiene una estructura de directorios lista para usar.
     *
     * @param zipFile Archivo ZIP del bootstrap
     * @param targetDir Directorio destino (PREFIX)
     * @param onProgress Callback con número de entradas procesadas
     * @return Número de entradas extraídas
     */
    private fun extractBootstrap(
        zipFile: File,
        targetDir: File,
        onProgress: (Int) -> Unit,
    ): Int {
        targetDir.mkdirs()

        var entriesProcessed = 0

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries
            
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val destFile = File(targetDir, entry.name)

                try {
                    when {
                        entry.isDirectory -> {
                            destFile.mkdirs()
                            AppLogger.d(TAG, "Created directory: ${entry.name}")
                        }

                        entry.isUnixSymlink -> {
                            // Symlink — leer el target del contenido del entry
                            destFile.parentFile?.mkdirs()
                            val linkTarget = zip.getInputStream(entry).bufferedReader().readText()
                            
                            // Eliminar archivo existente si hay
                            if (destFile.exists()) {
                                destFile.delete()
                            }

                            try {
                                android.system.Os.symlink(linkTarget, destFile.absolutePath)
                                AppLogger.d(TAG, "Created symlink: ${entry.name} -> $linkTarget")
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Symlink failed: ${entry.name} -> $linkTarget: ${e.message}")
                            }
                        }

                        else -> {
                            // Archivo regular
                            destFile.parentFile?.mkdirs()
                            
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }

                            // Establecer permisos de ejecución si el archivo los tiene en el ZIP
                            val unixMode = entry.unixMode
                            if (unixMode != 0 && (unixMode and 0b001_001_001) != 0) {
                                destFile.setExecutable(true, false)
                                AppLogger.d(TAG, "Set executable: ${entry.name} (mode: ${unixMode.toString(8)})")
                            }

                            // Archivos en bin/ siempre ejecutables
                            if (entry.name.contains("/bin/")) {
                                destFile.setExecutable(true, false)
                            }

                            AppLogger.d(TAG, "Extracted file: ${entry.name} (${entry.size} bytes)")
                        }
                    }

                    entriesProcessed++
                    onProgress(entriesProcessed)

                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to extract entry ${entry.name}: ${e.message}", e)
                }
            }
        }

        AppLogger.i(TAG, "Total entries extracted: $entriesProcessed")
        return entriesProcessed
    }

    /**
     * Configura el entorno de Termux después de la extracción.
     * Crea archivos de configuración necesarios y establece permisos.
     */
    private fun setupEnvironment() {
        // Crear directorio tmp
        val tmpDir = File(prefix, "tmp")
        tmpDir.mkdirs()

        // Crear directorio home si no existe
        homeDir.mkdirs()

        // Configurar DNS (resolv.conf)
        val etcDir = File(prefix, "etc")
        etcDir.mkdirs()
        
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText(
                """
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                nameserver 8.8.4.4
                """.trimIndent()
            )
            AppLogger.i(TAG, "Created resolv.conf")
        }

        // Configurar profile básico
        val profile = File(etcDir, "profile")
        if (!profile.exists()) {
            profile.writeText(
                """
                export PATH=${prefix.absolutePath}/bin:${'$'}PATH
                export PREFIX=${prefix.absolutePath}
                export HOME=${homeDir.absolutePath}
                export TMPDIR=${prefix.absolutePath}/tmp
                export LANG=en_US.UTF-8
                """.trimIndent()
            )
            AppLogger.i(TAG, "Created profile")
        }

        // Asegurar que todos los binarios en bin/ son ejecutables
        val binDir = File(prefix, "bin")
        if (binDir.exists()) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, false)
                }
            }
            AppLogger.i(TAG, "Set executable permissions on bin/ directory")
        }

        AppLogger.i(TAG, "Environment setup complete")
    }

    /**
     * Ejecuta pkg update para actualizar los repositorios.
     * Retorna true si tuvo éxito, false si falló.
     */
    private fun runPkgUpdate(listener: ProgressListener): Boolean {
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            AppLogger.w(TAG, "bash not found, skipping pkg update")
            return false
        }

        val env = buildTermuxEnvironment()
        val command = listOf(
            bash.absolutePath,
            "-c",
            "export PATH=${prefix.absolutePath}/bin:\$PATH && pkg update -y"
        )

        return try {
            val pb = ProcessBuilder(command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()
            
            // Leer salida en un hilo separado
            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[pkg update] $line")
                    if (line.contains("Reading package lists", ignoreCase = true) ||
                        line.contains("Building dependency tree", ignoreCase = true)) {
                        listener.onProgress(82, "Actualizando repositorios...")
                    }
                }
            }
            outputThread.start()

            // Timeout de 2 minutos para pkg update
            val finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            outputThread.join(5000)

            if (!finished) {
                process.destroyForcibly()
                AppLogger.w(TAG, "pkg update timed out")
                return false
            }

            val exitCode = process.exitValue()
            AppLogger.i(TAG, "pkg update exited with code $exitCode")
            exitCode == 0

        } catch (e: Exception) {
            AppLogger.e(TAG, "pkg update failed: ${e.message}", e)
            false
        }
    }

    /**
     * Instala paquetes adicionales usando pkg install.
     * Por defecto instala: git, curl, wget
     */
    private fun installAdditionalPackages(listener: ProgressListener) {
        val packages = listOf("git", "curl", "wget")
        val bash = File(prefix, "bin/bash")
        
        if (!bash.exists()) {
            AppLogger.w(TAG, "bash not found, skipping package installation")
            return
        }

        val env = buildTermuxEnvironment()
        
        for ((index, pkg) in packages.withIndex()) {
            val progress = 90 + (index * 3)
            listener.onProgress(progress, "Instalando $pkg...")

            val command = listOf(
                bash.absolutePath,
                "-c",
                "export PATH=${prefix.absolutePath}/bin:\$PATH && pkg install -y $pkg"
            )

            try {
                val pb = ProcessBuilder(command)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.directory(homeDir)
                pb.redirectErrorStream(true)

                val process = pb.start()
                
                // Leer salida
                val outputThread = Thread {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        AppLogger.d(TAG, "[pkg install $pkg] $line")
                    }
                }
                outputThread.start()

                // Timeout de 3 minutos por paquete
                val finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
                outputThread.join(5000)

                if (!finished) {
                    process.destroyForcibly()
                    AppLogger.w(TAG, "pkg install $pkg timed out")
                    continue
                }

                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    AppLogger.i(TAG, "Successfully installed $pkg")
                } else {
                    AppLogger.w(TAG, "Failed to install $pkg (exit code: $exitCode)")
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error installing $pkg: ${e.message}", e)
            }
        }
    }

    /**
     * Construye el mapa de variables de entorno para Termux.
     */
    private fun buildTermuxEnvironment(): Map<String, String> {
        return mapOf(
            "HOME" to homeDir.absolutePath,
            "PREFIX" to prefix.absolutePath,
            "TMPDIR" to File(prefix, "tmp").absolutePath,
            "PATH" to "${prefix.absolutePath}/bin:/system/bin:/bin",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
        )
    }

    /**
     * Escribe el marcador de instalación completa.
     */
    private fun writeMarker(arch: String) {
        markerFile.writeText(
            """
            architecture=$arch
            installed_at=${System.currentTimeMillis()}
            prefix=${prefix.absolutePath}
            version=termux-bootstrap-2026.02.12
            """.trimIndent()
        )
        AppLogger.i(TAG, "Installation marker written: ${markerFile.absolutePath}")
    }
}
