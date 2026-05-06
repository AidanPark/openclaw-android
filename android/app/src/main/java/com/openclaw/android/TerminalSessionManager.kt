package com.openclaw.android

import com.openclaw.android.core.env.EnvironmentResolver
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Manages multiple terminal sessions.
 * One TerminalView, many sessions — switch via attachSession().
 *
 * Shell selection priority:
 *   1. termux-bootstrap: bash from Termux Bootstrap installation (default mode)
 *   2. proot mode: openclaw-shell.sh (bash inside Ubuntu rootfs via proot) — advanced/optional
 *   3. online install: bash from prefix/bin/bash with full glibc env
 *   4. payload mode: glibc-wrapped bash from payload
 *   5. fallback: /system/bin/sh (limited, no glibc tools)
 */
class TerminalSessionManager(
    private val activity: MainActivity,
    private val sessionClient: TerminalSessionClient,
    private val eventBridge: EventBridge,
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val TRANSCRIPT_ROWS = 2000
    }

    private val sessions = mutableListOf<TerminalSession>()
    private var activeSessionIndex = -1
    private val finishedSessionIds = mutableSetOf<String>()
    var onSessionsChanged: (() -> Unit)? = null

    val activeSession: TerminalSession?
        get() = sessions.getOrNull(activeSessionIndex)

    fun createSession(): TerminalSession {
        val base = activity.filesDir.absolutePath
        val homeDir = File(base, "home").also { it.mkdirs() }
        val tmpDir = File(base, "tmp").also { it.mkdirs() }

        val session = buildSession(base, homeDir, tmpDir)
        sessions.add(session)
        switchSession(sessions.size - 1)
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
        return session
    }

    /**
     * Selects the best available shell and builds a TerminalSession.
     */
    private fun buildSession(base: String, homeDir: File, tmpDir: File): TerminalSession {
        val config = EnvironmentResolver.resolve(activity)
        val envMap = EnvironmentResolver.buildEnvMap(config, activity.packageName).toMutableMap()
        
        // Ensure directories exist
        config.homeDir.mkdirs()
        config.tmpDir.mkdirs()
        config.prefix.resolve("etc").mkdirs()

        // ── 1. Termux Bootstrap mode (default) ──────────────────────────────
        val termuxBash = File(config.prefix, "bin/bash")
        val bootstrapInstalled = File(config.filesDir, ".termux-bootstrap-installed").exists()
        
        if (bootstrapInstalled && termuxBash.exists() && termuxBash.canExecute()) {
            AppLogger.i(TAG, "Termux Bootstrap mode: using ${termuxBash.absolutePath}")

            // Fix: bash has /data/data/com.termux/files/usr hardcoded.
            val etcDir = File(config.prefix, "etc")
            val bashRcFile = File(etcDir, "bash.bashrc")
            bashRcFile.writeText(buildString {
                appendLine("# OpenClaw bash.bashrc")
                appendLine("export PREFIX=\"${config.prefix.absolutePath}\"")
                appendLine("export HOME=\"${config.homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${config.tmpDir.absolutePath}\"")
                appendLine("export PATH=\"${envMap["PATH"]}\"")
                appendLine("export LD_LIBRARY_PATH=\"${config.prefix.absolutePath}/lib\"")
                appendLine("export LANG=en_US.UTF-8")
                appendLine("export TERM=xterm-256color")
            })

            // .bashrc in homeDir — user prompt and aliases
            val homeBashRc = File(config.homeDir, ".bashrc")
            homeBashRc.writeText(buildString {
                appendLine("# OpenClaw .bashrc — sourced via --init-file")
                appendLine("export HOME=\"${config.homeDir.absolutePath}\"")
                appendLine("export PREFIX=\"${config.prefix.absolutePath}\"")
                appendLine("export TMPDIR=\"${config.tmpDir.absolutePath}\"")
                appendLine("export PATH=\"${config.homeDir.absolutePath}/.openclaw-android/bin:${envMap["PATH"]}\"")
                appendLine("export LD_LIBRARY_PATH=\"${config.prefix.absolutePath}/lib\"")
                appendLine("export LANG=en_US.UTF-8")
                appendLine("export TERM=xterm-256color")
                appendLine("export PS1='\\$ '")
                appendLine("alias ls='ls --color=auto'")
                appendLine("alias ll='ls -la'")
                appendLine("cd \"${config.homeDir.absolutePath}\"")
            })

            // CRITICAL: Clear etc/ld.so.preload to prevent signal 1 crash (hardcoded com.termux)
            val ldSoPreload = File(etcDir, "ld.so.preload")
            if (ldSoPreload.exists()) {
                try {
                    ldSoPreload.writeText("")
                } catch (_: Exception) {}
            }

            val envArray = envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray()

            return TerminalSession(
                termuxBash.absolutePath,
                config.homeDir.absolutePath,
                arrayOf("bash", "--norc", "--noprofile", "--init-file", homeBashRc.absolutePath, "-i"),
                envArray,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 2. Proot Ubuntu mode (advanced/optional) ─────────────────────────
        val prootShellScript = File(config.homeDir, "openclaw-shell.sh")
        if (File(config.filesDir, ".proot-installed").exists() &&
            prootShellScript.exists() && prootShellScript.canExecute()
        ) {
            AppLogger.i(TAG, "Proot mode: using openclaw-shell.sh")
            return TerminalSession(
                prootShellScript.absolutePath,
                config.homeDir.absolutePath,
                arrayOf("openclaw-shell.sh"),
                arrayOf(
                    "HOME=${config.homeDir.absolutePath}",
                    "TMPDIR=${config.tmpDir.absolutePath}",
                    "TERM=xterm-256color",
                    "LANG=en_US.UTF-8",
                    "PROOT_NO_SECCOMP=1",
                    "PROOT_TMP_DIR=${activity.cacheDir.absolutePath}",
                ),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 3. Fallback to best available shell ─────────────────────────────
        val shellBin = listOf(
            File(config.prefix, "bin/bash").absolutePath,
            File(config.prefix, "bin/sh").absolutePath,
            "/system/bin/sh",
        ).firstOrNull { File(it).exists() } ?: "/system/bin/sh"

        AppLogger.i(TAG, "Default mode: shell=$shellBin")

        if (shellBin.endsWith("/bash")) {
            val homeBashRc = File(config.homeDir, ".bashrc")
            if (!homeBashRc.exists()) {
                homeBashRc.writeText(buildString {
                    appendLine("export HOME=\"${config.homeDir.absolutePath}\"")
                    appendLine("export PREFIX=\"${config.prefix.absolutePath}\"")
                    appendLine("export TMPDIR=\"${config.tmpDir.absolutePath}\"")
                    appendLine("export PATH=\"${envMap["PATH"]}\"")
                    appendLine("export LD_LIBRARY_PATH=\"${config.prefix.absolutePath}/lib\"")
                    appendLine("export LANG=en_US.UTF-8")
                    appendLine("export TERM=xterm-256color")
                    appendLine("export PS1='\\$ '")
                    appendLine("cd \"${config.homeDir.absolutePath}\"")
                })
            }
            return TerminalSession(
                shellBin,
                config.homeDir.absolutePath,
                arrayOf("bash", "--norc", "--noprofile", "--init-file", homeBashRc.absolutePath, "-i"),
                envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // Fallback banner for system shell
        val bannerLines = listOf(
            "OpenClaw Android - Terminal",
            "No environment installed.",
            "Go to Dashboard -> Setup to install the runtime.",
        )
        val bannerCmd = bannerLines.joinToString("\\n") { "  $it" }.let { "printf '\\n$it\\n\\n'" }

        if (shellBin == "/system/bin/sh") {
            envMap.remove("LD_PRELOAD")
            envMap.remove("LD_LIBRARY_PATH")
        }

        val session = TerminalSession(
            shellBin,
            config.homeDir.absolutePath,
            arrayOf(if (shellBin.endsWith("/sh")) "sh" else shellBin, "-i"),
            envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            sessionClient,
        )

        if (shellBin == "/system/bin/sh") {
            activity.runOnUiThread {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    session.write("$bannerCmd\n")
                }, 300)
            }
        }

        return session
    }

    fun switchSession(index: Int) {
        if (index < 0 || index >= sessions.size) return
        activeSessionIndex = index
        val session = sessions[index]
        activity.runOnUiThread {
            val terminalView = activity.findViewById<com.termux.view.TerminalView>(R.id.terminalView)
            terminalView.attachSession(session)
            terminalView.invalidate()
        }
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "switched"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    fun switchSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index >= 0) switchSession(index)
    }

    fun getSessionById(handleId: String): TerminalSession? =
        sessions.find { it.mHandle == handleId }

    fun closeSession(handleId: String) {
        val index = sessions.indexOfFirst { it.mHandle == handleId }
        if (index < 0) return

        finishedSessionIds.remove(handleId)
        val removedSession = sessions.removeAt(index)
        removedSession.finishIfRunning()

        eventBridge.emit("session_changed", mapOf("id" to handleId, "action" to "closed"))

        if (sessions.isNotEmpty()) {
            switchSession(index.coerceAtMost(sessions.size - 1))
        } else {
            activeSessionIndex = -1
        }

        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    fun onSessionFinished(session: TerminalSession) {
        finishedSessionIds.add(session.mHandle)
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "finished"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
    }

    fun getSessionsInfo(): List<Map<String, Any>> =
        sessions.mapIndexed { index, session ->
            mapOf(
                "id" to session.mHandle,
                "name" to (session.title ?: "Session ${index + 1}"),
                "active" to (index == activeSessionIndex),
                "finished" to (session.mHandle in finishedSessionIds),
            )
        }

    fun isSessionFinished(handleId: String): Boolean = handleId in finishedSessionIds

    val sessionCount: Int get() = sessions.size
}
