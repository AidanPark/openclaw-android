package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * InstallerManager — single entry point for all installation flows.
 *
 * Architecture:
 *   ┌──────────────────────────────────────────────┐
 *   │            InstallerManager                  │
 *   │  ┌───────────────┐  ┌────────────────────┐   │
 *   │  │PayloadExtractor│  │InstallValidator   │   │
 *   │  │(streaming I/O) │  │(post-install check)│   │
 *   │  └───────────────┘  └────────────────────┘   │
 *   │  ┌────────────────┐                          │
 *   │  │BootstrapManager│ ← online fallback        │
 *   │  └────────────────┘                          │
 *   └───────────────── ↑progress callbacks ────────┘
 *                      │
 *              MainActivity (UI layer)
 *
 * Design principles:
 *   1. NO shell writes — all progress via [ProgressListener] callback.
 *   2. NO temp files for split reconstruction — streaming via SequenceInputStream.
 *   3. Marker (.installed) written ONLY after validation passes.
 *   4. Each extraction step has its own try-catch — partial failures are reported,
 *      not silently swallowed.
 *   5. Runs entirely on Dispatchers.IO — never blocks the main thread.
 *   6. The Activity shows progress in a dedicated UI overlay, not in a terminal.
 */
class InstallerManager(private val context: Context) {

    private val TAG = "InstallerManager"

    // ── Filesystem layout ──────────────────────────────────────────────────
    private val filesDir: File = context.filesDir
    private val homeDir = File(filesDir, "home")
    private val cacheDir: File = context.cacheDir

