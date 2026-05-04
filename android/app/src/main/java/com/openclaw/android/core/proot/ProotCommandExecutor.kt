package com.openclaw.android.core.proot

import android.content.Context
import com.openclaw.android.AppLogger
import java.io.File

/**
 * Ejecuta comandos dentro del rootfs Ubuntu via proot.
 *
 * Responsabilidad única: lanzar procesos con proot y manejar
 * su salida, errores y ciclo de vida.
 */
internal class ProotCommandExecutor(
    private val context: Context,
    private val pathResolver: ProotPathResolver,
    private val commandBuilder: ProotCommandBuilder,
) {

    private val TAG = ProotConstants.TAG

    /**
     * Ejecuta un comando dentro del rootfs Ubuntu via proot.
     * Streams la salida línea a línea.
     *
     * @param command Comando a ejecutar dentro del rootfs
     * @param env Variables de entorno adicionales (opcional)
     * @param onOutput Callback para cada línea de salida (stdout + stderr)
     * @return exit code del proceso, o -1 si falló
     */
    fun runInProot(
        command: String,
        env: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit,
    ): Int {
        val cmd = commandBuilder.buildProotCommand(command)
        AppLogger.i(TAG, "Running in proot: $command")

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(commandBuilder.buildProotEnv(context))
            pb.environment().putAll(env)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(false)

            val process = pb.start()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[proot] $line")
                    onOutput(line)
                }
            }.also { it.start() }

            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[proot-err] $line")
                    onOutput("[err] $line")
                }
            }.also { it.start() }

            stdoutThread.join()
            stderrThread.join()
            val exitCode = process.waitFor()
            AppLogger.i(TAG, "proot command exited with code $exitCode")
            exitCode
        } catch (e: Exception) {
            AppLogger.e(TAG, "runInProot failed: ${e.message}", e)
            onOutput("Error ejecutando proot: ${e.message}")
            -1
        }
    }

    /**
     * Lanza el gateway de OpenClaw dentro de proot como proceso persistente.
     * Retorna el Process para que OpenClawService pueda monitorearlo.
     */
    fun launchGatewayInProot(): Process? {
        val cmd = commandBuilder.buildGatewayCommand()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(commandBuilder.buildProotEnv(context))
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            AppLogger.i(TAG, "OpenClaw gateway launched in proot")
            process
        } catch (e: Exception) {
            AppLogger.e(TAG, "launchGatewayInProot failed: ${e.message}", e)
            null
        }
    }

    /**
     * Ejecuta apt dentro del rootfs para instalar paquetes.
     *
     * @param packages Lista de paquetes a instalar
     * @param onOutput Callback para salida
     * @return true si la instalación fue exitosa (exit code 0)
     */
    fun installPackages(
        packages: List<String>,
        onOutput: (String) -> Unit,
    ): Boolean {
        val command = "apt update && apt install -y ${packages.joinToString(" ")}"
        val exitCode = runInProot(command, onOutput = onOutput)
        return exitCode == 0
    }

    /**
     * Ejecuta un comando bash interactivo dentro del rootfs.
     * Útil para debugging o comandos manuales.
     */
    fun runInteractiveBash(
        initialCommand: String? = null,
        onOutput: (String) -> Unit,
    ): Int {
        val command = initialCommand ?: "/bin/bash"
        return runInProot(command, onOutput = onOutput)
    }

    /**
     * Verifica que proot pueda ejecutar comandos básicos.
     */
    fun testProotFunctionality(): Boolean {
        var success = false
        val exitCode = runInProot("echo 'proot test' && ls /", onOutput = { line ->
            if (line.contains("proot test")) {
                success = true
            }
        })
        return success && exitCode == 0
    }
}
