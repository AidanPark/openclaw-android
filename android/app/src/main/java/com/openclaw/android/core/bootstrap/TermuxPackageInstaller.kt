package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import com.openclaw.android.TermuxBootstrapManager
import java.io.File

/**
 * Instala paquetes adicionales de Termux (git, curl, wget).
 *
 * Responsabilidad única: ejecutar `pkg install` para cada paquete
 * con configuración no-interactiva.
 */
internal class TermuxPackageInstaller(
    private val prefix: File,
    private val homeDir: File,
    private val envConfigurator: TermuxEnvironmentConfigurator,
) {

    private val TAG = "TermuxPackageInstaller"

    /**
     * Instala paquetes adicionales de Termux.
     *
     * @param listener Para reportar progreso
     * @param packages Lista de paquetes a instalar (por defecto: git, curl, wget)
     */
    fun installAdditionalPackages(
        listener: TermuxBootstrapManager.ProgressListener,
        packages: List<String> = listOf("git", "curl", "wget"),
    ) {
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) return

        val env = envConfigurator.buildTermuxEnv() + mapOf("DEBIAN_FRONTEND" to "noninteractive")

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
}
