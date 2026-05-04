package com.openclaw.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Installs and updates the minimum runtime needed for OpenClaw.
 *
 * Core contract:
 *   1. Ensure npm exists.
 *   2. npm install -g openclaw@latest with scripts disabled.
 *   3. Regenerate app-local launch wrappers.
 *   4. Mark OpenClaw installed only after the package is actually present.
 */
class OpenClawManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenClawManager"
        private const val NODE_MAJOR = 24
        private const val NODE_RELEASE_BASE = "https://nodejs.org/dist/latest-v24.x"
    }

    private val filesDir: File = context.filesDir
    private val prefixDir: File = File(filesDir, "usr")
    private val homeDir: File = File(filesDir, "home")
    private val ocaDir: File = File(homeDir, ".openclaw-android")

    /**
     * Install or update OpenClaw.
     * Returns Pair(success, errorMessage). errorMessage is null on success.
     */
    suspend fun installOrUpdate(onProgress: (Int, String) -> Unit): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            homeDir.mkdirs()
            ocaDir.mkdirs()

            // Already installed and valid — skip
            if (isInstalled() && isMarkerValid()) {
                onProgress(100, "OpenClaw already installed and verified")
                AppLogger.i(TAG, "Already installed, skipping.")
                return@withContext Pair(true, null)
            }

            onProgress(0, "Preparing minimal OpenClaw runtime...")
            val env = CommandRunner.buildTermuxEnv(context).toMutableMap()

            // Node.js installation
            if (!hasUsableNpm(env)) {
                onProgress(10, "Installing Node.js $NODE_MAJOR runtime...")
                val runtimeOk = installOfficialNode(onProgress) ||
                    runStreamingStep(
                        "pkg install -y nodejs git || apt-get install -y nodejs git",
                        env, homeDir, onProgress, 10, 35,
                    )
                if (!runtimeOk) return@withContext Pair(false, "Node.js runtime installation failed")
            } else {
                onProgress(35, "Node.js runtime already available")
            }

            // Verify npm registry
            onProgress(35, "Verifying network connectivity...")
            env["NPM_CONFIG_REGISTRY"] = "https://registry.npmmirror.com/"
            val registryOk = verifyNpmRegistry(onProgress, env)
            if (!registryOk) {
                onProgress(35, "Mirror unreachable — trying official registry...")
                env["NPM_CONFIG_REGISTRY"] = "https://registry.npmjs.org/"
                if (!verifyNpmRegistry(onProgress, env)) {
                    return@withContext Pair(false, "npm registry unreachable — check internet/DNS")
                }
            }

            // Install OpenClaw with retry
            onProgress(40, "Installing OpenClaw latest (attempt 1/3)...")
            val installOk = runStreamingWithRetry(
                "npm install -g openclaw@latest --ignore-scripts --no-fund --no-audit",
                env, homeDir, onProgress, 40, 82, maxRetries = 3,
            )
            if (!installOk) return@withContext Pair(false, "npm install openclaw failed — check network or disk space")

            onProgress(84, "Restoring lightweight OpenClaw dependencies...")
            restoreBundledPluginDependencies(env, onProgress)

            onProgress(90, "Creating launch wrappers...")
            CommandRunner.createWrapperScript(filesDir)

            if (!isInstalled()) {
                AppLogger.e(TAG, "OpenClaw verification failed after npm install")
                return@withContext Pair(false, "OpenClaw binary not found after installation")
            }

            writeInstalledMarker()

            onProgress(95, "Activating environment...")
            try {
                CommandRunner.createWrapperScript(filesDir)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Activation minor error: ${e.message}")
            }

            onProgress(100, "OpenClaw installed and activated")
            Pair(true, null)
        }

    fun isInstalled(): Boolean =
        File(prefixDir, "bin/openclaw").exists() ||
            File(prefixDir, "lib/node_modules/openclaw/openclaw.mjs").exists()

    private fun isMarkerValid(): Boolean {
        val marker = File(ocaDir, "installed.json")
        return marker.exists() && marker.length() > 10
    }

    private fun hasUsableNpm(env: Map<String, String>): Boolean {
        val hasNpm = File(prefixDir, "bin/npm").exists() ||
            File(ocaDir, "node/bin/npm").exists() ||
            File(ocaDir, "bin/npm").exists()
        if (!hasNpm) return false
        val result = CommandRunner.runSync("node -v", env, homeDir, timeoutMs = 5_000)
        val version = result.stdout.trim().removePrefix("v")
        return isNodeVersionSupported(version)
    }

    private fun isNodeVersionSupported(version: String): Boolean {
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > 22 || (major == 22 && minor >= 14)
    }

    private fun installOfficialNode(onProgress: (Int, String) -> Unit): Boolean {
        val nodeDir = File(ocaDir, "node")
        val tarFile = File(context.cacheDir, "node-linux-arm64.tar.xz")
        return try {
            onProgress(12, "Resolving latest Node.js $NODE_MAJOR...")

            var shasums: String? = null
            val shasumsUrl = "$NODE_RELEASE_BASE/SHASUMS256.txt"
            for (i in 1..2) {
                try {
                    val conn = URL(shasumsUrl).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    shasums = conn.inputStream.bufferedReader().use { it.readText() }
                    if (!shasums.isNullOrBlank()) break
                } catch (e: Exception) {
                    AppLogger.w(TAG, "SHASUMS fetch attempt $i failed: ${e.message}")
                    Thread.sleep(3_000)
                }
            }

            val tarName = if (!shasums.isNullOrBlank()) {
                Regex("""node-v$NODE_MAJOR\.[^\s]+-linux-arm64\.tar\.xz""")
                    .find(shasums)?.value ?: "node-v24.1.0-linux-arm64.tar.xz"
            } else {
                "node-v24.1.0-linux-arm64.tar.xz"
            }

            onProgress(16, "Downloading $tarName...")
            val conn = URL("$NODE_RELEASE_BASE/$tarName")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                tarFile.outputStream().use { output -> input.copyTo(output) }
            }

            if (!tarFile.exists() || tarFile.length() == 0L) {
                throw IllegalStateException("Downloaded tarball is empty")
            }

            onProgress(28, "Extracting Node.js runtime...")
            nodeDir.deleteRecursively()
            nodeDir.mkdirs()

            val process = ProcessBuilder(
                "/system/bin/sh", "-c",
                "/system/bin/tar -xJf \"${tarFile.absolutePath}\" -C \"${nodeDir.absolutePath}\" --strip-components=1 2>&1",
            ).apply {
                environment()["PATH"] = "/system/bin:/bin"
            }.start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                AppLogger.e(TAG, "Node extraction failed ($exitCode): $output")
                false
            } else {
                File(nodeDir, "bin/node").setExecutable(true, false)
                File(nodeDir, "bin/npm").setExecutable(true, false)
                File(nodeDir, "bin/npx").setExecutable(true, false)
                onProgress(35, "Node.js runtime ready")
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Official Node install failed: ${e.message}", e)
            false
        } finally {
            if (tarFile.exists()) tarFile.delete()
        }
    }

    private suspend fun runStreamingStep(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onProgress: (Int, String) -> Unit,
        start: Int,
        end: Int,
    ): Boolean {
        var ticks = 0
        val result = CommandRunner.runStreaming(command, env, workDir) { line ->
            ticks++
            val pct = (start + (ticks % 12) * ((end - start).coerceAtLeast(1) / 12.0))
                .toInt().coerceIn(start, end)
            onProgress(pct, line)
        }
        if (result.exitCode != 0) {
            onProgress(start, result.stderr.ifBlank { "Command failed: $command" })
            AppLogger.e(TAG, "Command failed ($command): ${result.stderr}")
            return false
        }
        return true
    }

    private suspend fun runStreamingWithRetry(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onProgress: (Int, String) -> Unit,
        start: Int,
        end: Int,
        maxRetries: Int = 3,
    ): Boolean {
        var delaySec = 5
        for (attempt in 1..maxRetries) {
            onProgress(start, "Installing OpenClaw latest (attempt $attempt/$maxRetries)...")
            if (runStreamingStep(command, env, workDir, onProgress, start, end)) return true
            if (attempt < maxRetries) {
                onProgress(start, "Retrying in ${delaySec}s...")
                Thread.sleep(delaySec * 1_000L)
                delaySec *= 2
                File(ocaDir, ".npm/_cacache/tmp").deleteRecursively()
            }
        }
        return false
    }

    private fun verifyNpmRegistry(onProgress: (Int, String) -> Unit, env: Map<String, String>): Boolean {
        val registry = env["NPM_CONFIG_REGISTRY"] ?: "https://registry.npmjs.org/"
        for (i in 1..2) {
            try {
                onProgress(36, "Checking $registry (attempt $i)...")
                val result = CommandRunner.runSync(
                    "curl -fsSL --noproxy '*' --connect-timeout 15 --retry 1 $registry",
                    env, homeDir, timeoutMs = 25_000,
                )
                if (result.exitCode == 0) return true
                if (i < 2) Thread.sleep(3_000)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Registry check $i failed: ${e.message}")
                if (i < 2) Thread.sleep(3_000)
            }
        }
        return false
    }

    private suspend fun restoreBundledPluginDependencies(
        env: Map<String, String>,
        onProgress: (Int, String) -> Unit,
    ) {
        val openClawDir = File(prefixDir, "lib/node_modules/openclaw")
        val postInstall = File(openClawDir, "scripts/postinstall-bundled-plugins.mjs")
        if (!postInstall.exists()) return
        runStreamingStep(
            "cd \"${openClawDir.absolutePath}\" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs",
            env, homeDir, onProgress, 84, 89,
        )
    }

    private fun writeInstalledMarker() {
        val version = readOpenClawVersion()
        val marker = File(ocaDir, "installed.json")
        marker.parentFile?.mkdirs()
        marker.writeText(
            """{"installed":true,"source":"openclaw-manager","version":"$version","prefix":"${prefixDir.absolutePath}","home":"${homeDir.absolutePath}"}"""
        )
    }

    private fun readOpenClawVersion(): String {
        val pkg = File(prefixDir, "lib/node_modules/openclaw/package.json")
        if (!pkg.exists()) return "unknown"
        return Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg.readText())
            ?.groupValues?.getOrNull(1) ?: "unknown"
    }
}
