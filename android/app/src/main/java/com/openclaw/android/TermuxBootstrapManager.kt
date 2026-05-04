package com.openclaw.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * TermuxBootstrapManager — descarga e instala el bootstrap oficial de Termux.
 *
 * Flujo de instalación online (cuando el payload offline no está disponible):
 *   1. Detectar arquitectura del dispositivo
 *   2. Descargar bootstrap ZIP desde packages.termux.dev
 *   3. Extraer al PREFIX preservando permisos y symlinks
 *   4. Configurar entorno (DNS, DEBIAN_FRONTEND, etc.)
 *   5. Ejecutar dpkg --configure -a (no-interactivo) para reparar estado
 *   6. Ejecutar pkg update con reintentos automáticos
 *   7. Instalar paquetes adicionales (git, curl, wget)
 *
 * Manejo del prompt interactivo de dpkg:
 *   dpkg a veces pregunta sobre sources.list al configurar apt.
 *   Solución: DEBIAN_FRONTEND=noninteractive + respuesta "N" via stdin.
 *   Esto preserva el sources.list existente (comportamiento correcto).
 */
class TermuxBootstrapManager(private val context: Context) {

    private val TAG = "TermuxBootstrapManager"

    private val filesDir: File = context.filesDir
    private val cacheDir: File = context.cacheDir
    private val homeDir = File(filesDir, "home")

    // PREFIX donde se instala el bootstrap — se resuelve dinámicamente
    private val prefix: File
        get() {
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

    // URLs del bootstrap oficial por arquitectura
    private val BOOTSTRAP_URLS = mapOf(
        "aarch64" to "https://packages.termux.dev/bootstrap/bootstrap-aarch64.zip",
        "arm"     to "https://packages.termux.dev/bootstrap/bootstrap-arm.zip",
        "x86_64"  to "https://packages.termux.dev/bootstrap/bootstrap-x86_64.zip",
        "i686"    to "https://packages.termux.dev/bootstrap/bootstrap-i686.zip",
    )

    // ── Interfaz de progreso ──────────────────────────────────────────────────

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── API pública ───────────────────────────────────────────────────────────

    fun isInstalled(): Boolean {
        if (!markerFile.exists()) return false
        return File(prefix, "bin/dpkg").exists() &&
               File(prefix, "bin/apt").exists() &&
               File(prefix, "bin/bash").exists()
    }

    fun detectArchitecture(): String {
        val abis = Build.SUPPORTED_ABIS
        AppLogger.i(TAG, "Device ABIs: ${abis.joinToString(", ")}")
        return when {
            abis.any { it.startsWith("arm64") || it == "aarch64" } -> "aarch64"
            abis.any { it.startsWith("armeabi") }                  -> "arm"
            abis.any { it == "x86_64" }                            -> "x86_64"
            abis.any { it == "x86" }                               -> "i686"
            else -> { AppLogger.w(TAG, "Unknown ABI, defaulting to aarch64"); "aarch64" }
        }
    }

    /**
     * Instala el bootstrap de Termux.
     * Debe ejecutarse en Dispatchers.IO.
     */
    suspend fun install(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            if (isInstalled()) {
                AppLogger.i(TAG, "Already installed")
                listener.onSuccess()
                return@withContext
            }

            // Paso 1: Arquitectura
            listener.onProgress(1, "Detectando arquitectura...")
            val arch = detectArchitecture()
            val bootstrapUrl = BOOTSTRAP_URLS[arch]
                ?: throw IllegalStateException("Arquitectura no soportada: $arch")
            listener.onProgress(2, "Arquitectura: $arch")

            // Paso 2: Descargar
            val bootstrapZip = File(cacheDir, "termux-bootstrap-$arch.zip")
            downloadBootstrap(bootstrapUrl, bootstrapZip) { downloaded, total ->
                val pct = if (total > 0) 2 + (downloaded * 48 / total).toInt().coerceIn(0, 48) else 25
                listener.onProgress(pct, "Descargando... ${downloaded / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB" else ""}")
            }

            // Paso 3: Extraer
            listener.onProgress(50, "Extrayendo bootstrap...")
            val count = extractBootstrap(bootstrapZip, prefix) { n ->
                if (n % 200 == 0) listener.onProgress(50 + (n / 100).coerceAtMost(20), "Extrayendo... $n archivos")
            }
            listener.onProgress(70, "Extraídos $count archivos")

            // Paso 4: Configurar entorno base
            listener.onProgress(71, "Configurando entorno...")
            setupEnvironment()

            // Paso 5: dpkg --configure -a (no-interactivo)
            // Esto resuelve el estado "half-configured" de apt/dpkg después de la extracción.
            // dpkg puede preguntar sobre sources.list — respondemos "N" (mantener el actual).
            listener.onProgress(75, "Configurando dpkg (no-interactivo)...")
            runDpkgConfigure(listener)

            // Paso 6: pkg update con reintentos
            listener.onProgress(80, "Actualizando repositorios...")
            val updateOk = runPkgUpdateWithRetry(listener, maxAttempts = 3)
            if (!updateOk) {
                AppLogger.w(TAG, "pkg update failed after retries — continuing anyway")
                listener.onProgress(88, "Advertencia: pkg update falló, continuando...")
            }

            // Paso 7: Paquetes adicionales
            listener.onProgress(90, "Instalando paquetes base...")
            installAdditionalPackages(listener)

            // Paso 8: Marcador y verificación
            writeMarker(arch)
            listener.onProgress(98, "Verificando instalación...")
            if (!isInstalled()) throw IllegalStateException("Verificación post-instalación falló")

            // Limpiar ZIP descargado
            bootstrapZip.delete()

            listener.onProgress(100, "¡Instalación completada!")
            listener.onSuccess()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Bootstrap installation failed: ${e.message}", e)
            listener.onError("Error instalando bootstrap: ${e.message}", e)
        }
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "installed"    to isInstalled(),
        "architecture" to detectArchitecture(),
        "prefixPath"   to prefix.absolutePath,
        "prefixExists" to prefix.exists(),
        "prefixSizeMB" to if (prefix.exists()) prefix.walkTopDown().sumOf { it.length() } / 1024 / 1024 else 0L,
        "dpkgExists"   to File(prefix, "bin/dpkg").exists(),
        "aptExists"    to File(prefix, "bin/apt").exists(),
        "bashExists"   to File(prefix, "bin/bash").exists(),
    )

