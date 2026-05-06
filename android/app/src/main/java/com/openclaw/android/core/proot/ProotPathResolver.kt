package com.openclaw.android.core.proot

import android.content.Context
import java.io.File

/**
 * Resuelve las rutas del filesystem para proot y rootfs.
 *
 * Responsabilidad única: proporcionar rutas consistentes a los archivos
 * y directorios usados por proot en todo el sistema.
 */
internal class ProotPathResolver(private val context: Context) {

    private val filesDir: File = context.filesDir

    data class ProotPaths(
        val prootBin: File,
        val rootfsDir: File,
        val homeDir: File,
        val cacheDir: File,
    )

    /**
     * Obtiene todas las rutas relevantes para proot.
     */
    fun getPaths(): ProotPaths {
        return ProotPaths(
            prootBin = File(filesDir, ProotConstants.PROOT_BIN_PATH),
            rootfsDir = File(filesDir, ProotConstants.ROOTFS_DIR_PATH),
            homeDir = File(filesDir, ProotConstants.HOME_DIR_PATH),
            cacheDir = context.cacheDir,
        )
    }

    /**
     * Verifica si el binario proot está presente y es ejecutable.
     */
    fun isProotReady(): Boolean {
        val paths = getPaths()
        return paths.prootBin.exists() && paths.prootBin.canExecute()
    }

    /**
     * Verifica si el rootfs Ubuntu tiene estructura básica.
     */
    fun isRootfsReady(): Boolean {
        val paths = getPaths()
        // Verificar que el rootfs tiene estructura básica de Ubuntu
        return paths.rootfsDir.resolve("usr/bin/apt").exists() ||
            paths.rootfsDir.resolve("bin/bash").exists() ||
            paths.rootfsDir.resolve("usr/bin/bash").exists()
    }

    /**
     * Obtiene el archivo .deb de proot en cache.
     */
    fun getProotDebCacheFile(): File {
        return File(context.cacheDir, ProotConstants.PROOT_DEB_FILENAME)
    }

    /**
     * Obtiene el archivo data.tar.xz extraído del .deb en cache.
     */
    fun getProotDataTarCacheFile(): File {
        return File(context.cacheDir, ProotConstants.PROOT_DATA_TAR_FILENAME)
    }

    /**
     * Obtiene el archivo tar.xz del rootfs en cache.
     */
    fun getRootfsTarCacheFile(): File {
        return File(context.cacheDir, ProotConstants.UBUNTU_ROOTFS_TAR_FILENAME)
    }
}
