package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.core.env.EnvironmentConfig
import java.io.File

/**
 * DnsAndSslSetup — configures DNS resolution and SSL certificates.
 *
 * Required for Node.js to make HTTPS requests inside the glibc environment.
 * glibc uses its own resolv.conf and does not inherit Android's DNS config.
 */
object DnsAndSslSetup {

    private const val TAG = "DnsAndSslSetup"

    private val DNS_SERVERS = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n"

    /**
     * Write resolv.conf and nsswitch.conf for glibc DNS resolution.
     * Also sets up SSL cert bundle from Android system certs if needed.
     */
    fun setup(config: EnvironmentConfig, context: Context? = null) {
        setupDns(config)
        setupSsl(config, context)
    }

    fun setupDns(config: EnvironmentConfig) {
        // glibc/etc/resolv.conf — used by glibc resolver
        val glibcEtc = File(config.payloadDir, "glibc/etc").also { it.mkdirs() }
        writeIfMissing(File(glibcEtc, "resolv.conf"), DNS_SERVERS)
        writeIfMissing(File(glibcEtc, "nsswitch.conf"), "passwd: files\ngroup: files\nhosts: files dns\n")
        writeIfMissing(File(glibcEtc, "hosts"), "127.0.0.1 localhost\n::1 localhost\n")

        // Also write to prefix/etc for legacy compatibility
        val prefixEtc = File(config.prefix, "etc").also { it.mkdirs() }
        writeIfMissing(File(prefixEtc, "resolv.conf"), DNS_SERVERS)

        AppLogger.d(TAG, "DNS configured in ${glibcEtc.absolutePath}")
    }

    fun setupSsl(config: EnvironmentConfig, context: Context? = null) {
        val sslDir = File(config.payloadDir, "ssl").also { it.mkdirs() }
        val certPem = File(sslDir, "cert.pem")

        if (certPem.exists() && certPem.length() > 1000) {
            AppLogger.d(TAG, "SSL cert bundle already present: ${certPem.length()} bytes")
            return
        }

        // Try to copy from assets first
        if (context != null) {
            val assetPaths = listOf("certs/cert.pem", "cert.pem")
            for (assetPath in assetPaths) {
                try {
                    context.assets.open(assetPath).use { input ->
                        certPem.outputStream().use { output -> input.copyTo(output) }
                    }
                    AppLogger.i(TAG, "SSL cert bundle copied from assets: ${certPem.length()} bytes")
                    return
                } catch (_: Exception) {}
            }
        }

        // Build from Android system certs
        val androidCerts = File("/system/etc/security/cacerts")
        if (androidCerts.isDirectory) {
            var count = 0
            androidCerts.listFiles()
                ?.filter { it.name.endsWith(".0") }
                ?.forEach { cert ->
                    try {
                        val content = cert.readText()
                        if (content.contains("BEGIN CERTIFICATE")) {
                            certPem.appendText(content)
                            count++
                        }
                    } catch (_: Exception) {}
                }
            AppLogger.i(TAG, "SSL cert bundle built from Android system: $count certs")
        } else {
            AppLogger.w(TAG, "Android system certs not found — HTTPS may fail")
        }
    }

    private fun writeIfMissing(file: File, content: String) {
        try {
            if (!file.exists() || file.length() == 0L) {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not write ${file.name}: ${e.message}")
        }
    }
}