    fun uninstall() {
        try {
            if (prefix.exists()) prefix.deleteRecursively()
            markerFile.delete()
            AppLogger.i(TAG, "Bootstrap uninstalled")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Uninstall failed: ${e.message}", e)
        }
    }

    // ── Descarga ──────────────────────────────────────────────────────────────

    private fun downloadBootstrap(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()

        // Reutilizar si ya está descargado y tiene tamaño razonable
        if (dest.exists() && dest.length() > 5_000_000) {
            AppLogger.i(TAG, "Bootstrap already downloaded: ${dest.length()} bytes")
            onProgress(dest.length(), dest.length())
            return
        }

        AppLogger.i(TAG, "Downloading bootstrap from $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK)
                throw IllegalStateException("HTTP ${conn.responseCode}: ${conn.responseMessage}")

            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                BufferedInputStream(input, 32 * 1024).use { buf ->
                    FileOutputStream(dest).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        var n: Int
                        while (buf.read(buffer).also { n = it } != -1) {
                            out.write(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            AppLogger.i(TAG, "Downloaded: ${dest.length()} bytes")
        } finally {
            conn.disconnect()
        }
    }

    // ── Extracción ────────────────────────────────────────────────────────────

    /**
     * Extrae el ZIP del bootstrap al PREFIX.
     * Preserva permisos Unix y crea symlinks correctamente.
     *
     * El bootstrap de Termux usa formato ZIP con:
     *   - Archivos regulares con Unix mode en el campo extra
     *   - Symlinks marcados con isUnixSymlink = true
     *   - Un archivo SYMLINKS.txt que lista symlinks adicionales
     */
    private fun extractBootstrap(zipFile: File, targetDir: File, onProgress: (Int) -> Unit): Int {
        targetDir.mkdirs()
        var count = 0

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val dest = File(targetDir, entry.name)

                try {
                    when {
                        entry.isDirectory -> dest.mkdirs()

                        entry.isUnixSymlink -> {
                            // El contenido del entry ES el target del symlink
                            dest.parentFile?.mkdirs()
                            val target = zip.getInputStream(entry).bufferedReader().readText().trim()
                            dest.delete()
                            try {
                                android.system.Os.symlink(target, dest.absolutePath)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Symlink failed: ${entry.name} -> $target: ${e.message}")
                            }
                        }

                        // Archivo especial: SYMLINKS.txt — procesar symlinks adicionales
                        entry.name == "SYMLINKS.txt" -> {
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { out -> input.copyTo(out) }
                            }
                            // Procesar el archivo de symlinks
                            processSymlinksFile(dest, targetDir)
                        }

                        else -> {
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { out -> input.copyTo(out) }
                            }
                            // Aplicar permisos Unix del ZIP
                            val mode = entry.unixMode
                            if (mode != 0 && (mode and 0b001_001_001) != 0) {
                                dest.setExecutable(true, false)
                            }
                            // bin/ siempre ejecutable
                            if (entry.name.contains("/bin/") || entry.name.startsWith("bin/")) {
                                dest.setExecutable(true, false)
                            }
                        }
                    }
                    count++
                    onProgress(count)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Extract failed: ${entry.name}: ${e.message}")
                }
            }
        }

