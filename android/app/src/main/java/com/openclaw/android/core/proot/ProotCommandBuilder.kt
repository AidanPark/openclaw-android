package com.openclaw.android.core.proot

import android.content.Context
import java.io.File

/**
 * Construye comandos proot y variables de entorno.
 *
 * Responsabilidad única: generar la línea de comandos y entorno
 * para ejecutar procesos dentro del rootfs Ubuntu via proot.
 */
internal class ProotCommandBuilder(
    private val pathResolver: ProotPathResolver,
) {

    /**
     * Construye el comando proot para ejecutar un comando dentro del rootfs Ubuntu.
     *
     * Equivalente a: proot --rootfs=<dir> -0 -w /root /bin/bash -c "<cmd>"
     *
     * Flags importantes:
     *   -0  : simula root (uid 0) — necesario para apt
     *   -w  : directorio de trabajo inicial
     *   --bind=/proc : monta /proc del host (necesario para Node.js)
     *   --bind=/dev  : monta /dev del host
     *   --kill-on-exit : mata todos los procesos al salir
     */
    fun buildProotCommand(
        command: String,
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        val paths = pathResolver.getPaths()
        val rootfs = paths.rootfsDir.absolutePath
        val proot = paths.prootBin.absolutePath

        return buildList {
            add(proot)
            add("--rootfs=$rootfs")
            add("-0")                          // simular root
            add("-w=/root")                    // working dir dentro del rootfs
            add("--kill-on-exit")
            add("--link2symlink")              // convierte hardlinks a symlinks (necesario en Android)
            add("--sysvipc")                   // IPC para Node.js
            // Binds esenciales
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom:/dev/random")  // Android no tiene /dev/random real
            // Bind del home de la app para intercambio de archivos
            add("--bind=${paths.homeDir.absolutePath}:/mnt/app-home")
            // Binds adicionales opcionales
            extraBinds.forEach { add("--bind=$it") }
            // Shell y comando
            add("/bin/sh")
            add("-c")
            add(command)
        }
    }

    /**
     * Variables de entorno para ejecutar proot.
     */
    fun buildProotEnv(context: Context): Map<String, String> {
        val paths = pathResolver.getPaths()
        return mapOf(
            "HOME" to paths.homeDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:/bin",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            // Necesario para que proot funcione en Android
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
        )
    }

    /**
     * Construye un comando para lanzar el gateway de OpenClaw dentro de proot.
     */
    fun buildGatewayCommand(): List<String> {
        val command = """
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export NODE_PATH=/usr/local/lib/node_modules
            export OA_GLIBC=0
            export CONTAINER=1
            cd /root
            exec node /usr/local/lib/node_modules/openclaw/openclaw.mjs gateway --host 0.0.0.0
        """.trimIndent()

        return buildProotCommand(command)
    }

    /**
     * Construye un comando para ejecutar apt dentro del rootfs.
     */
    fun buildAptCommand(subcommand: String, args: List<String> = emptyList()): List<String> {
        val aptArgs = args.joinToString(" ")
        val command = "apt $subcommand $aptArgs"
        return buildProotCommand(command)
    }

    /**
     * Construye un comando para ejecutar bash interactivo dentro del rootfs.
     */
    fun buildInteractiveBashCommand(): List<String> {
        return buildProotCommand("/bin/bash")
    }
}