    // Dynamic prefix resolution to match EnvironmentBuilder logic
    private val prefix: File get() {
        val payloadDir = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload")
        ).find { it.isDirectory }
        return payloadDir ?: File(filesDir, "usr")
    }

    private val markerInstalled = File(filesDir, ".installed")

    private val PAYLOAD_ASSET_DIR = "payload"

    // ── Progress reporting ─────────────────────────────────────────────────

    /**
     * Listener for installation progress.
     * Called from Dispatchers.IO — the UI layer must post to main thread.
     *
     * This is deliberately simple (no Flow/LiveData) because:
     *   - It's consumed by exactly one observer (MainActivity)
     *   - Progress is linear (0→100%), no fan-out needed
     *   - Callbacks are the simplest contract to test and reason about
     */
    interface ProgressListener {
        /** Installation step changed. percent is 0–100. */
        fun onProgress(percent: Int, message: String)

        /** Installation completed successfully. */
        fun onSuccess()

        /** Installation failed. [message] is user-facing. [cause] is for logging. */
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** True if the environment is structurally installed (marker + dirs). */
    fun isInstalled(): Boolean {
        // Verificar instalación proot (nuevo flujo)
        val prootInstalled = File(filesDir, ".proot-installed").exists() &&
            SetupManager(context).isInstalled()
        if (prootInstalled) return true

        // Verificar instalación online (curl -sL myopenclawhub.com/install | bash)
        // El script online instala en homeDir/.openclaw-android/ y prefix/lib/node_modules/
        if (isOnlineInstallPresent()) return true

        // Verificar instalación legada (payload)
        return prefix.isDirectory && markerInstalled.exists()
    }

    /** True if glibc and node are both present (fully functional). */
    fun isReady(): Boolean {
        // proot: verificar que openclaw está en el rootfs
        if (File(filesDir, ".proot-installed").exists()) {
            return SetupManager(context).isOpenClawInstalledInRootfs()
        }
        // Online install: verificar node + openclaw en rutas del script online
        if (isOnlineInstallPresent()) return true

        return isInstalled() && InstallValidator.isStructurallyComplete(prefix)
    }

    /**
     * Detecta si la instalación online (curl -sL myopenclawhub.com/install | bash)
     * está presente y funcional.
     *
     * El script online instala en:
     *   homeDir/.openclaw-android/node/bin/node.real  ← Node.js
     *   homeDir/.openclaw-android/installed.json      ← marcador
     *   prefix/lib/node_modules/openclaw/openclaw.mjs ← OpenClaw
     *   prefix/glibc/lib/ld-linux-aarch64.so.1        ← glibc linker
     */
    private fun isOnlineInstallPresent(): Boolean {
        val ocaDir = File(homeDir, ".openclaw-android")
        val nodeReal = File(ocaDir, "node/bin/node.real")
        val installedJson = File(ocaDir, "installed.json")
        val ocMjs = File(prefix, "lib/node_modules/openclaw/openclaw.mjs")
        val ldso = File(prefix, "glibc/lib/ld-linux-aarch64.so.1")

        val hasNode = nodeReal.exists() && nodeReal.length() > 1_000_000
        val hasMarker = installedJson.exists()
        val hasOpenClaw = ocMjs.exists()
        val hasGlibc = ldso.exists() && ldso.length() > 100_000

        if (hasNode && hasMarker && hasOpenClaw && hasGlibc) {
            AppLogger.i(TAG, "Online install detected: node=${nodeReal.absolutePath}")
            return true
        }
        return false
    }

    /** True if APK bundles a payload asset. */
    fun hasPayloadAsset(): Boolean {
        val names = listOf(
            "payload.tar.gz",           // ← tu archivo actual
            "payload-final.tar.gz",
            "openclaw-payload.tar.gz",
            "payload/openclaw-payload.tar.gz",
            "payload/payload.tar.gz",
        )
        for (name in names) {
            try {
                context.assets.open(name).use {
                    AppLogger.i(TAG, "Payload asset found: $name")
                    return true
                }
            } catch (_: Exception) {
            }
        }
        // Diagnóstico: listar assets disponibles en la raíz
        try {
            val rootAssets = context.assets.list("")?.joinToString(", ") ?: "(vacío)"
            AppLogger.w(TAG, "No payload asset found. Root assets: [$rootAssets]")
        } catch (e: Exception) {
            AppLogger.w(TAG, "No payload asset found. Could not list assets: ${e.message}")
        }
        return false
    }

    private fun getPayloadAssetPath(): String? {
        val names = listOf(
            "payload.tar.gz",           // ← tu archivo actual
            "payload-final.tar.gz",
            "openclaw-payload.tar.gz",
            "payload/openclaw-payload.tar.gz",
            "payload/payload.tar.gz",
        )
        for (name in names) {
            try {
                context.assets.open(name).use { return name }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Main entry point: decide offline vs online, execute, validate.
     *
     * Flujo correcto de instalación:
     *
     *   PASO 1 — Termux Bootstrap (siempre primero si no está instalado)
     *     Instala: dpkg, apt, bash, pkg y herramientas base de Termux.
     *     Sin esto, la instalación online (curl | bash) no puede funcionar
     *     porque no hay bash, curl ni apt disponibles.
     *
     *   PASO 2 — Payload offline (payload.tar.gz en assets)
     *     Contiene: OpenClaw pre-empaquetado (NO es Termux).
     *     Se extrae ENCIMA del bootstrap ya instalado.
     *     Solo existe en el APK local — NO se sube a Git.
     *     Si no existe en assets, se salta este paso.
     *
     *   PASO 3 — Instalación online (curl -sL myopenclawhub.com/install | bash)
     *     Se ejecuta dentro del terminal embebido DESPUÉS del bootstrap.
     *     Instala/actualiza OpenClaw desde internet.
     *     Si dpkg falla → dpkg --configure -a (responde N) → reintento.
     *
     * Modos:
     *   "auto"             → Paso 1 + Paso 2 (si hay payload) — sin internet
     *   "termux-bootstrap" → Solo Paso 1
     *   "offline"          → Paso 1 + Paso 2 (requiere payload en assets)
     *   "online"           → Paso 1 (si no hay bootstrap) + Paso 3 en terminal
     *   "proot"            → Ubuntu completo via proot (avanzado, sin bootstrap)
     */
    suspend fun install(mode: String, customUri: android.net.Uri?, listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            // ── Ya instalado ───────────────────────────────────────────────
            if (isInstalled() && mode != "force") {
                AppLogger.i(TAG, "Already installed — skipping")
                listener.onSuccess()
                return@withContext
            }

            when (mode) {
                // ── Auto: Bootstrap + Payload offline si existe ───────────
                // Orden: 1) Termux Bootstrap  2) payload.tar.gz (OpenClaw)
                "auto" -> {
                    // Paso 1: Termux Bootstrap (siempre)
                    AppLogger.i(TAG, "Auto mode: step 1 — Termux Bootstrap")
                    installViaTermuxBootstrap(listener)

                    // Paso 2: Payload offline encima (si existe en assets)
                    if (hasPayloadAsset()) {
                        AppLogger.i(TAG, "Auto mode: step 2 — OpenClaw payload from assets")
                        installOffline(listener)
                    } else {
                        AppLogger.i(TAG, "Auto mode: no payload asset — online install needed")
                        // El usuario deberá ejecutar la instalación online desde el terminal
                    }
                }

                // ── Termux Bootstrap solo ─────────────────────────────────
                "termux-bootstrap" -> {
                    AppLogger.i(TAG, "Installing Termux Bootstrap only")
                    installViaTermuxBootstrap(listener)
                }

                // ── Offline: Bootstrap + Payload ──────────────────────────
                // payload.tar.gz = OpenClaw, NO es Termux
                "offline" -> {
                    if (customUri != null) {
                        // Archivo externo seleccionado por el usuario
                        installFromCustomPayload(customUri, listener)
                    } else if (hasPayloadAsset()) {
                        // Paso 1: Bootstrap primero (necesario para que OpenClaw funcione)
                        AppLogger.i(TAG, "Offline mode: step 1 — Termux Bootstrap")
                        installViaTermuxBootstrap(listener)
                        // Paso 2: Payload de OpenClaw encima
                        AppLogger.i(TAG, "Offline mode: step 2 — OpenClaw payload")
                        installOffline(listener)
                    } else {
                        listener.onError(
                            "No hay payload bundled en el APK.\n" +
                            "Usa 'auto' para instalar Termux Bootstrap, luego ejecuta\n" +
                            "la instalación online desde el terminal."
                        )
                    }
                }

                // ── Online: Bootstrap + curl | bash en terminal ───────────
                // El modo "online" solo prepara el bootstrap.
                // La instalación online real (curl | bash) se ejecuta en el
                // terminal embebido via MainActivity.runOnlineInstallInTerminal()
                "online" -> {
                    if (!TermuxBootstrapManager(context).isInstalled()) {
                        AppLogger.i(TAG, "Online mode: installing Termux Bootstrap first")
                        installViaTermuxBootstrap(listener)
                    } else {
                        AppLogger.i(TAG, "Online mode: bootstrap already installed")
                        listener.onSuccess()
                    }
                    // La instalación online (curl | bash) se ejecuta en el terminal
                    // via MainActivity.runOnlineInstallInTerminal() — no aquí
                }

                // ── Proot + Ubuntu (AVANZADO) ─────────────────────────────
                "proot" -> {
                    AppLogger.i(TAG, "Installing Proot + Ubuntu (advanced option)")
                    installViaProot(listener)
                }

                else -> {
                    listener.onError("Modo de instalación desconocido: $mode")
                }
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Install failed fatally: ${e.message}", e)
            listener.onError(
                "Instalación falló: ${e.message ?: "error desconocido"}",
                e,
            )
        }
    }

    /**
     * Instalación via Termux Bootstrap oficial.
     * Descarga e instala el bootstrap de Termux con dpkg, apt y herramientas esenciales.
     * Más ligero que proot (~50MB vs ~250MB) y proporciona un entorno Termux completo.
     */
    private suspend fun installViaTermuxBootstrap(listener: ProgressListener) {
        AppLogger.i(TAG, "Starting Termux bootstrap installation")
        val bootstrapManager = TermuxBootstrapManager(context)

        // Si ya está instalado, solo verificar
        if (bootstrapManager.isInstalled()) {
            AppLogger.i(TAG, "Termux bootstrap already installed")
            listener.onSuccess()
            return
        }

        // Delegar la instalación al TermuxBootstrapManager
        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                // Sincronizar marcadores con el sistema legado
                try {
                    writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write legacy marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message", cause)
                } else {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message")
                }
                listener.onError(message, cause)
            }
        })
    }

    /**
     * Instalación via proot + Ubuntu rootfs.
     * Este es el nuevo flujo principal — robusto contra Phantom Process Killer.
     */
    private fun installViaProot(listener: ProgressListener) {
        AppLogger.i(TAG, "Starting proot-based installation")
        val setupManager = SetupManager(context)

        // Si ya está instalado via proot, solo verificar
        if (setupManager.isInstalled()) {
            AppLogger.i(TAG, "proot installation already complete")
            listener.onSuccess()
            return
        }

        setupManager.install(object : SetupManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                // Sincronizar marcadores con el sistema legado
                try {
                    writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write legacy marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) {
                    AppLogger.e(TAG, "proot install error: $message", cause)
                } else {
                    AppLogger.e(TAG, "proot install error: $message")
                }
                listener.onError(message, cause)
            }
        })
    }

    private fun installOffline(listener: ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde assets...")

        try {
            // Asegurar que homeDir existe
            homeDir.mkdirs()
            File(filesDir, "tmp").mkdirs()

            // ── Detectar el asset path ────────────────────────────────────────
            val assetPath = getPayloadAssetPath()
            if (assetPath == null) {
                // Diagnóstico: listar todos los assets disponibles
                val available = try {
                    context.assets.list("")?.joinToString(", ") ?: "(vacío)"
                } catch (e: Exception) { "(error: ${e.message})" }
                AppLogger.e(TAG, "No payload asset found. Available root assets: $available")
                listener.onError(
                    "No se encontró payload en el APK.\n" +
                    "Assets disponibles: $available\n" +
                    "Esperado: payload.tar.gz en assets/"
                )
                return
            }

            AppLogger.i(TAG, "Payload asset found: $assetPath")

            // ── Copiar el payload desde assets a homeDir ──────────────────────
            val payloadDest = File(homeDir, "openclaw-payload.tar.gz")
            if (!payloadDest.exists() || payloadDest.length() < 1_000_000) {
                listener.onProgress(2, "Copiando payload desde APK...")
                AppLogger.i(TAG, "Copying asset '$assetPath' → ${payloadDest.absolutePath}")
                context.assets.open(assetPath).use { input ->
                    payloadDest.outputStream().use { output -> input.copyTo(output) }
                }
                AppLogger.i(TAG, "Payload copied: ${payloadDest.length() / 1024 / 1024}MB")
            } else {
                AppLogger.i(TAG, "Payload already copied: ${payloadDest.length() / 1024 / 1024}MB")
            }

            listener.onProgress(5, "Extrayendo payload (puede tardar 1-2 min)...")
            AppLogger.i(TAG, "Extracting payload to ${homeDir.absolutePath}")
            val count = PayloadExtractor.extractTarGzFile(payloadDest, homeDir)
            AppLogger.i(TAG, "Payload extracted: $count entries to ${homeDir.absolutePath}")

            // Verificar que el payload se extrajo correctamente
            val payloadDir = resolvePayloadDir()
            AppLogger.i(TAG, "Resolved payload dir: ${payloadDir.absolutePath} (exists=${payloadDir.exists()})")

            listener.onProgress(75, "Payload extraído ($count archivos). Configurando entorno...")
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Offline extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo payload: ${e.message}", e)
        }
    }

    private fun installFromCustomPayload(uri: android.net.Uri, listener: ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde archivo externo...")
        try {
            listener.onProgress(5, "Abriendo archivo seleccionado...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                listener.onProgress(10, "Extrayendo contenido (streaming)...")
                val count = PayloadExtractor.extractTarGzStream(input, filesDir)
                AppLogger.i(TAG, "Custom payload extracted: $count entries")
            }
            
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Custom payload extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo archivo externo: ${e.message}", e)
        }
    }

    private fun completeInstallation(listener: ProgressListener) {
        listener.onProgress(80, "Configurando entorno...")
        applyScriptUpdate()

        // ── El payload ya tiene glibc/ extraído directamente ──────────────────
        // Estructura de payload-final.tar.gz (rutas reales):
        //   payload/glibc/lib/ld-linux-aarch64.so.1  ← loader glibc
        //   payload/glibc/lib/libc.so.6 + libs        ← librerías glibc
        //   payload/lib/node/bin/node.real             ← Node.js ELF 120MB
        //   payload/lib/openclaw/openclaw.mjs          ← OpenClaw
        //   payload/certs/cert.pem                     ← certificados SSL
        //   payload/patches/glibc-compat.js            ← shim de compatibilidad
        //   payload/run-openclaw.sh                    ← launcher
        //
        // No hay glibc-aarch64.tar.xz dentro — glibc está listo tras la extracción.
        // Solo necesitamos: verificar, reparar symlinks, permisos y crear wrappers.

        val payloadDir = resolvePayloadDir()
        AppLogger.i(TAG, "Payload dir: ${payloadDir.absolutePath}")

        // ── Paso 1: verificar y reparar glibc ────────────────────────────────
        listener.onProgress(82, "Verificando glibc...")
        verifyAndRepairGlibc(payloadDir, listener)

        // ── Paso 2: crear wrapper node en .openclaw-android/bin/ ─────────────
        listener.onProgress(85, "Configurando Node.js...")
        setupNodeWrapper(payloadDir)

        // ── Paso 3: configurar DNS y SSL ──────────────────────────────────────
        listener.onProgress(87, "Configurando DNS y SSL...")
        setupDnsAndSsl(payloadDir)

        // ── Paso 4: crear openclaw-start.sh ──────────────────────────────────
        listener.onProgress(88, "Creando scripts de lanzamiento...")
        createStartScript(payloadDir)

        // ── Paso 5: permisos ──────────────────────────────────────────────────
        applyPermissions()

        // ── Paso 6: escribir marcador de instalación ──────────────────────────
        writeInstalledJson(payloadDir)

        // ── Paso 7: validar ───────────────────────────────────────────────────
        listener.onProgress(90, "Validando instalación...")
        val validation = InstallValidator.validatePayload(payloadDir)
        if (!validation.passed) {
            val errorMsg = "Validación fallida: ${validation.errors.joinToString("; ")}"
            AppLogger.e(TAG, errorMsg)
            listener.onError(errorMsg)
            return
        }
        validation.warnings.forEach { AppLogger.w(TAG, "Install warning: $it") }

        writeMarker()
        listener.onProgress(100, "¡Instalación completada!")
        listener.onSuccess()
    }

    /**
     * Verifica que glibc está en su lugar y repara symlinks críticos.
     * El payload ya tiene glibc/ extraído — solo verificamos y reparamos.
     *
     * Si glibc NO está (payload corrupto o instalación manual incompleta),
     * busca glibc-aarch64.tar.xz en ubicaciones conocidas como fallback.
     */
    private fun verifyAndRepairGlibc(payloadDir: File, listener: ProgressListener) {
        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")

        if (ldso.exists() && ldso.length() > 100_000) {
            ldso.setExecutable(true, false)
            AppLogger.i(TAG, "glibc OK: ${ldso.absolutePath} (${ldso.length()} bytes)")
            listener.onProgress(83, "glibc verificado (${ldso.length() / 1024}KB)")
        } else {
            // glibc no está — buscar archivo comprimido como fallback
            AppLogger.w(TAG, "glibc linker missing at ${ldso.absolutePath}, searching for archive...")
            listener.onProgress(82, "glibc no encontrado, buscando archivo comprimido...")
            val archive = findGlibcArchive(payloadDir)
            if (archive != null) {
                AppLogger.i(TAG, "Extracting glibc from ${archive.absolutePath}")
                listener.onProgress(82, "Extrayendo glibc (${archive.length() / 1024}KB)...")
                try {
                    PayloadExtractor.extractTarXzFile(archive, payloadDir)
                    if (ldso.exists()) {
                        ldso.setExecutable(true, false)
                        AppLogger.i(TAG, "glibc extracted OK: ${ldso.absolutePath}")
                        listener.onProgress(83, "glibc instalado correctamente")
                    } else {
                        AppLogger.e(TAG, "glibc linker still missing after extraction")
                        listener.onProgress(83, "ADVERTENCIA: glibc no disponible")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "glibc extraction failed: ${e.message}", e)
                    listener.onProgress(83, "ERROR extrayendo glibc: ${e.message}")
                }
            } else {
                AppLogger.e(TAG, "glibc archive not found anywhere")
                listener.onProgress(83, "ADVERTENCIA: glibc no encontrado — instalar manualmente")
            }
        }

        // Reparar symlinks críticos siempre
        PayloadExtractor.repairGlibcSymlinks(File(payloadDir, "glibc/lib"))
    }

    /**
     * Crea el wrapper node en .openclaw-android/bin/node que apunta al node de glibc.
     *
     * Rutas del payload-final.tar.gz:
     *   payload/lib/node/bin/node.real  ← binario ELF principal
     *   payload/glibc/bin/node          ← alternativa (si existe)
     *   payload/glibc/lib/ld-linux-aarch64.so.1  ← loader glibc
     */
    private fun setupNodeWrapper(payloadDir: File) {
        val ocaDir = File(homeDir, ".openclaw-android")
        val binDir = File(ocaDir, "bin")
        binDir.mkdirs()

        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        val glibcLib = File(payloadDir, "glibc/lib")

        // Buscar el binario node en orden de prioridad (estructura de payload-final.tar.gz)
        val nodeReal = listOf(
            File(payloadDir, "lib/node/bin/node.real"),   // payload-final.tar.gz
            File(payloadDir, "glibc/bin/node"),            // alternativa legacy
            File(payloadDir, "lib/node/bin/node"),         // sin .real
        ).firstOrNull { it.exists() && it.length() > 1_000_000 }

        if (nodeReal == null) {
            AppLogger.w(TAG, "node binary not found in payload at ${payloadDir.absolutePath}")
            return
        }
        nodeReal.setExecutable(true, false)
        AppLogger.i(TAG, "node binary found: ${nodeReal.absolutePath} (${nodeReal.length() / 1024 / 1024}MB)")

        // Crear wrapper que lanza node via ld-linux (necesario en Android)
        val nodeWrapper = File(binDir, "node")
        val wrapperContent = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OpenClaw glibc-wrapped Node.js — auto-generated")
            appendLine("unset LD_PRELOAD")
            appendLine("export _OA_WRAPPER_PATH=\"${nodeWrapper.absolutePath}\"")
            appendLine("_OA_COMPAT=\"${ocaDir.absolutePath}/patches/glibc-compat.js\"")
            appendLine("if [ -f \"\$_OA_COMPAT\" ]; then")
            appendLine("  case \"\${NODE_OPTIONS:-}\" in")
            appendLine("    *\"\$_OA_COMPAT\"*) ;;")
            appendLine("    *) export NODE_OPTIONS=\"\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT\" ;;")
            appendLine("  esac")
            appendLine("fi")
            if (ldso.exists()) {
                appendLine("exec \"${ldso.absolutePath}\" --library-path \"${glibcLib.absolutePath}\" \"${nodeReal.absolutePath}\" \"\$@\"")
            } else {
                appendLine("exec \"${nodeReal.absolutePath}\" \"\$@\"")
            }
        }
        nodeWrapper.writeText(wrapperContent)
        nodeWrapper.setExecutable(true, false)
        AppLogger.i(TAG, "node wrapper created: ${nodeWrapper.absolutePath}")

        // npm wrapper — buscar npm-cli.js en rutas del payload
        // La estructura de payload-final.tar.gz pone npm en lib/node/lib/node_modules/npm/
        val npmCli = listOf(
            File(payloadDir, "lib/node/lib/node_modules/npm/bin/npm-cli.js"),  // payload-final.tar.gz
            File(payloadDir, "lib/openclaw/node_modules/npm/bin/npm-cli.js"),  // legacy
            File(payloadDir, "glibc/lib/node_modules/npm/bin/npm-cli.js"),     // legacy
        ).firstOrNull { it.exists() }

        if (npmCli != null) {
            val npmWrapper = File(binDir, "npm")
            npmWrapper.writeText("#!/system/bin/sh\nexec \"${nodeWrapper.absolutePath}\" \"${npmCli.absolutePath}\" \"\$@\"\n")
            npmWrapper.setExecutable(true, false)
        }

        // Copiar glibc-compat.js — primero desde el payload, luego desde assets
        val patchesDir = File(ocaDir, "patches")
        patchesDir.mkdirs()
        val compatDest = File(patchesDir, "glibc-compat.js")
        if (!compatDest.exists()) {
            // Intentar desde el payload (payload/patches/glibc-compat.js)
            val compatInPayload = File(payloadDir, "patches/glibc-compat.js")
            if (compatInPayload.exists()) {
                compatInPayload.copyTo(compatDest, overwrite = true)
                AppLogger.i(TAG, "glibc-compat.js copied from payload")
            } else {
                // Fallback: desde assets
                try {
                    context.assets.open("glibc-compat.js").use { i ->
                        compatDest.outputStream().use { o -> i.copyTo(o) }
                    }
                    AppLogger.i(TAG, "glibc-compat.js installed from assets")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not copy glibc-compat.js: ${e.message}")
                }
            }
        }
    }

    /**
     * Configura DNS (resolv.conf) y SSL (cert.pem) para que node pueda hacer HTTPS.
     */
    private fun setupDnsAndSsl(payloadDir: File) {
        val dns = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"

        // resolv.conf en glibc/etc/
        val glibcEtc = File(payloadDir, "glibc/etc")
        glibcEtc.mkdirs()
        val resolvConf = File(glibcEtc, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText(dns)
            AppLogger.i(TAG, "resolv.conf written: ${resolvConf.absolutePath}")
        }

        // nsswitch.conf
        val nsswitch = File(glibcEtc, "nsswitch.conf")
        if (!nsswitch.exists()) {
            nsswitch.writeText("passwd: files\ngroup: files\nhosts: files dns\n")
        }

        // SSL: usar cert.pem del payload si existe, sino Android system certs
        val sslDir = File(payloadDir, "ssl")
        sslDir.mkdirs()
        val certPem = File(sslDir, "cert.pem")
        if (!certPem.exists() || certPem.length() == 0L) {
            // payload-final.tar.gz pone los certs en payload/certs/cert.pem
            val certInPayload = File(payloadDir, "certs/cert.pem")
            if (certInPayload.exists() && certInPayload.length() > 0) {
                certInPayload.copyTo(certPem, overwrite = true)
                AppLogger.i(TAG, "cert.pem copied from payload/certs/ to ssl/")
            } else {
                val androidCerts = File("/system/etc/security/cacerts")
                if (androidCerts.isDirectory) {
                    var count = 0
                    androidCerts.listFiles()?.filter { it.name.endsWith(".0") }?.forEach { cert ->
                        try {
                            val content = cert.readText()
                            if (content.contains("BEGIN CERTIFICATE")) {
                                certPem.appendText(content)
                                count++
                            }
                        } catch (_: Exception) {}
                    }
                    AppLogger.i(TAG, "SSL cert bundle built from Android system: $count certs")
                }
            }
        }
    }

    /**
     * Crea openclaw-start.sh en homeDir — el script que lanza el gateway.
     * Usa run-openclaw.sh del payload si existe, sino crea uno nuevo.
     */
    private fun createStartScript(payloadDir: File) {
        val startScript = File(homeDir, "openclaw-start.sh")
        val runScript = File(payloadDir, "run-openclaw.sh")

        if (runScript.exists()) {
            runScript.setExecutable(true, false)
            // Crear wrapper que llama al run-openclaw.sh del payload
            startScript.writeText(buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# OpenClaw gateway launcher — auto-generated")
                appendLine("export HOME=\"${homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${File(filesDir, "tmp").absolutePath}\"")
                appendLine("export OA_GLIBC=1")
                appendLine("export CONTAINER=1")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${runScript.absolutePath}\" gateway --host 0.0.0.0 \"\$@\"")
            })
        } else {
            // Crear script directo — buscar openclaw.mjs en rutas del payload
            val nodeWrapper = File(homeDir, ".openclaw-android/bin/node")
            val ocMjs = listOf(
                File(payloadDir, "lib/openclaw/openclaw.mjs"),   // payload-final.tar.gz
                File(payloadDir, "openclaw/openclaw.mjs"),        // legacy
            ).firstOrNull { it.exists() } ?: File(payloadDir, "lib/openclaw/openclaw.mjs")

            startScript.writeText(buildString {
                appendLine("#!/system/bin/sh")
                appendLine("export HOME=\"${homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${File(filesDir, "tmp").absolutePath}\"")
                appendLine("export OA_GLIBC=1")
                appendLine("export CONTAINER=1")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${nodeWrapper.absolutePath}\" \"${ocMjs.absolutePath}\" gateway --host 0.0.0.0 \"\$@\"")
            })
        }
        startScript.setExecutable(true, false)
        AppLogger.i(TAG, "openclaw-start.sh created: ${startScript.absolutePath}")
    }

    /**
     * Escribe installed.json con las versiones detectadas del payload.
     */
    private fun writeInstalledJson(payloadDir: File) {
        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        val marker = File(ocaDir, "installed.json")

        val openclawVersion = readOpenClawVersionFromPayload(payloadDir)
        val nodeVersion = readNodeVersionFromPayload(payloadDir)

        marker.writeText(
            """
            {
              "installed": true,
              "source": "payload",
              "openclawVersion": "$openclawVersion",
              "nodeVersion": "$nodeVersion",
              "payloadDir": "${payloadDir.absolutePath}",
              "installedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent()
        )
        AppLogger.i(TAG, "installed.json written: openclaw=$openclawVersion node=$nodeVersion")
    }

    /**
     * Lee la versión de OpenClaw desde el package.json del payload.
     * payload-final.tar.gz: payload/lib/openclaw/package.json
     */
    private fun readOpenClawVersionFromPayload(payloadDir: File): String {
        val candidates = listOf(
            File(payloadDir, "lib/openclaw/package.json"),   // payload-final.tar.gz
            File(payloadDir, "openclaw/package.json"),        // legacy
        )
        for (pkg in candidates) {
            if (pkg.exists()) {
                return try {
                    val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg.readText())
                    match?.groupValues?.getOrNull(1) ?: continue
                } catch (_: Exception) { continue }
            }
        }
        return "unknown"
    }

    /**
     * Lee la versión de Node.js desde el binario del payload.
     * payload-final.tar.gz: payload/lib/node/bin/node.real
     */
    private fun readNodeVersionFromPayload(payloadDir: File): String {
        val nodeReal = listOf(
            File(payloadDir, "lib/node/bin/node.real"),   // payload-final.tar.gz
            File(payloadDir, "glibc/bin/node"),            // legacy
        ).firstOrNull { it.exists() && it.length() > 1_000_000 } ?: return "unknown"

        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")

        return try {
            val cmd = if (ldso.exists()) {
                listOf(ldso.absolutePath, "--library-path",
                    File(payloadDir, "glibc/lib").absolutePath,
                    nodeReal.absolutePath, "--version")
            } else {
                listOf(nodeReal.absolutePath, "--version")
            }
            val pb = ProcessBuilder(cmd)
            pb.environment().apply {
                clear()
                put("PATH", "/system/bin:/bin")
                put("LD_LIBRARY_PATH", File(payloadDir, "glibc/lib").absolutePath)
            }
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.removePrefix("v").trim().ifEmpty { "unknown" }
        } catch (_: Exception) { "unknown" }
    }

    /**
     * Resuelve el directorio del payload extraído.
     */
    private fun resolvePayloadDir(): File {
        return listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload"),
        ).firstOrNull { it.isDirectory }
            ?: File(homeDir, "payload").also { it.mkdirs() }
    }

    /**
     * Busca glibc-aarch64.tar.xz como fallback si glibc no está en el payload.
     * Incluye Descargas del teléfono para instalación manual.
     */
    private fun findGlibcArchive(payloadDir: File): File? {
        val candidates = listOf(
            File(payloadDir, "glibc-aarch64.tar.xz"),
            File(homeDir, "glibc-aarch64.tar.xz"),
            File(filesDir, "glibc-aarch64.tar.xz"),
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "glibc-aarch64.tar.xz"),
        )
        return candidates.firstOrNull { it.exists() && it.length() > 10_000 }
    }

    /**
     * Instala glibc desde un archivo externo proporcionado por el usuario.
     */
    fun installGlibcFromFile(archiveFile: File): Boolean {
        AppLogger.i(TAG, "Installing glibc from: ${archiveFile.absolutePath}")
        if (!archiveFile.exists() || archiveFile.length() < 10_000) return false

        val payloadDir = resolvePayloadDir()
        val dest = File(homeDir, "glibc-aarch64.tar.xz")
        if (archiveFile.absolutePath != dest.absolutePath) {
            try { archiveFile.copyTo(dest, overwrite = true) } catch (_: Exception) {}
        }

        return try {
            PayloadExtractor.extractTarXzFile(archiveFile, payloadDir)
            val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
            if (ldso.exists()) {
                ldso.setExecutable(true, false)
                PayloadExtractor.repairGlibcSymlinks(File(payloadDir, "glibc/lib"))
                true
            } else false
        } catch (e: Exception) {
            AppLogger.e(TAG, "glibc install failed: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Online install (download bootstrap from network)
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun installOnline(listener: ProgressListener) {
        listener.onProgress(0, "Iniciando descarga de payload...")
        
        val payloadUrl = "https://github.com/desarrollo032/openclaw-android/releases/download/latest/openclaw-payload.tar.gz"
        val tempFile = File(cacheDir, "downloaded-payload.tar.gz")
        
        try {
            listener.onProgress(5, "Conectando con el servidor...")
            val url = URL(payloadUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            
            if (connection.responseCode != 200) {
                throw Exception("Error del servidor: ${connection.responseCode} ${connection.responseMessage}")
            }
            
            val totalSize = connection.contentLength.toLong()
            var downloaded = 0L
            
            listener.onProgress(10, "Descargando payload (${totalSize / 1024 / 1024}MB)...")
            
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = 10 + (downloaded * 60 / totalSize).toInt()
                            listener.onProgress(pct, "Descargando... ${(downloaded / 1024 / 1024)}MB / ${(totalSize / 1024 / 1024)}MB")
                        }
                    }
                }
            }
            
            listener.onProgress(70, "Descarga completada. Extrayendo...")
            tempFile.inputStream().use { input ->
                val count = PayloadExtractor.extractTarGzStream(input, filesDir)
                AppLogger.i(TAG, "Online payload extracted: $count entries")
            }
            
            completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Online install failed: ${e.message}", e)
            listener.onError("Error en la instalación online: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun writeMarker() {
        try {
            // Internal app marker
            markerInstalled.writeText("0.4.0\n")
            
            // Shell script marker (~/.openclaw-android/installed.json)
            val ocaDir = File(homeDir, ".openclaw-android")
            ocaDir.mkdirs()
            val ocaMarker = File(ocaDir, "installed.json")
            ocaMarker.writeText("{\"version\": \"0.4.0\", \"status\": \"success\"}\n")
            
            AppLogger.i(TAG, "Installed markers written at ${markerInstalled.absolutePath} and ${ocaMarker.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write marker: ${e.message}", e)
        }
    }

    /**
     * Set executable permissions on extracted binaries and create emergency wrappers.
     */
    private fun applyPermissions() {
        val ocaPayloadDir = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload")
        ).find { it.isDirectory } ?: filesDir

        AppLogger.i(TAG, "Applying permissions in ${ocaPayloadDir.absolutePath}")

        // Asegurar que run-openclaw.sh sea ejecutable
        val runScript = File(ocaPayloadDir, "run-openclaw.sh")
        if (runScript.exists()) {
            runScript.setExecutable(true, false)
        }

        // Asegurar que el binario de node sea ejecutable (en prefix/bin o ocaPayloadDir/bin)
        listOf(
            File(prefix, "bin/node"),
            File(ocaPayloadDir, "bin/node"),
            File(homeDir, ".openclaw-android/bin/node")
        ).forEach { node ->
            if (node.exists()) node.setExecutable(true, false)
        }

        // Asegurar que el cargador de glibc sea ejecutable
        val ldsoInPrefix = File(prefix, "glibc/lib/ld-linux-aarch64.so.1")
        if (ldsoInPrefix.exists()) {
            ldsoInPrefix.setExecutable(true, false)
        }
        
        // Recursively set executable for ALL files in the entire payload directory
        if (ocaPayloadDir.isDirectory) {
            AppLogger.i(TAG, "Setting recursive permissions on payload: ${ocaPayloadDir.absolutePath}")
            ocaPayloadDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, false)
                }
            }
        }

        // Crear enlace simbólico o wrapper en bin/openclaw para acceso fácil
        val prefixBinDir = File(prefix, "bin")
        prefixBinDir.mkdirs()
        val openclawLink = File(prefixBinDir, "openclaw")
        val mainRunScript = getRunScriptPath()
        
        if (mainRunScript.exists()) {
            try {
                // Intentar crear un wrapper script en bin/openclaw
                val wrapper = "#!/system/bin/sh\nexec \"${mainRunScript.absolutePath}\" \"$@\"\n"
                openclawLink.writeText(wrapper)
                openclawLink.setExecutable(true, false)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create bin wrapper: ${e.message}")
            }
        }

        // Crear wrapper glibc para el binario ELF openclaw en .openclaw-android/bin/
        // Este wrapper está PRIMERO en el PATH ($ocaBin) y evita el error "invalid ELF header"
        // cuando el kernel Android intenta ejecutar el ELF directamente.
        val ocaBinDir = File(homeDir, ".openclaw-android/bin")
        ocaBinDir.mkdirs()
        val ocaOpenclawWrapper = File(ocaBinDir, "openclaw")
        val ldso = File(ocaPayloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        val glibcLib = File(ocaPayloadDir, "glibc/lib")

        // Buscar el binario ELF openclaw en el payload
        val openclawElf = listOf(
            File(ocaPayloadDir, "bin/openclaw"),
            File(prefix, "bin/openclaw"),
        ).firstOrNull { f ->
            f.exists() && f.length() > 1000 && run {
                // Verificar que es ELF (no un script)
                try {
                    val magic = f.inputStream().use { it.read(ByteArray(4)) }
                    false // no podemos leer aquí fácilmente, asumir que es ELF si es grande
                } catch (_: Exception) { false }
            }
        }

        // Siempre crear el wrapper en ocaBin que usa el node wrapper para lanzar openclaw.mjs
        // Esto es más robusto que intentar ejecutar el ELF directamente
        val ocMjs = listOf(
            File(ocaPayloadDir, "lib/openclaw/openclaw.mjs"),
            File(prefix, "lib/node_modules/openclaw/openclaw.mjs"),
            File(ocaPayloadDir, "openclaw/openclaw.mjs"),
        ).firstOrNull { it.exists() }

        val nodeWrapper = File(ocaBinDir, "node")
        if (ocMjs != null && nodeWrapper.exists()) {
            val ocaWrapper = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# OpenClaw glibc-wrapped launcher — auto-generated")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${nodeWrapper.absolutePath}\" \"${ocMjs.absolutePath}\" \"\$@\"")
            }
            ocaOpenclawWrapper.writeText(ocaWrapper)
            ocaOpenclawWrapper.setExecutable(true, false)
            AppLogger.i(TAG, "openclaw wrapper (via node) created: ${ocaOpenclawWrapper.absolutePath}")
        } else if (ldso.exists() && openclawElf != null) {
            // Fallback: envolver el ELF directamente con el linker glibc
            val elfWrapper = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# OpenClaw glibc ELF wrapper — auto-generated")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${ldso.absolutePath}\" --library-path \"${glibcLib.absolutePath}\" \"${openclawElf.absolutePath}\" \"\$@\"")
            }
            ocaOpenclawWrapper.writeText(elfWrapper)
            ocaOpenclawWrapper.setExecutable(true, false)
            AppLogger.i(TAG, "openclaw wrapper (via ld.so) created: ${ocaOpenclawWrapper.absolutePath}")
        } else {
            AppLogger.w(TAG, "Could not create openclaw wrapper: ocMjs=$ocMjs nodeWrapper=${nodeWrapper.exists()} ldso=${ldso.exists()}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Status queries (for JsBridge / UI)
    // ═══════════════════════════════════════════════════════════════════════

    fun getRunScriptPath(): File {
        // Nuevo flujo proot: usar el script generado por SetupManager
        val prootScript = File(homeDir, "openclaw-start.sh")
        if (prootScript.exists()) return prootScript

        // Legado: buscar en payload dirs
        val ocaPayloadDir = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload")
        ).find { it.isDirectory } ?: filesDir
        return File(ocaPayloadDir, "run-openclaw.sh")
    }

    fun getStatus(): String = when {
        isReady() -> "ready"
        isInstalled() -> "setup_required"
        else -> "not_installed"
    }

    fun isOpenClawInstalled(): Boolean {
        // Nuevo flujo proot
        if (File(filesDir, ".proot-installed").exists()) {
            return SetupManager(context).isOpenClawInstalledInRootfs()
        }
        // Online install (curl -sL myopenclawhub.com/install | bash)
        if (File(prefix, "lib/node_modules/openclaw/openclaw.mjs").exists()) return true
        // payload-final.tar.gz layout
        val ocaPayloadDir = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload")
        ).find { it.isDirectory } ?: filesDir
        return File(prefix, "bin/openclaw").exists() ||
               File(ocaPayloadDir, "run-openclaw.sh").exists() ||
               File(ocaPayloadDir, "lib/openclaw/openclaw.mjs").exists()
    }

    /** Sync www assets from APK to the share directory. */
    fun syncWwwFromAssets() {
        val wwwDest = getWwwDir()
        if (!wwwDest.exists()) wwwDest.mkdirs()
        copyAssetFolder(context.assets, "www", wwwDest.absolutePath)
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, assetPath: String, destPath: String) {
        try {
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {
                // It's a file
                val destFile = File(destPath)
                assetManager.open(assetPath).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // It's a directory
                val destDir = File(destPath)
                if (!destDir.exists()) destDir.mkdirs()
                for (asset in assets) {
                    copyAssetFolder(assetManager, "$assetPath/$asset", "$destPath/$asset")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to copy asset $assetPath: ${e.message}")
        }
    }

    fun getWwwDir(): File = File(prefix, "share/openclaw-app/www")
    fun getPrefixDir(): File = prefix
    fun getHomeDir(): File = homeDir

    /**
     * Instalación online legacy: usa el payload bundled si existe,
     * o informa al usuario que debe usar el modo proot explícitamente.
     * NO activa proot automáticamente — eso es responsabilidad del modo "proot".
     */
    private fun installOnlineLegacy(listener: ProgressListener) {
        AppLogger.i(TAG, "Online install: no bundled payload, reporting to UI")
        // No hay payload en assets y el usuario eligió "online" (no "proot").
        // Reportar que se necesita conexión a internet y que el modo proot
        // está disponible como alternativa robusta.
        listener.onError(
            "Instalación online: no hay payload bundled en el APK. " +
            "Usa el modo 'proot' para instalar OpenClaw descargando Ubuntu + Node.js, " +
            "o proporciona un archivo payload externo."
        )
    }

    fun applyScriptUpdate() {
        val ocaPayloadDir = listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload")
        ).find { it.isDirectory } ?: filesDir
        val ocaDir = File(homeDir, ".openclaw-android")
        
        val scripts = listOf(
            "run.sh" to File(prefix, "bin/run.sh"),
            "post-setup.sh" to File(ocaPayloadDir, "post-setup.sh"),
            "run-openclaw.sh" to File(ocaPayloadDir, "run-openclaw.sh"),
            "glibc-compat.js" to File(ocaDir, "patches/glibc-compat.js"),
        )
        for ((assetName, destFile) in scripts) {
            try {
                context.assets.open(assetName).use { input ->
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
            } catch (_: Exception) {
            }
        }
    }
}