        AppLogger.i(TAG, "Extracted $count entries to ${targetDir.absolutePath}")
        return count
    }

    /**
     * Procesa SYMLINKS.txt del bootstrap de Termux.
     * Formato: "target←←←linkpath" (separador ←←←)
     * Ejemplo: "bash←←←bin/sh"
     */
    private fun processSymlinksFile(symlinksFile: File, targetDir: File) {
        if (!symlinksFile.exists()) return
        try {
            symlinksFile.readLines().forEach { line ->
                val parts = line.split("←←←")
                if (parts.size == 2) {
                    val target   = parts[0].trim()
                    val linkPath = parts[1].trim()
                    val linkFile = File(targetDir, linkPath)
                    linkFile.parentFile?.mkdirs()
                    linkFile.delete()
                    try {
                        android.system.Os.symlink(target, linkFile.absolutePath)
                        AppLogger.d(TAG, "Symlink from SYMLINKS.txt: $linkPath -> $target")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "SYMLINKS.txt symlink failed: $linkPath -> $target: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to process SYMLINKS.txt: ${e.message}")
        }
    }

    // ── Configuración del entorno ─────────────────────────────────────────────

    private fun setupEnvironment() {
        File(prefix, "tmp").mkdirs()
        homeDir.mkdirs()

        val etcDir = File(prefix, "etc")
        etcDir.mkdirs()

        // resolv.conf — DNS
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            AppLogger.i(TAG, "Created resolv.conf")
        }

        // Permisos en bin/
        File(prefix, "bin").listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }

        AppLogger.i(TAG, "Environment setup complete at ${prefix.absolutePath}")
    }

    // ── dpkg --configure -a (no-interactivo) ─────────────────────────────────

    /**
     * Ejecuta `dpkg --configure -a` de forma completamente no-interactiva.
     *
     * Problema: al configurar apt por primera vez, dpkg puede preguntar:
     *   "What would you like to do about sources.list? [Y/I/N/O/D/Z]"
     *
     * Solución:
     *   1. DEBIAN_FRONTEND=noninteractive suprime la mayoría de prompts
     *   2. Pasamos "N\n" por stdin como respuesta por defecto (mantener versión actual)
     *   3. Usamos `yes N |` como fallback adicional
     *
     * "N" = mantener el sources.list actual (correcto para Termux).
     */
    private fun runDpkgConfigure(listener: ProgressListener) {
        val dpkg = File(prefix, "bin/dpkg")
        if (!dpkg.exists()) {
            AppLogger.w(TAG, "dpkg not found, skipping configure")
            return
        }

        val env = buildTermuxEnv()
        // DEBIAN_FRONTEND=noninteractive es la clave para evitar prompts
        val fullEnv = env + mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN" to "true",
            "DPKG_FRONTEND_LOCKED" to "1",
        )

        AppLogger.i(TAG, "Running dpkg --configure -a (non-interactive)")

        try {
            val pb = ProcessBuilder(dpkg.absolutePath, "--configure", "-a", "--force-confold")
            pb.environment().clear()
            pb.environment().putAll(fullEnv)
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()

            // Responder "N" a cualquier prompt interactivo que dpkg envíe
            // "--force-confold" debería evitarlo, pero por si acaso:
            Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        // Esperar un poco y enviar "N" repetidamente como seguro
                        Thread.sleep(500)
                        repeat(10) {
                            writer.write("N\n")
                            writer.flush()
                            Thread.sleep(200)
                        }
                    }
                } catch (_: Exception) {}
            }.start()

            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[dpkg-configure] $line")
                    // Si dpkg pregunta sobre sources.list, loguear para diagnóstico
                    if (line.contains("sources.list", ignoreCase = true) ||
                        line.contains("What would you like", ignoreCase = true)) {
                        AppLogger.w(TAG, "dpkg interactive prompt detected: $line")
                        listener.onProgress(76, "Configurando dpkg (respondiendo N)...")
                    }
                }
            }
            outputThread.start()

            val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            outputThread.join(3000)

            if (!finished) {
                process.destroyForcibly()
                AppLogger.w(TAG, "dpkg --configure -a timed out (non-fatal)")
            } else {
                AppLogger.i(TAG, "dpkg --configure -a exited with code ${process.exitValue()}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "dpkg --configure -a failed (non-fatal): ${e.message}")
        }
    }

    // ── pkg update con reintentos ─────────────────────────────────────────────

    /**
     * Ejecuta `pkg update` con hasta [maxAttempts] reintentos.
     *
     * Si falla, ejecuta `dpkg --configure -a` antes de reintentar.
     * Esto resuelve el caso donde dpkg quedó en estado inconsistente.
     *
     * Variables críticas:
     *   DEBIAN_FRONTEND=noninteractive — evita prompts interactivos
     *   --force-confold                — mantener configuración actual sin preguntar
     */
    private fun runPkgUpdateWithRetry(listener: ProgressListener, maxAttempts: Int): Boolean {
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            AppLogger.w(TAG, "bash not found, skipping pkg update")
            return false
        }

        repeat(maxAttempts) { attempt ->
            val attemptNum = attempt + 1
            listener.onProgress(80 + attempt * 2, "pkg update (intento $attemptNum/$maxAttempts)...")
            AppLogger.i(TAG, "pkg update attempt $attemptNum/$maxAttempts")

            val success = runSinglePkgUpdate(bash)
            if (success) {
                AppLogger.i(TAG, "pkg update succeeded on attempt $attemptNum")
                return true
            }

            // Falló — ejecutar dpkg --configure -a antes de reintentar
            if (attemptNum < maxAttempts) {
                AppLogger.w(TAG, "pkg update failed, running dpkg --configure -a before retry...")
                listener.onProgress(81 + attempt * 2, "Reparando dpkg antes de reintentar...")
                runDpkgConfigure(listener)
                Thread.sleep(1000)
            }
        }

        return false
    }

    /**
     * Ejecuta un único intento de `pkg update`.
     *
     * Usa DEBIAN_FRONTEND=noninteractive y --force-confold para evitar
     * cualquier prompt interactivo, incluyendo el de sources.list.
     */
    private fun runSinglePkgUpdate(bash: File): Boolean {
        val env = buildTermuxEnv() + mapOf(
            "DEBIAN_FRONTEND"              to "noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN"  to "true",
        )

        // El script pasa "N" a stdin como respuesta por defecto a cualquier prompt
        // y usa --force-confold para que dpkg no pregunte sobre archivos de config
        val script = """
            export DEBIAN_FRONTEND=noninteractive
            export DEBCONF_NONINTERACTIVE_SEEN=true
            export PATH="${prefix.absolutePath}/bin:${'$'}PATH"
            export PREFIX="${prefix.absolutePath}"
            export HOME="${homeDir.absolutePath}"
            export TMPDIR="${prefix.absolutePath}/tmp"

            # Responder N a cualquier prompt de dpkg sobre archivos de configuración
            yes N | pkg update -y -o Dpkg::Options::="--force-confold" 2>&1
        """.trimIndent()

        return try {
            val pb = ProcessBuilder(bash.absolutePath, "-c", script)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()

            val output = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[pkg update] $line")
                    output.appendLine(line)
                }
            }
            outputThread.start()

            val finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            outputThread.join(5000)

            if (!finished) {
                process.destroyForcibly()
                AppLogger.w(TAG, "pkg update timed out")
                return false
            }

            val exitCode = process.exitValue()
            AppLogger.i(TAG, "pkg update exit code: $exitCode")

            // Considerar éxito si el exit code es 0 o si la salida indica éxito
            exitCode == 0 || output.contains("Reading package lists") || output.contains("All packages are up to date")
        } catch (e: Exception) {
            AppLogger.e(TAG, "pkg update exception: ${e.message}", e)
            false
        }
    }

    // ── Instalación de paquetes adicionales ───────────────────────────────────

    private fun installAdditionalPackages(listener: ProgressListener) {
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) return

        val packages = listOf("git", "curl", "wget")
        val env = buildTermuxEnv() + mapOf("DEBIAN_FRONTEND" to "noninteractive")

        packages.forEachIndexed { i, pkg ->
            listener.onProgress(90 + i * 3, "Instalando $pkg...")

            val script = """
                export DEBIAN_FRONTEND=noninteractive
                export PATH="${prefix.absolutePath}/bin:${'$'}PATH"
                export PREFIX="${prefix.absolutePath}"
                export HOME="${homeDir.absolutePath}"
                export TMPDIR="${prefix.absolutePath}/tmp"
                yes N | pkg install -y -o Dpkg::Options::="--force-confold" $pkg 2>&1
            """.trimIndent()

            try {
                val pb = ProcessBuilder(bash.absolutePath, "-c", script)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.directory(homeDir)
                pb.redirectErrorStream(true)

                val process = pb.start()
                val outputThread = Thread {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        AppLogger.d(TAG, "[pkg install $pkg] $line")
                    }
                }
                outputThread.start()

                val finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
                outputThread.join(5000)

                if (!finished) {
                    process.destroyForcibly()
                    AppLogger.w(TAG, "pkg install $pkg timed out")
                } else {
                    val code = process.exitValue()
                    AppLogger.i(TAG, "pkg install $pkg exit code: $code")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "pkg install $pkg failed: ${e.message}", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Variables de entorno base para ejecutar comandos de Termux.
     * DEBIAN_FRONTEND=noninteractive se agrega en cada llamada que lo necesite.
     */
    private fun buildTermuxEnv(): Map<String, String> = mapOf(
        "HOME"         to homeDir.absolutePath,
        "PREFIX"       to prefix.absolutePath,
        "TMPDIR"       to File(prefix, "tmp").absolutePath,
        "PATH"         to "${prefix.absolutePath}/bin:/system/bin:/bin",
        "LANG"         to "en_US.UTF-8",
        "TERM"         to "xterm-256color",
        "ANDROID_DATA" to "/data",
        "ANDROID_ROOT" to "/system",
    )

    private fun writeMarker(arch: String) {
        markerFile.writeText(
            "architecture=$arch\n" +
            "installed_at=${System.currentTimeMillis()}\n" +
            "prefix=${prefix.absolutePath}\n" +
            "version=termux-bootstrap-2026.02.12\n"
        )
        AppLogger.i(TAG, "Marker written: ${markerFile.absolutePath}")
    }
}
