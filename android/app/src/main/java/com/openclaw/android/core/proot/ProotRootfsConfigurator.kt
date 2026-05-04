package com.openclaw.android.core.proot

import com.openclaw.android.AppLogger
import java.io.File

/**
 * Configura el rootfs Ubuntu después de la extracción.
 *
 * Responsabilidad única: crear directorios y archivos necesarios
 * para que proot funcione correctamente dentro del rootfs.
 */
internal class ProotRootfsConfigurator {

    private val TAG = ProotConstants.TAG

    /**
     * Crea directorios y archivos necesarios para que proot funcione correctamente.
     */
    fun setupRootfsDirs(rootfsDir: File) {
        // Directorios que proot necesita montar
        listOf("proc", "dev", "sys", "tmp", "root", "mnt/app-home").forEach { dir ->
            File(rootfsDir, dir).mkdirs()
        }

        // resolv.conf con DNS de Google (para que apt funcione dentro de proot)
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        // hosts básico
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        // nsswitch.conf mínimo
        val nsswitch = File(rootfsDir, "etc/nsswitch.conf")
        if (!nsswitch.exists()) {
            nsswitch.writeText("passwd: files\ngroup: files\nhosts: files dns\n")
        }

        // Directorio home del root
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()

        AppLogger.i(TAG, "Rootfs dirs setup complete")
    }

    /**
     * Verifica que el rootfs tenga la estructura mínima necesaria.
     */
    fun validateRootfsStructure(rootfsDir: File): Boolean {
        val requiredDirs = listOf("bin", "usr/bin", "etc", "lib", "proc", "dev", "sys")
        val requiredFiles = listOf("usr/bin/apt", "bin/bash", "usr/bin/bash")

        val dirsOk = requiredDirs.all { File(rootfsDir, it).exists() }
        val filesOk = requiredFiles.any { File(rootfsDir, it).exists() }

        return dirsOk && filesOk
    }
}
