package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.CommandRunner
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.core.env.EnvironmentResolver
import com.openclaw.android.core.install.VersionReader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ToolsBridge — WebView ↔ Kotlin bridge for optional tool management.
 *
 * Handles: listing installed tools, installing/uninstalling CLI tools,
 * and querying the runtime environment (node, git, openclaw versions).
 */
class ToolsBridge(
    private val activity: MainActivity,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
    private val ioScope: CoroutineScope,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "ToolsBridge"
        private const val PROGRESS_START = 0f
        private const val PROGRESS_HALF = 0.5f
        private const val PROGRESS_DONE = 1f
        private const val CMD_TIMEOUT_MS = 5_000L
    }

    private fun launchIO(errorEvent: String = "error", block: suspend CoroutineScope.() -> Unit) {
        val handler = CoroutineExceptionHandler { _, t ->
            AppLogger.e(TAG, "Coroutine error [$errorEvent]: ${t.message}", t)
            eventBridge.emit(errorEvent, mapOf("error" to (t.message ?: "Unknown error"), "progress" to PROGRESS_START))
        }
        ioScope.launch(handler, block = block)
    }

    @JavascriptInterface
    fun getInstalledTools(): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)
        val tools = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()

        // Check prefix bin dirs
        val prefixesToCheck = linkedSetOf(config.prefix.absolutePath, CommandRunner.TERMUX_PREFIX)
        for (prefix in prefixesToCheck) {
            val binContents = java.io.File("$prefix/bin").list()?.toSet() ?: emptySet()
            val pkgChecks = mapOf(
                "tmux" to "tmux", "ttyd" to "ttyd", "dufs" to "dufs",
                "openssh-server" to "sshd", "android-tools" to "adb", "code-server" to "code-server",
            )
            for ((id, bin) in pkgChecks) {
                if (!seen.contains(id) && binContents.contains(bin)) {
                    tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
                    seen.add(id)
                }
            }
            if (!seen.contains("chromium") && (binContents.contains("chromium-browser") || binContents.contains("chromium"))) {
                tools.add(mapOf("id" to "chromium", "name" to "chromium", "version" to "installed"))
                seen.add("chromium")
            }
        }

        // Check npm global bins
        val ocaBin = java.io.File(config.ocaDir, "bin")
        val npmBinChecks = mapOf(
            "claude-code" to "claude", "gemini-cli" to "gemini",
            "codex-cli" to "codex", "opencode" to "opencode",
        )
        val ocaBinContents = ocaBin.list()?.toSet() ?: emptySet()
        for ((id, bin) in npmBinChecks) {
            if (!seen.contains(id) && ocaBinContents.contains(bin)) {
                tools.add(mapOf("id" to id, "name" to id, "version" to "installed"))
                seen.add(id)
            }
        }

        return gson.toJson(tools)
    }

    @JavascriptInterface
    fun getEnvironmentInfo(): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)

        // ── Proot mode: read versions from rootfs files directly ──────────────
        // NEVER use CommandRunner.runSync("node -v") — /system/bin/sh cannot
        // execute glibc ELF binaries and will produce "CANNOT LINK EXECUTABLE".
        // VersionReader reads package.json files (no process spawn) and falls
        // back to GlibcRunner which uses the correct ld-linux-aarch64.so.1 loader.
        val versions = VersionReader.readAll(config)

        // For proot mode, also check rootfs paths
        val prootInstalled = java.io.File(activity.filesDir, ".proot-installed").exists()
        val nodeVersion: String
        val gitVersion: String
        val ocVersion: String

        if (prootInstalled) {
            val paths = com.openclaw.android.ProotManager.getPaths(activity)
            val rootfs = paths.rootfsDir

            // Read node version from rootfs package.json (no process spawn needed)
            val nodeFromRootfs = try {
                // Try reading from node binary version string embedded in the binary
                // Fastest: check if node exists and read version from npm's package.json
                val npmPkg = rootfs.resolve("usr/local/lib/node_modules/npm/package.json")
                if (npmPkg.exists()) {
                    // npm package.json has engines.node field
                    val content = npmPkg.readText()
                    val engines = Regex(""""node"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.getOrNull(1)
                    engines?.removePrefix(">=")?.split(" ")?.firstOrNull()?.trim()
                } else null
            } catch (_: Exception) { null }

            // Prefer VersionReader result (uses GlibcRunner or installed.json)
            nodeVersion = when {
                versions.node != "unknown" -> versions.node
                nodeFromRootfs != null -> nodeFromRootfs
                rootfs.resolve("usr/local/bin/node").exists() -> "installed"
                rootfs.resolve("usr/bin/node").exists() -> "installed"
                else -> ""
            }

            // git: check rootfs
            gitVersion = when {
                rootfs.resolve("usr/bin/git").exists() -> "installed"
                rootfs.resolve("usr/local/bin/git").exists() -> "installed"
                else -> ""
            }

            // openclaw: check rootfs
            ocVersion = when {
                versions.openclaw != "not installed" -> versions.openclaw
                rootfs.resolve("usr/local/lib/node_modules/openclaw/openclaw.mjs").exists() -> "installed"
                rootfs.resolve("usr/local/bin/openclaw").exists() -> "installed"
                else -> ""
            }
        } else {
            // Payload / legacy mode — VersionReader handles everything correctly
            nodeVersion = if (versions.node != "unknown") versions.node else ""
            gitVersion = ""  // git version not tracked in payload mode
            ocVersion = if (versions.openclaw != "not installed") versions.openclaw else ""
        }

        AppLogger.d(TAG, "getEnvironmentInfo: node=$nodeVersion git=$gitVersion openclaw=$ocVersion proot=$prootInstalled")

        // npm version — read from package.json (no process spawn)
        val npmVersion = versions.npm

        return gson.toJson(mapOf(
            "node" to mapOf(
                "version" to nodeVersion.ifEmpty { null },
                "detected" to nodeVersion.isNotEmpty(),
                "path" to "${config.ocaDir.absolutePath}/bin/node",
            ),
            "npm" to mapOf(
                "version" to npmVersion.ifEmpty { null },
                "detected" to (npmVersion != "unknown" && npmVersion.isNotEmpty()),
                "path" to "${config.ocaDir.absolutePath}/bin/npm",
            ),
            "git" to mapOf(
                "version" to gitVersion.ifEmpty { null },
                "detected" to gitVersion.isNotEmpty(),
                "path" to "${config.prefix.absolutePath}/bin/git",
            ),
            "openclaw" to mapOf(
                "version" to ocVersion.ifEmpty { null },
                "detected" to ocVersion.isNotEmpty(),
                "path" to "${config.prefix.absolutePath}/bin/openclaw",
            ),
            "prefix" to config.prefix.absolutePath,
            "home" to config.homeDir.absolutePath,
        ))
    }

    @JavascriptInterface
    fun installTool(id: String) {
        launchIO(errorEvent = "install_progress") {
            val env = CommandRunner.buildTermuxEnv(activity)
            val config = EnvironmentResolver.resolve(activity.filesDir)
            val prefix = config.prefix.absolutePath

            // Determine install method: npm for AI tools, apt-get for system tools
            // apt-get path: try payload prefix first, then system
            val aptGet = listOf(
                "$prefix/bin/apt-get",
                "/usr/bin/apt-get",
            ).firstOrNull { java.io.File(it).exists() }?.let { apt ->
                "$apt -y -o Acquire::AllowInsecureRepositories=true -o APT::Get::AllowUnauthenticated=true"
            } ?: "apt-get -y"

            val cmd = when (id) {
                "tmux" -> "$aptGet install tmux"
                "ttyd" -> "$aptGet install ttyd"
                "dufs" -> "$aptGet install dufs"
                "android-tools" -> "$aptGet install android-tools"
                "openssh-server" -> "$aptGet install openssh"
                "claude-code" -> "npm install -g @anthropic-ai/claude-code --ignore-scripts"
                "gemini-cli" -> "npm install -g @google/gemini-cli --ignore-scripts"
                "codex-cli" -> "npm install -g @openai/codex --ignore-scripts"
                "opencode" -> "npm install -g opencode-ai --ignore-scripts"
                else -> {
                    eventBridge.emit("install_progress", mapOf("target" to id, "progress" to PROGRESS_START, "message" to "Unknown tool: $id"))
                    return@launchIO
                }
            }

            eventBridge.emit("install_progress", mapOf("target" to id, "progress" to PROGRESS_START, "message" to "Installing $id..."))
            val result = CommandRunner.runStreaming(cmd, env, config.homeDir) { output ->
                eventBridge.emit("install_progress", mapOf("target" to id, "progress" to PROGRESS_HALF, "message" to output))
            }
            val success = result.exitCode == 0
            eventBridge.emit("install_progress", mapOf(
                "target" to id,
                "progress" to if (success) PROGRESS_DONE else PROGRESS_START,
                "message" to if (success) "$id installed" else (result.stderr.take(200).ifEmpty { "Install failed (exit ${result.exitCode})" }),
            ))
        }
    }

    @JavascriptInterface
    fun uninstallTool(id: String) {
        launchIO(errorEvent = "install_progress") {
            val env = CommandRunner.buildTermuxEnv(activity)
            val config = EnvironmentResolver.resolve(activity.filesDir)
            val prefix = config.prefix.absolutePath
            val aptGet = "DEBIAN_FRONTEND=noninteractive $prefix/bin/apt-get -y"

            val cmd = when (id) {
                "tmux", "ttyd", "dufs", "android-tools", "openssh-server" -> "$aptGet remove $id"
                "claude-code" -> "npm uninstall -g @anthropic-ai/claude-code"
                "gemini-cli" -> "npm uninstall -g @google/gemini-cli"
                "codex-cli" -> "npm uninstall -g @openai/codex"
                "opencode" -> "npm uninstall -g opencode-ai"
                else -> return@launchIO
            }

            CommandRunner.runSync(cmd, env, config.homeDir)
            eventBridge.emit("install_progress", mapOf("target" to id, "progress" to PROGRESS_DONE, "message" to "$id uninstalled"))
        }
    }

    @JavascriptInterface
    fun isToolInstalled(id: String): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)
        val prefixesToCheck = linkedSetOf(config.prefix.absolutePath, CommandRunner.TERMUX_PREFIX)
        
        // System tools
        val pkgChecks: Map<String, String> = mapOf(
            "tmux" to "tmux", "ttyd" to "ttyd", "dufs" to "dufs",
            "openssh-server" to "sshd", "android-tools" to "adb", "code-server" to "code-server",
        )
        
        val systemBin = pkgChecks.get(id)
        if (systemBin != null) {
            if (prefixesToCheck.any { p: String -> java.io.File("$p/bin/$systemBin").exists() }) return "true"
        }
        
        if (id == "chromium") {
            if (prefixesToCheck.any { p: String -> java.io.File("$p/bin/chromium").exists() || java.io.File("$p/bin/chromium-browser").exists() }) return "true"
        }

        // NPM tools
        val npmBinChecks: Map<String, String> = mapOf(
            "claude-code" to "claude", "gemini-cli" to "gemini",
            "codex-cli" to "codex", "opencode" to "opencode",
        )
        val npmBin = npmBinChecks.get(id)
        if (npmBin != null) {
            if (java.io.File(config.ocaDir, "bin/$npmBin").exists()) return "true"
        }
        
        return "false"
    }
}
