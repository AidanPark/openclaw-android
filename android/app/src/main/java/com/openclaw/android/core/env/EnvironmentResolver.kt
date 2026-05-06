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
        // CRÍTICO: El PREFIX para Termux Bootstrap DEBE ser filesDir/usr.
        // Si usamos el payloadDir como prefix, bash no encontrará sus binarios.
        val prefix = File(filesDir, "usr").also { it.mkdirs() }

        val glibcLib = resolveGlibcLib(payloadDir, prefix)
        val linker = File(glibcLib, "ld-linux-aarch64.so.1")

        // Node binary — check all known layouts in priority order:
        //   1. payload-final.tar.gz:  payloadDir/lib/node/bin/node.real
        //   2. online install (Termux): homeDir/.openclaw-android/node/bin/node.real
        //   3. legacy payload:         payloadDir/glibc/bin/node
        val nodeBin = listOf(
            File(payloadDir, "lib/node/bin/node.real"),          // payload-final.tar.gz
            File(ocaDir, "node/bin/node.real"),                   // online install (curl | bash)
            File(ocaDir, "node/bin/node"),
            File(payloadDir, "lib/node/bin/node"),
            File(payloadDir, "glibc/bin/node"),                   // legacy
            File(prefix, "bin/node"),
        ).firstOrNull { it.exists() } ?: File(payloadDir, "lib/node/bin/node.real")

        // openclaw.mjs — check all known layouts:
        //   1. payload-final.tar.gz:  payloadDir/lib/openclaw/openclaw.mjs
        //   2. online install (Termux): prefix/lib/node_modules/openclaw/openclaw.mjs
        //   3. legacy payload:         payloadDir/openclaw/openclaw.mjs
        val openClawMjs = listOf(
            File(payloadDir, "lib/openclaw/openclaw.mjs"),                    // payload-final.tar.gz
            File(prefix, "lib/node_modules/openclaw/openclaw.mjs"),           // online install
            File(payloadDir, "openclaw/openclaw.mjs"),                        // legacy
        ).firstOrNull { it.exists() } ?: File(payloadDir, "lib/openclaw/openclaw.mjs")

        // SSL cert — check all known layouts:
        //   1. payload-final.tar.gz:  payloadDir/certs/cert.pem
        //   2. online install (Termux): prefix/etc/tls/cert.pem
        //   3. legacy:                 payloadDir/ssl/cert.pem
        val certPem = listOf(
            File(payloadDir, "certs/cert.pem"),              // payload-final.tar.gz
            File(prefix, "etc/tls/cert.pem"),                // online install / Termux
            File(payloadDir, "ssl/cert.pem"),                // legacy
        ).firstOrNull { it.exists() } ?: File(payloadDir, "certs/cert.pem")

        AppLogger.d(TAG, "Resolved: filesDir=${filesDir.absolutePath} payload=${payloadDir.absolutePath} " +
            "node=${nodeBin.absolutePath}(exists=${nodeBin.exists()}) " +
            "glibc=${linker.exists()}")

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
     * Resolve the glibc lib directory from payload or prefix.
     * Online install puts glibc in prefix/glibc/lib.
     * payload-final.tar.gz puts it in payloadDir/glibc/lib.
     */
    private fun resolveGlibcLib(payloadDir: File, prefix: File): File {
        val fromPayload = File(payloadDir, "glibc/lib")
        if (fromPayload.isDirectory && File(fromPayload, "ld-linux-aarch64.so.1").exists()) {
            return fromPayload
        }
        val fromPrefix = File(prefix, "glibc/lib")
        if (fromPrefix.isDirectory && File(fromPrefix, "ld-linux-aarch64.so.1").exists()) {
            return fromPrefix
        }
        // Default to payloadDir (will be checked by isGlibcReady)
        return fromPayload
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

            // CRITICAL: Do NOT include glibc/lib in LD_LIBRARY_PATH here.
            // This map is used for Bionic shells (/system/bin/sh, prefix/bin/bash).
            // If glibc/lib is in LD_LIBRARY_PATH, Android's Bionic linker finds
            // glibc's libc.so there and fails with:
            //   CANNOT LINK EXECUTABLE "sh": cannot find "libc.so" from verneed[0]
            //
            // The glibc LD_LIBRARY_PATH is added ONLY by the node wrapper script
            // (generated by ScriptWriter/EnvironmentBuilder) when launching node.real
            // via ld-linux-aarch64.so.1. It must never leak into Bionic processes.
            put("LD_LIBRARY_PATH", "$prefixPath/lib")

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
