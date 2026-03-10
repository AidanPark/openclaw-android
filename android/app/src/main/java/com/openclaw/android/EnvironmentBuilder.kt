package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * Builds the complete process environment for Termux bootstrap (§2.2.5).
 * Based on AnyClaw CodexServerManager.kt pattern.
 */
object EnvironmentBuilder {

    fun build(context: Context): Map<String, String> {
        val filesDir = context.filesDir
        val prefix = File(filesDir, "usr")
        val home = File(filesDir, "home")
        val tmp = File(filesDir, "tmp")

        return buildMap {
            // Core paths
            put("PREFIX", prefix.absolutePath)
            put("HOME", home.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "${home.absolutePath}/.openclaw-android/node/bin:${home.absolutePath}/.local/bin:${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets")
            put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib")

            // libtermux-exec path conversion (§2.2.4)
            // The bootstrap binaries have hardcoded /data/data/com.termux/... paths.
            // libtermux-exec intercepts file operations and rewrites them.
            // Only set LD_PRELOAD if the library actually exists — otherwise
            // the dynamic linker refuses to start any process.
            val termuxExecLib = File(prefix, "lib/libtermux-exec.so")
            if (termuxExecLib.exists()) {
                put("LD_PRELOAD", termuxExecLib.absolutePath)
            }
            put("TERMUX__PREFIX", prefix.absolutePath)
            put("TERMUX_PREFIX", prefix.absolutePath)
            put("TERMUX__ROOTFS", filesDir.absolutePath)
            // Tell libtermux-exec where the current app data dir is
            val appDataDir = filesDir.parentFile?.absolutePath ?: filesDir.absolutePath
            put("TERMUX_APP__DATA_DIR", appDataDir)
            // Tell libtermux-exec the OLD path to match against and rewrite
            put("TERMUX_APP__LEGACY_DATA_DIR", "/data/data/com.termux")
            // put("TERMUX_EXEC__LOG_LEVEL", "2") // Uncomment to debug libtermux-exec

            // apt/dpkg (§2.2.3)
            put("APT_CONFIG", "${prefix.absolutePath}/etc/apt/apt.conf")
            put("DPKG_ADMINDIR", "${prefix.absolutePath}/var/lib/dpkg")
            put("DPKG_ROOT", prefix.absolutePath)

            // SSL (libgnutls.so hardcoded path workaround)
            put("SSL_CERT_FILE", "${prefix.absolutePath}/etc/tls/cert.pem")

            put("CURL_CA_BUNDLE", "${prefix.absolutePath}/etc/tls/cert.pem")
            put("GIT_SSL_CAINFO", "${prefix.absolutePath}/etc/tls/cert.pem")

            // Git (system gitconfig has hardcoded com.termux path)
            put("GIT_CONFIG_NOSYSTEM", "1")

            // Git exec path (git looks for helpers like git-remote-https here)
            put("GIT_EXEC_PATH", "${prefix.absolutePath}/libexec/git-core")

            // Git template dir (hardcoded /data/data/com.termux path workaround)
            put("GIT_TEMPLATE_DIR", "${prefix.absolutePath}/share/git-core/templates")

            // Locale and terminal
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")

            // Android-specific
            put("ANDROID_DATA", "/data")
            put("ANDROID_ROOT", "/system")

            // OpenClaw platform
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("CLAWDHUB_WORKDIR", "${home.absolutePath}/.openclaw/workspace")
            put("CPATH", "${prefix.absolutePath}/include/glib-2.0:${prefix.absolutePath}/lib/glib-2.0/include")
        }
    }
}
