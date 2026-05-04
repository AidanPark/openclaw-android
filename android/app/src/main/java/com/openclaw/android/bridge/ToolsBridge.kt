package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.CommandRunner
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.core.env.EnvironmentResolver
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
        val env = CommandRunner.buildTermuxEnv(activity)
        val config = EnvironmentResolver.resolve(activity.filesDir)

        fun runV(cmd: String): String {
            val r = CommandRunner.runSync(cmd, env, config.homeDir, timeoutMs = CMD_TIMEOUT_MS)
            return r.stdout.trim().ifEmpty { r.stderr.trim() }
        }

        val nodeRaw = runV("node -v 2>/dev/null || node --version 2>/dev/null")
        val gitRaw = runV("git --version 2>/dev/null")
        val ocRaw = runV("openclaw --version 2>/dev/null")

        return gson.toJson(mapOf(
            "node" to mapOf(
                "version" to nodeRaw.ifEmpty { null },
                "detected" to nodeRaw.isNotEmpty(),
                "path" to "${config.ocaDir.absolutePath}/bin/node",
            ),
            "git" to mapOf(
                "version" to gitRaw.replace("git version ", "").ifEmpty { null },
                "detected" to gitRaw.isNotEmpty(),
                "path" to "${config.prefix.absolutePath}/bin/git",
            ),
            "openclaw" to mapOf(
                "version" to ocRaw.ifEmpty { null },
                "detected" to ocRaw.isNotEmpty(),
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
            val aptGet = "DEBIAN_FRONTEND=noninteractive $prefix/bin/apt-get" +
                " -y -o Acquire::AllowInsecureRepositories=true" +
                " -o APT::Get::AllowUnauthenticated=true"

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
                "message" to if (success) "$id installed" else result.stderr,
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
}
