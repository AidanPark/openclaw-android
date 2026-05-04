package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.core.bootstrap.TermuxBootstrapOrchestrator
import com.openclaw.android.core.proot.ProotCommandExecutor
import com.openclaw.android.core.proot.ProotPathResolver
import com.openclaw.android.core.proot.ProotRootfsDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * InstallationOrchestrator unificado — reemplaza SetupManager, RootfsManager, y duplicación de lógica.
 *
 * Modos de instalación:
 *   Auto: Bootstrap + Payload offline (si existe)
 *   BootstrapOnly: Solo Termux bootstrap
 *   OfflinePayload: Desde assets o URI
 *   Online: Solo bootstrap + luego terminal
 *   Proot: Ubuntu + proot
 *   Force: Reinstalar completamente
 */
sealed class InstallationMode {
    object Auto : InstallationMode()           // bootstrap + payload si existe
    object BootstrapOnly : InstallationMode()  // solo Termux bootstrap
    object OfflinePayload : InstallationMode() // desde assets o URI
    object Online : InstallationMode()         // solo bootstrap + luego terminal
    object Proot : InstallationMode()          // Ubuntu + proot
    object Force : InstallationMode()          // reinstalar
}

class UnifiedInstallationOrchestrator(
    private val context: Context,
    private val paths: InstallPathResolver,
    private val bootstrapOrchestrator: TermuxBootstrapOrchestrator,
    private val payloadInstaller: PayloadInstaller,
    private val prootPathResolver: ProotPathResolver,
    private val prootRootfsDownloader: ProotRootfsDownloader,
    private val prootCommandExecutor: ProotCommandExecutor,
    private val markerWriter: InstallMarkerWriter,
) {
    private val TAG = "UnifiedInstallationOrchestrator"

    /**
     * Punto de entrada principal para la instalación.
     */
    suspend fun install(
        mode: InstallationMode,
        listener: InstallerManager.ProgressListener
    ) = withContext(Dispatchers.IO) {
        try {
            when (mode) {
                is InstallationMode.Auto -> installAuto(listener)
                is InstallationMode.BootstrapOnly -> installBootstrap(listener)
                is InstallationMode.OfflinePayload -> installOffline(listener)
                is InstallationMode.Online -> installBootstrap(listener) // terminal después
                is InstallationMode.Proot -> installProot(listener)
                is InstallationMode.Force -> {
                    cleanInstallation()
                    installAuto(listener)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Installation failed: ${e.message}", e)
            listener.onError("Instalación falló: ${e.message ?: "error desconocido"}", e)
        }
    }

    /**
     * Modo automático: Bootstrap + Payload offline si existe.
     */
    private suspend fun installAuto(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Auto mode: installing bootstrap")
        installBootstrap(listener)
        
        if (payloadInstaller.hasPayloadAsset()) {
            AppLogger.i(TAG, "Auto mode: installing payload from assets")
            payloadInstaller.installOffline(listener)
        } else {
            listener.onProgress(100, "Bootstrap listo. Usa terminal para instalación online.")
        }
    }

    /**
     * Instala solo el bootstrap de Termux.
     */
    private suspend fun installBootstrap(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Installing Termux bootstrap")
        
        if (bootstrapOrchestrator.isInstalled()) {
            AppLogger.i(TAG, "Bootstrap already installed")
            listener.onSuccess()
            return
        }

        bootstrapOrchestrator.install(object : TermuxBootstrapOrchestrator.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                try {
                    markerWriter.writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                AppLogger.e(TAG, "Bootstrap install error: $message", cause)
                listener.onError(message, cause)
            }
        })
    }

    /**
     * Instala desde payload offline.
     */
    private suspend fun installOffline(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Installing from offline payload")
        
        // Primero asegurar que el bootstrap esté instalado
        if (!bootstrapOrchestrator.isInstalled()) {
            installBootstrap(listener)
        }
        
        payloadInstaller.installOffline(listener)
    }

    /**
     * Instala entorno Ubuntu completo via proot.
     */
    private suspend fun installProot(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Installing Proot + Ubuntu")
        
        // Verificar si proot ya está listo
        if (!prootPathResolver.isProotReady()) {
            listener.onProgress(1, "Descargando proot (binario nativo)...")
            val prootDownloaded = downloadProot(listener)
            if (!prootDownloaded) {
                listener.onError("No se pudo descargar proot. Verifica tu conexión a internet.")
                return
            }
        } else {
            listener.onProgress(8, "proot ya disponible")
        }

        // Verificar si rootfs ya está listo
        if (!prootPathResolver.isRootfsReady()) {
            listener.onProgress(9, "Descargando Ubuntu rootfs (~80MB)...")
            val rootfsDownloaded = downloadRootfs(listener)
            if (!rootfsDownloaded) {
                listener.onError("No se pudo descargar el rootfs Ubuntu. Verifica tu conexión.")
                return
            }
        } else {
            listener.onProgress(80, "Rootfs Ubuntu ya disponible")
        }

        // Configurar Ubuntu dentro de proot
        listener.onProgress(81, "Configurando Ubuntu (primera vez)...")
        val setupOk = runUbuntuSetup(listener)
        if (!setupOk) {
            listener.onError("Error configurando Ubuntu. Revisa los logs.")
            return
        }

        // Instalar Node.js dentro de proot
        listener.onProgress(83, "Instalando Node.js 22 en Ubuntu...")
        val nodeOk = installNodeInProot(listener)
        if (!nodeOk) {
            listener.onError("Error instalando Node.js. Verifica tu conexión.")
            return
        }

        // Instalar OpenClaw via npm
        listener.onProgress(88, "Instalando OpenClaw (npm install -g openclaw)...")
        val openclawOk = installOpenClawInProot(listener)
        if (!openclawOk) {
            listener.onError("Error instalando OpenClaw via npm.")
            return
        }

        // Crear scripts de lanzamiento
        listener.onProgress(96, "Creando scripts de lanzamiento...")
        createLaunchScripts()

        // Escribir marcadores
        writeProotMarkers()

        listener.onProgress(100, "¡Instalación completada!")
        listener.onSuccess()
    }

    /**
     * Descarga el binario proot.
     */
    private fun downloadProot(listener: InstallerManager.ProgressListener): Boolean {
        return try {
            // Usar el downloader existente de ProotManager
            val success = prootRootfsDownloader.downloadProot { percent, message ->
                listener.onProgress(percent, message)
            }
            success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download proot: ${e.message}", e)
            false
        }
    }

    /**
     * Descarga y extrae el rootfs Ubuntu.
     */
    private fun downloadRootfs(listener: InstallerManager.ProgressListener): Boolean {
        return try {
            val success = prootRootfsDownloader.downloadAndExtractRootfs { percent, message ->
                listener.onProgress(percent, message)
            }
            success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download rootfs: ${e.message}", e)
            false
        }
    }

    /**
     * Configuración inicial de Ubuntu dentro de proot.
     */
    private fun runUbuntuSetup(listener: InstallerManager.ProgressListener): Boolean {
        val setupScript = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            echo "[setup] Actualizando listas de paquetes..."
            apt-get update -qq 2>&1 || apt-get update 2>&1 || true

            echo "[setup] Instalando dependencias base..."
            apt-get install -y --no-install-recommends \
                ca-certificates \
                curl \
                wget \
                gnupg \
                lsb-release \
                2>&1 || true

            echo "[setup] Ubuntu configurado OK"
        """.trimIndent()

        var lastLine = ""
        val exitCode = prootCommandExecutor.runInProot(setupScript, emptyMap()) { line ->
            lastLine = line
            AppLogger.d(TAG, "[ubuntu-setup] $line")
            if (line.contains("[setup]")) {
                listener.onProgress(82, line.removePrefix("[setup] "))
            }
        }

        return exitCode == 0 || lastLine.contains("OK")
    }

    /**
     * Instala Node.js 22 LTS dentro del rootfs Ubuntu.
     */
    private fun installNodeInProot(listener: InstallerManager.ProgressListener): Boolean {
        val paths = prootPathResolver.getPaths()
        
        // Verificar si Node.js ya está instalado
        if (paths.rootfsDir.resolve("usr/local/bin/node").exists() ||
            paths.rootfsDir.resolve("usr/bin/node").exists()
        ) {
            AppLogger.i(TAG, "Node.js already installed in rootfs")
            listener.onProgress(87, "Node.js ya instalado")
            return true
        }

        // Estrategia 1: NodeSource (Node 22 LTS)
        val nodeSourceScript = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            echo "[node] Configurando repositorio NodeSource (Node.js 22 LTS)..."
            curl -fsSL https://deb.nodesource.com/setup_22.x | bash - 2>&1

            echo "[node] Instalando Node.js 22..."
            apt-get install -y nodejs 2>&1

            echo "[node] Verificando..."
            node --version 2>&1
            npm --version 2>&1
            echo "[node] Node.js instalado OK"
        """.trimIndent()

        var nodeInstalled = false
        val exitCode = prootCommandExecutor.runInProot(nodeSourceScript, emptyMap()) { line ->
            AppLogger.d(TAG, "[node-install] $line")
            if (line.contains("[node]")) {
                listener.onProgress(85, line.removePrefix("[node] "))
            }
            if (line.contains("v22.") || line.contains("v20.") || line.contains("OK")) {
                nodeInstalled = true
            }
        }

        if (exitCode == 0 || nodeInstalled) return true

        // Estrategia 2: Descargar binario oficial
        AppLogger.w(TAG, "NodeSource failed, trying direct binary download...")
        listener.onProgress(84, "Descargando Node.js binario oficial...")

        val directScript = """
            set -e
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            NODE_VERSION="v22.13.1"
            NODE_DIR="/usr/local"

            echo "[node] Descargando Node.js $NODE_VERSION para arm64..."
            curl -fsSL "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz" \
                -o /tmp/node.tar.gz 2>&1

            echo "[node] Extrayendo..."
            tar -xzf /tmp/node.tar.gz -C "$NODE_DIR" --strip-components=1 2>&1
            rm -f /tmp/node.tar.gz

            echo "[node] Verificando..."
            node --version 2>&1
            npm --version 2>&1
            echo "[node] Node.js instalado OK"
        """.trimIndent()

        var nodeOk2 = false
        val exitCode2 = prootCommandExecutor.runInProot(directScript, emptyMap()) { line ->
            AppLogger.d(TAG, "[node-direct] $line")
            if (line.contains("[node]")) {
                listener.onProgress(86, line.removePrefix("[node] "))
            }
            if (line.contains("v22.") || line.contains("OK")) nodeOk2 = true
        }

        return exitCode2 == 0 || nodeOk2
    }

    /**
     * Instala OpenClaw via npm dentro del rootfs Ubuntu.
     */
    private fun installOpenClawInProot(listener: InstallerManager.ProgressListener): Boolean {
        val installScript = """
            set -e
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export NODE_PATH=/usr/local/lib/node_modules
            export npm_config_cache=/tmp/npm-cache

            echo "[openclaw] Verificando npm..."
            npm --version 2>&1

            echo "[openclaw] Instalando OpenClaw (puede tardar 2-3 min)..."
            npm install -g openclaw@latest --ignore-scripts --no-fund --no-audit 2>&1

            echo "[openclaw] Verificando instalación..."
            if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ]; then
                echo "[openclaw] openclaw.mjs encontrado OK"
            elif [ -f /usr/local/bin/openclaw ]; then
                echo "[openclaw] openclaw binary encontrado OK"
            else
                echo "[openclaw] ERROR: openclaw no encontrado después de npm install"
                exit 1
            fi

            echo "[openclaw] Instalación completada"
        """.trimIndent()

        var openclawOk = false
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts && !openclawOk) {
            attempt++
            listener.onProgress(88 + attempt, "Instalando OpenClaw (intento $attempt/$maxAttempts)...")

            val exitCode = prootCommandExecutor.runInProot(installScript, emptyMap()) { line ->
                AppLogger.d(TAG, "[openclaw-install] $line")
                if (line.contains("[openclaw]")) {
                    listener.onProgress(89 + attempt, line.removePrefix("[openclaw] "))
                }
                if (line.contains("OK") || line.contains("completada")) {
                    openclawOk = true
                }
            }

            if (exitCode == 0 || openclawOk) {
                openclawOk = true
                break
            }

            if (attempt < maxAttempts) {
                AppLogger.w(TAG, "npm install attempt $attempt failed, retrying in 5s...")
                listener.onProgress(89, "Reintentando en 5 segundos...")
                Thread.sleep(5_000)
                // Limpiar caché npm antes de reintentar
                prootCommandExecutor.runInProot("rm -rf /tmp/npm-cache 2>/dev/null; true", emptyMap()) {}
            }
        }

        return openclawOk || isOpenClawInstalledInRootfs()
    }

    /**
     * Crea scripts de lanzamiento para proot.
     */
    private fun createLaunchScripts() {
        val homeDir = File(context.filesDir, "home")
        homeDir.mkdirs()

        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()

        val paths = prootPathResolver.getPaths()
        val prootBin = paths.prootBin.absolutePath
        val rootfsDir = paths.rootfsDir.absolutePath
        val appHomeDir = homeDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath

        // Script principal de lanzamiento del gateway
        val gatewayScript = File(homeDir, "openclaw-start.sh")
        gatewayScript.writeText(
            """
            #!/system/bin/sh
            # OpenClaw gateway launcher — proot edition
            # Generado por UnifiedInstallationOrchestrator

            PROOT="$prootBin"
            ROOTFS="$rootfsDir"
            APP_HOME="$appHomeDir"
            TMPDIR="$cacheDir"

            export PROOT_NO_SECCOMP=1
            export PROOT_TMP_DIR="$TMPDIR"
            export HOME="$APP_HOME"
            export TMPDIR="$TMPDIR"

            exec "$PROOT" \
                --rootfs="$ROOTFS" \
                -0 \
                -w=/root \
                --kill-on-exit \
                --link2symlink \
                --sysvipc \
                --bind=/proc \
                --bind=/dev \
                --bind=/sys \
                --bind=/dev/urandom:/dev/random \
                --bind="$APP_HOME":/mnt/app-home \
                /bin/sh -c '
                    export HOME=/root
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export NODE_PATH=/usr/local/lib/node_modules
                    export OA_GLIBC=0
                    export CONTAINER=1
                    export CLAWDHUB_WORKDIR=/root/.openclaw/workspace
                    mkdir -p /root/.openclaw/workspace
                    exec node /usr/local/lib/node_modules/openclaw/openclaw.mjs gateway --host 0.0.0.0
                '
            """.trimIndent(),
        )
        gatewayScript.setExecutable(true, false)

        // Script de shell interactivo
        val shellScript = File(homeDir, "openclaw-shell.sh")
        shellScript.writeText(
            """
            #!/system/bin/sh
            # Shell interactivo dentro del entorno Ubuntu/OpenClaw

            PROOT="$prootBin"
            ROOTFS="$rootfsDir"
            APP_HOME="$appHomeDir"
            TMPDIR="$cacheDir"

            export PROOT_NO_SECCOMP=1
            export PROOT_TMP_DIR="$TMPDIR"
            export HOME="$APP_HOME"

            exec "$PROOT" \
                --rootfs="$ROOTFS" \
                -0 \
                -w=/root \
                --kill-on-exit \
                --link2symlink \
                --sysvipc \
                --bind=/proc \
                --bind=/dev \
                --bind=/sys \
                --bind=/dev/urandom:/dev/random \
                --bind="$APP_HOME":/mnt/app-home \
                /bin/bash --login
            """.trimIndent(),
        )
        shellScript.setExecutable(true, false)

        AppLogger.i(TAG, "Launch scripts created: ${gatewayScript.absolutePath}")
    }

    /**
     * Escribe marcadores de instalación proot.
     */
    private fun writeProotMarkers() {
        // Marcador interno de la app
        val markerFile = File(context.filesDir, ".proot-installed")
        markerFile.writeText("proot-installed\n")

        // Marcador compatible con el sistema existente
        val ocaDir = File(context.filesDir, "home/.openclaw-android")
        ocaDir.mkdirs()
        val openclawMarker = File(ocaDir, "installed.json")
        openclawMarker.writeText(
            """
            {
              "installed": true,
              "source": "unified-installation-orchestrator",
              "version": "proot",
              "installedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent(),
        )

        // Marcador .installed para compatibilidad
        File(context.filesDir, ".installed").writeText("proot\n")

        AppLogger.i(TAG, "Proot installation markers written")
    }

    /**
     * Verifica si OpenClaw está instalado en el rootfs.
     */
    private fun isOpenClawInstalledInRootfs(): Boolean {
        val paths = prootPathResolver.getPaths()
        return paths.rootfsDir.resolve("usr/local/lib/node_modules/openclaw/openclaw.mjs").exists() ||
            paths.rootfsDir.resolve("usr/local/bin/openclaw").exists()
    }

    /**
     * Limpia toda la instalación para empezar de cero.
     */
    private fun cleanInstallation() {
        // Eliminar marcadores
        File(context.filesDir, ".proot-installed").delete()
        File(context.filesDir, "home/.openclaw-android/installed.json").delete()
        File(context.filesDir, ".installed").delete()
        
        // Eliminar directorios
        paths.prefix.deleteRecursively()
        paths.homeDir.deleteRecursively()
        
        // Eliminar marcadores escritos por markerWriter
        markerWriter.deleteMarkers()
        
        AppLogger.i(TAG, "Installation cleanup complete")
    }

    /**
     * Obtiene el estado de la instalación.
     */
    fun getStatus(): Map<String, Any> {
        return try {
            val paths = prootPathResolver.getPaths()
            mapOf(
                "installed" to File(context.filesDir, ".proot-installed").exists(),
                "prootReady" to prootPathResolver.isProotReady(),
                "rootfsReady" to prootPathResolver.isRootfsReady(),
                "openclawReady" to isOpenClawInstalledInRootfs(),
                "prootPath" to paths.prootBin.absolutePath,
                "rootfsPath" to paths.rootfsDir.absolutePath,
                "rootfsSizeMB" to (paths.rootfsDir.walkTopDown().sumOf { it.length() } / 1024 / 1024),
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get status: ${e.message}", e)
            emptyMap()
        }
    }
}