package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallValidator
import com.openclaw.android.InstallerManager
import com.openclaw.android.PayloadExtractor
import java.io.File

/**
 * Configura el entorno de ejecución tras la extracción del payload.
 *
 * Responsabilidad única: orquestar los pasos post-extracción:
 *   1. Verificar/reparar glibc
 *   2. Crear wrapper de Node.js
 *   3. Configurar DNS y SSL
 *   4. Crear script de lanzamiento
 *   5. Aplicar permisos
 *   6. Validar la instalación
 */
internal class EnvironmentConfigurator(
    private val context: Context,
    private val paths: InstallPathResolver,
    private val markerWriter: InstallMarkerWriter,
) {

    private val TAG = "EnvironmentConfigurator"

    /**
     * Ejecuta todos los pasos de configuración post-extracción.
     * Llama a [listener.onSuccess] si todo va bien, [listener.onError] si falla la validación.
     */
    fun completeInstallation(listener: InstallerManager.ProgressListener) {
        listener.onProgress(80, "Configurando entorno...")
        applyScriptUpdate()

        val payloadDir = paths.resolvePayloadDir()
        AppLogger.i(TAG, "Payload dir: ${payloadDir.absolutePath}")

        listener.onProgress(82, "Verificando glibc...")
        verifyAndRepairGlibc(payloadDir, listener)

        listener.onProgress(85, "Configurando Node.js...")
        setupNodeWrapper(payloadDir)

        listener.onProgress(87, "Configurando DNS y SSL...")
        setupDnsAndSsl(payloadDir)

        listener.onProgress(88, "Creando scripts de lanzamiento...")
        createStartScript(payloadDir)

        applyPermissions()

        markerWriter.writeInstalledJson(payloadDir)

        listener.onProgress(90, "Validando instalación...")
        val validation = InstallValidator.validatePayload(payloadDir)
        if (!validation.passed) {
            val errorMsg = "Validación fallida: ${validation.errors.joinToString("; ")}"
            AppLogger.e(TAG, errorMsg)
            listener.onError(errorMsg)
            return
        }
        validation.warnings.forEach { AppLogger.w(TAG, "Install warning: $it") }

        markerWriter.writeMarker()
        listener.onProgress(100, "¡Instalación completada!")
        listener.onSuccess()
    }

    /**
     * Verifica que glibc está en su lugar y repara symlinks críticos.
     * Si glibc no está, busca glibc-aarch64.tar.xz como fallback.
     */
    fun verifyAndRepairGlibc(payloadDir: File, listener: InstallerManager.ProgressListener) {
        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")

        if (ldso.exists() && ldso.length() > 100_000) {
            ldso.setExecutable(true, false)
            AppLogger.i(TAG, "glibc OK: ${ldso.absolutePath} (${ldso.length()} bytes)")
            listener.onProgress(83, "glibc verificado (${ldso.length() / 1024}KB)")
        } else {
            AppLogger.w(TAG, "glibc linker missing at ${ldso.absolutePath}, searching for archive...")
            listener.onProgress(82, "glibc no encontrado, buscando archivo comprimido...")
            val archive = paths.findGlibcArchive(payloadDir)
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
     *   payload/glibc/bin/node          ← alternativa legacy
     *   payload/glibc/lib/ld-linux-aarch64.so.1  ← loader glibc
     */
    fun setupNodeWrapper(payloadDir: File) {
        val ocaDir = File(paths.homeDir, ".openclaw-android")
        val binDir = File(ocaDir, "bin")
        binDir.mkdirs()

        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        val glibcLib = File(payloadDir, "glibc/lib")

        val nodeReal = listOf(
            File(payloadDir, "lib/node/bin/node.real"),
            File(payloadDir, "glibc/bin/node"),
            File(payloadDir, "lib/node/bin/node"),
        ).firstOrNull { it.exists() && it.length() > 1_000_000 }

        if (nodeReal == null) {
            AppLogger.w(TAG, "node binary not found in payload at ${payloadDir.absolutePath}")
            return
        }
        nodeReal.setExecutable(true, false)
        AppLogger.i(TAG, "node binary found: ${nodeReal.absolutePath} (${nodeReal.length() / 1024 / 1024}MB)")

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

        // npm wrapper
        val npmCli = listOf(
            File(payloadDir, "lib/node/lib/node_modules/npm/bin/npm-cli.js"),
            File(payloadDir, "lib/openclaw/node_modules/npm/bin/npm-cli.js"),
            File(payloadDir, "glibc/lib/node_modules/npm/bin/npm-cli.js"),
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
            val compatInPayload = File(payloadDir, "patches/glibc-compat.js")
            if (compatInPayload.exists()) {
                compatInPayload.copyTo(compatDest, overwrite = true)
                AppLogger.i(TAG, "glibc-compat.js copied from payload")
            } else {
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
    fun setupDnsAndSsl(payloadDir: File) {
        val dns = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"

        val glibcEtc = File(payloadDir, "glibc/etc")
        glibcEtc.mkdirs()
        val resolvConf = File(glibcEtc, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText(dns)
            AppLogger.i(TAG, "resolv.conf written: ${resolvConf.absolutePath}")
        }

        val nsswitch = File(glibcEtc, "nsswitch.conf")
        if (!nsswitch.exists()) {
            nsswitch.writeText("passwd: files\ngroup: files\nhosts: files dns\n")
        }

        val sslDir = File(payloadDir, "ssl")
        sslDir.mkdirs()
        val certPem = File(sslDir, "cert.pem")
        if (!certPem.exists() || certPem.length() == 0L) {
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
    fun createStartScript(payloadDir: File) {
        val startScript = File(paths.homeDir, "openclaw-start.sh")
        val runScript = File(payloadDir, "run-openclaw.sh")

        if (runScript.exists()) {
            runScript.setExecutable(true, false)
            startScript.writeText(buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# OpenClaw gateway launcher — auto-generated")
                appendLine("export HOME=\"${paths.homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${File(paths.filesDir, "tmp").absolutePath}\"")
                appendLine("export OA_GLIBC=1")
                appendLine("export CONTAINER=1")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${runScript.absolutePath}\" gateway --host 0.0.0.0 \"\$@\"")
            })
        } else {
            val nodeWrapper = File(paths.homeDir, ".openclaw-android/bin/node")
            val ocMjs = listOf(
                File(payloadDir, "lib/openclaw/openclaw.mjs"),
                File(payloadDir, "openclaw/openclaw.mjs"),
            ).firstOrNull { it.exists() } ?: File(payloadDir, "lib/openclaw/openclaw.mjs")

            startScript.writeText(buildString {
                appendLine("#!/system/bin/sh")
                appendLine("export HOME=\"${paths.homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${File(paths.filesDir, "tmp").absolutePath}\"")
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
     * Aplica permisos de ejecución a binarios y crea wrappers de emergencia.
     */
    fun applyPermissions() {
        val ocaPayloadDir = paths.resolvePayloadDir()
        AppLogger.i(TAG, "Applying permissions in ${ocaPayloadDir.absolutePath}")

        File(ocaPayloadDir, "run-openclaw.sh").takeIf { it.exists() }
            ?.setExecutable(true, false)

        listOf(
            File(paths.prefix, "bin/node"),
            File(ocaPayloadDir, "bin/node"),
            File(paths.homeDir, ".openclaw-android/bin/node")
        ).forEach { node -> if (node.exists()) node.setExecutable(true, false) }

        File(paths.prefix, "glibc/lib/ld-linux-aarch64.so.1")
            .takeIf { it.exists() }
            ?.setExecutable(true, false)

        // Permisos recursivos en todo el payload
        if (ocaPayloadDir.isDirectory) {
            AppLogger.i(TAG, "Setting recursive permissions on payload: ${ocaPayloadDir.absolutePath}")
            ocaPayloadDir.walkTopDown().forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
        }

        // Wrapper en bin/openclaw para acceso fácil
        val prefixBinDir = File(paths.prefix, "bin")
        prefixBinDir.mkdirs()
        val openclawLink = File(prefixBinDir, "openclaw")
        val mainRunScript = paths.getRunScriptPath()
        if (mainRunScript.exists()) {
            try {
                openclawLink.writeText("#!/system/bin/sh\nexec \"${mainRunScript.absolutePath}\" \"$@\"\n")
                openclawLink.setExecutable(true, false)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create bin wrapper: ${e.message}")
            }
        }

        // Wrapper glibc en .openclaw-android/bin/openclaw
        val ocaBinDir = File(paths.homeDir, ".openclaw-android/bin")
        ocaBinDir.mkdirs()
        val ocaOpenclawWrapper = File(ocaBinDir, "openclaw")
        val ldso = File(ocaPayloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        val glibcLib = File(ocaPayloadDir, "glibc/lib")

        val ocMjs = listOf(
            File(ocaPayloadDir, "lib/openclaw/openclaw.mjs"),
            File(paths.prefix, "lib/node_modules/openclaw/openclaw.mjs"),
            File(ocaPayloadDir, "openclaw/openclaw.mjs"),
        ).firstOrNull { it.exists() }

        val nodeWrapper = File(ocaBinDir, "node")
        if (ocMjs != null && nodeWrapper.exists()) {
            ocaOpenclawWrapper.writeText(buildString {
                appendLine("#!/system/bin/sh")
                appendLine("# OpenClaw glibc-wrapped launcher — auto-generated")
                appendLine("unset LD_PRELOAD")
                appendLine("exec \"${nodeWrapper.absolutePath}\" \"${ocMjs.absolutePath}\" \"\$@\"")
            })
            ocaOpenclawWrapper.setExecutable(true, false)
            AppLogger.i(TAG, "openclaw wrapper (via node) created: ${ocaOpenclawWrapper.absolutePath}")
        } else if (ldso.exists()) {
            val openclawElf = listOf(
                File(ocaPayloadDir, "bin/openclaw"),
                File(paths.prefix, "bin/openclaw"),
            ).firstOrNull { it.exists() && it.length() > 1000 }

            if (openclawElf != null) {
                ocaOpenclawWrapper.writeText(buildString {
                    appendLine("#!/system/bin/sh")
                    appendLine("# OpenClaw glibc ELF wrapper — auto-generated")
                    appendLine("unset LD_PRELOAD")
                    appendLine("exec \"${ldso.absolutePath}\" --library-path \"${glibcLib.absolutePath}\" \"${openclawElf.absolutePath}\" \"\$@\"")
                })
                ocaOpenclawWrapper.setExecutable(true, false)
                AppLogger.i(TAG, "openclaw wrapper (via ld.so) created: ${ocaOpenclawWrapper.absolutePath}")
            }
        } else {
            AppLogger.w(TAG, "Could not create openclaw wrapper: ocMjs=$ocMjs nodeWrapper=${nodeWrapper.exists()} ldso=${ldso.exists()}")
        }
    }

    /**
     * Actualiza scripts desde assets (run.sh, post-setup.sh, run-openclaw.sh, glibc-compat.js).
     */
    fun applyScriptUpdate() {
        val ocaPayloadDir = paths.resolvePayloadDir()
        val ocaDir = File(paths.homeDir, ".openclaw-android")

        val scripts = listOf(
            "run.sh" to File(paths.prefix, "bin/run.sh"),
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
