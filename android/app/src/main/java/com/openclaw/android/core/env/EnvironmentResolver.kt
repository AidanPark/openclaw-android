package com.openclaw.android.core.env

import android.content.Context
import com.openclaw.android.AppLogger
import java.io.File

/**
 * EnvironmentResolver — resolves all paths from context.filesDir.
 *
 * Single source of truth for path resolution. All other components
 * call this instead of computing paths independently.
 *
 * Design:
 *   - ALWAYS uses context.filesDir — never hardcodes /data/data/ or /data/user/0/
 *   - context.filesDir resolves correctly for both debug and release builds
 *   - Payload detection: homeDir/payload > homeDir/openclaw-payload > filesDir/payload
 */
object EnvironmentResolver {

    private const val TAG = "EnvironmentResolver"

    /**
     * Resolve all paths and return an immutable EnvironmentConfig.
     * Creates required directories as a side effect.
     */
    fun resolve(context: Context): EnvironmentConfig =
        resolve(context.filesDir)

    fun resolve(filesDir: File): EnvironmentConfig {
        val homeDir = File(filesDir, "home").also { it.mkdirs() }
        val tmpDir = File(filesDir, "tmp").also { it.mkdirs() }
        val ocaDir = File(homeDir, ".openclaw-android").also { it.mkdirs() }

        val payloadDir = resolvePayloadDir(filesDir, homeDir)
        val prefix = if (payloadDir.absolutePath != filesDir.absolutePath) {
            payloadDir
        } else {
            File(filesDir, "usr").also { it.mkdirs() }
        }

        val glibcLib = File(payloadDir, "glibc/lib")
        val linker = File(glibcLib, "ld-linux-aarch64.so.1")

        val nodeBin = listOf(
            File(payloadDir, "glibc/bin/node"),
            File(payloadDir, "lib/node/bin/node.real"),
            File(payloadDir, "lib/node/bin/node"),
        ).firstOrNull { it.exists() } ?: File(payloadDir, "glibc/bin/node")

        val openClawMjs = listOf(
            File(payloadDir, "openclaw/openclaw.mjs"),
            File(payloadDir, "lib/openclaw/openclaw.mjs"),
        ).firstOrNull { it.exists() } ?: File(payloadDir, "openclaw/openclaw.mjs")

        val certPem = listOf(
            File(payloadDir, "ssl/cert.pem"),
            File(payloadDir, "certs/cert.pem"),
            File(prefix, "etc/tls/cert.pem"),
        ).firstOrNull { it.exists() } ?: File(payloadDir, "ssl/cert.pem")

        AppLogger.d(TAG, "Resolved: filesDir=${filesDir.absolutePath} payload=${payloadDir.absolutePath} glibc=${linker.exists()}")

        return EnvironmentConfig(
            filesDir = filesDir,
            homeDir = homeDir,
            payloadDir = payloadDir,
            prefix = prefix,
            tmpDir = tmpDir,
            ocaDir = ocaDir,
            glibcLib = glibcLib,
            linker = linker,
            nodeBin = nodeBin,
            openClawMjs = openClawMjs,
            certPem = certPem,
        )
    }

    /**
     * Resolve the payload directory in priority order.
     * Returns filesDir if no payload is found (fallback to legacy /usr layout).
     */
    fun resolvePayloadDir(filesDir: File, homeDir: File? = null): File {
        val home = homeDir ?: File(filesDir, "home")
        val candidates = listOf(
            File(home, "payload"),
            File(home, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload"),
        )
        return candidates.firstOrNull { it.isDirectory } ?: filesDir
    }

    /**
     * Build the full environment variable map for process execution.
     * Used by TerminalSessionManager and CommandRunner.
     */
    fun buildEnvMap(config: EnvironmentConfig, packageName: String = "com.openclaw.android"): Map<String, String> {
        val ocaBin = File(config.ocaDir, "bin").absolutePath
        val nodeDir = File(config.ocaDir, "node").absolutePath
        val prefixPath = config.prefix.absolutePath
        val glibcLibPath = config.glibcLib.absolutePath

        return buildMap {
            put("HOME", config.homeDir.absolutePath)
            put("PREFIX", prefixPath)
            put("TMPDIR", config.tmpDir.absolutePath)
            put("APP_FILES_DIR", config.filesDir.absolutePath)
            put("PAYLOAD_DIR", config.payloadDir.absolutePath)
            put("APP_PACKAGE", packageName)

            put("PATH", "$ocaBin:$nodeDir/bin:$prefixPath/bin:$prefixPath/lib/node/bin:$prefixPath/bin/applets:/system/bin:/system/xbin:/vendor/bin:/bin")
            put("NPM_CONFIG_PREFIX", prefixPath)
            put("npm_config_prefix", prefixPath)

            put("LD_LIBRARY_PATH", "$prefixPath/lib:$glibcLibPath")

            // Only set LD_PRELOAD if the file actually exists
            val termuxExec = File("$prefixPath/lib/libtermux-exec.so")
            if (termuxExec.exists()) {
                put("LD_PRELOAD", termuxExec.absolutePath)
            }

            put("TERMUX__PREFIX", prefixPath)
            put("TERMUX_PREFIX", prefixPath)
            put("TERMUX__ROOTFS", config.filesDir.absolutePath)

            put("BASH_ENV", "/dev/null")
            put("ENV", "/dev/null")

            val certPath = config.certPem.absolutePath
            put("SSL_CERT_FILE", certPath)
            put("CURL_CA_BUNDLE", certPath)
            put("GIT_SSL_CAINFO", certPath)

            put("RESOLV_CONF", "$prefixPath/etc/resolv.conf")
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_EXEC_PATH", "$prefixPath/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefixPath/share/git-core/templates")

            put("APT_CONFIG", "$prefixPath/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "$prefixPath/var/lib/dpkg")
            put("DPKG_ROOT", prefixPath)

            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "${config.homeDir.absolutePath}/.openclaw/workspace")

            put("_OA_COMPAT_PATH", "${config.ocaDir.absolutePath}/patches/glibc-compat.js")
            put("_OA_WRAPPER_PATH", "$ocaBin/node")
        }
    }

    /**
     * Ensure resolv.conf exists with working DNS servers.
     * Called before any network operation.
     */
    fun ensureResolvConf(config: EnvironmentConfig) {
        val dns = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"
        listOf(
            File(config.prefix, "etc/resolv.conf"),
            File(config.payloadDir, "glibc/etc/resolv.conf"),
        ).forEach { f ->
            try {
                if (!f.exists() || f.length() == 0L || !f.readText().contains("nameserver")) {
                    f.parentFile?.mkdirs()
                    f.writeText(dns)
                }
            } catch (_: Exception) {}
        }
    }
}
