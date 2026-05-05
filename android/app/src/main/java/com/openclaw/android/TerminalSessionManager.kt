package com.openclaw.android

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
 *
 * Termux Bootstrap is checked first because it is the default installation mode.
 * Proot is only used when the user explicitly chose the advanced proot installation.
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
     *
     * Priority order (default first, advanced optional):
     *   1. Termux Bootstrap — default mode, native Termux environment
     *   2. Proot Ubuntu — advanced/optional mode, only if explicitly activated
     *   3. Online install — curl | bash layout
     *   4. Payload / legacy mode
     *   5. Fallback — /system/bin/sh (no environment installed)
     *
     * Proot is checked AFTER Termux Bootstrap because it is an optional advanced
     * mode that the user must explicitly choose. Termux Bootstrap is the default.
     */
    private fun buildSession(base: String, homeDir: File, tmpDir: File): TerminalSession {

        // ── 1. Termux Bootstrap mode (default) ──────────────────────────────
        val termuxPrefix = File(base, "usr")
        val termuxBash = File(termuxPrefix, "bin/bash")
        if (File(base, ".termux-bootstrap-installed").exists() &&
            termuxBash.exists() && termuxBash.canExecute() &&
            File(termuxPrefix, "bin/dpkg").exists() &&
            File(termuxPrefix, "bin/apt").exists()
        ) {
            AppLogger.i(TAG, "Termux Bootstrap mode: using ${termuxBash.absolutePath}")

            // Ensure bash.bashrc exists at the real path — bash is compiled with
            // /data/data/com.termux/files/usr hardcoded, so it looks for bash.bashrc
            // there. We create it at the actual prefix path so bash finds it.
            val etcDir = File(termuxPrefix, "etc").also { it.mkdirs() }
            val bashRcFile = File(etcDir, "bash.bashrc")
            if (!bashRcFile.exists()) {
                bashRcFile.writeText(buildString {
                    appendLine("# OpenClaw bash.bashrc — auto-generated")
                    appendLine("export PREFIX=\"${termuxPrefix.absolutePath}\"")
                    appendLine("export HOME=\"${homeDir.absolutePath}\"")
                    appendLine("export TMPDIR=\"${tmpDir.absolutePath}\"")
                    appendLine("export PATH=\"${termuxPrefix.absolutePath}/bin:${termuxPrefix.absolutePath}/bin/applets:/system/bin:/bin\"")
                    appendLine("export LD_LIBRARY_PATH=\"${termuxPrefix.absolutePath}/lib\"")
                    appendLine("export LANG=en_US.UTF-8")
                    appendLine("export TERM=xterm-256color")
                })
                AppLogger.i(TAG, "Created bash.bashrc at ${bashRcFile.absolutePath}")
            }
            // Ensure .bashrc exists in homeDir
            val homeBashRc = File(homeDir, ".bashrc")
            if (!homeBashRc.exists()) {
                homeBashRc.writeText("export PS1='\\u@openclaw:\\w\\$ '\nalias ls='ls --color=auto'\n")
            }

            // Include ~/.openclaw-android/bin in PATH so node/openclaw wrappers
            // installed by the payload are accessible from the terminal.
            val ocaBin = File(homeDir, ".openclaw-android/bin").absolutePath

            // Resolve payload glibc lib dir for LD_LIBRARY_PATH (needed by node)
            val payloadGlibcLib = listOf(
                File(homeDir, "payload/glibc/lib"),
                File(homeDir, "openclaw-payload/glibc/lib"),
            ).firstOrNull { it.isDirectory }?.absolutePath
            val ldLibPath = buildString {
                append("${termuxPrefix.absolutePath}/lib")
                if (payloadGlibcLib != null) append(":$payloadGlibcLib")
            }

            // libtermux-exec-ld-preload.so redirects hardcoded Termux paths
            // (/data/data/com.termux/files) to the real app sandbox path at
            // runtime. Without it, bash reads bash.bashrc from the wrong path
            // and crashes with "Permission denied" (signal 1).
            // TERMUX_APP__DATA_DIR tells the library where to redirect to.
            val termuxExecLdPreload = File(termuxPrefix, "lib/libtermux-exec-ld-preload.so")
            val termuxExecLinkerLdPreload = File(termuxPrefix, "lib/libtermux-exec-linker-ld-preload.so")
            val ldPreload = when {
                termuxExecLdPreload.exists()       -> termuxExecLdPreload.absolutePath
                termuxExecLinkerLdPreload.exists() -> termuxExecLinkerLdPreload.absolutePath
                else                               -> null
            }
            AppLogger.i(TAG, "LD_PRELOAD: $ldPreload")

            val env = mutableListOf(
                "HOME=${homeDir.absolutePath}",
                "PREFIX=${termuxPrefix.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=$ocaBin:${termuxPrefix.absolutePath}/bin:${termuxPrefix.absolutePath}/bin/applets:/system/bin:/bin",
                "LD_LIBRARY_PATH=$ldLibPath",
                "PACKAGE_MANAGER=apt",
                "TERMUX_VERSION=1.0",
                "TERMUX_APP_PID=${android.os.Process.myPid()}",
                "DEBIAN_FRONTEND=noninteractive",
                "DEBCONF_NONINTERACTIVE_SEEN=true",
                // Required by libtermux-exec: tells it the real app data dir
                // so it can redirect /data/data/com.termux/files → this path.
                "TERMUX_APP__DATA_DIR=${activity.filesDir.parentFile?.absolutePath ?: activity.filesDir.absolutePath}",
                "TERMUX_APP__PACKAGE_NAME=${activity.packageName}",
            )
            if (ldPreload != null) {
                env.add("LD_PRELOAD=$ldPreload")
            }

            return TerminalSession(
                termuxBash.absolutePath,
                homeDir.absolutePath,
                // --norc: skip /data/data/com.termux/files/usr/etc/bash.bashrc
                //         (hardcoded Termux path, inaccessible from this app)
                // --noprofile: skip /data/data/com.termux/files/usr/etc/profile
                // We source our own .bashrc from HOME via ENV instead.
                arrayOf("bash", "--norc", "--noprofile", "-i"),
                env.toTypedArray(),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 2. Proot Ubuntu mode (advanced/optional) ─────────────────────────
        // Only activated when the user explicitly chose proot installation.
        val prootShellScript = File(homeDir, "openclaw-shell.sh")
        if (File(activity.filesDir, ".proot-installed").exists() &&
            prootShellScript.exists() && prootShellScript.canExecute()
        ) {
            AppLogger.i(TAG, "Proot mode: using openclaw-shell.sh")
            return TerminalSession(
                prootShellScript.absolutePath,
                homeDir.absolutePath,
                arrayOf("openclaw-shell.sh"),
                arrayOf(
                    "HOME=${homeDir.absolutePath}",
                    "TMPDIR=${tmpDir.absolutePath}",
                    "TERM=xterm-256color",
                    "LANG=en_US.UTF-8",
                    "PROOT_NO_SECCOMP=1",
                    "PROOT_TMP_DIR=${activity.cacheDir.absolutePath}",
                ),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 3. Online install mode ───────────────────────────────────────────
        // Layout left by: curl -sL myopenclawhub.com/install | bash
        val onlinePrefix = File(base, "usr")
        val ocaDir = File(homeDir, ".openclaw-android")
        val bashBin = File(onlinePrefix, "bin/bash")
        val nodeReal = File(ocaDir, "node/bin/node.real")
        val glibcLdso = File(onlinePrefix, "glibc/lib/ld-linux-aarch64.so.1")
        val ocaMjs = File(onlinePrefix, "lib/node_modules/openclaw/openclaw.mjs")

        if (bashBin.exists() && bashBin.canExecute() &&
            File(ocaDir, "installed.json").exists() &&
            nodeReal.exists() && glibcLdso.exists() && ocaMjs.exists()
        ) {
            AppLogger.i(TAG, "Online install mode: using ${bashBin.absolutePath}")
            val ocaBin = File(ocaDir, "bin").absolutePath
            val nodeDir = File(ocaDir, "node/bin").absolutePath
            val glibcLib = File(onlinePrefix, "glibc/lib").absolutePath
            val certPem = File(onlinePrefix, "etc/tls/cert.pem").absolutePath
            return TerminalSession(
                bashBin.absolutePath,
                homeDir.absolutePath,
                arrayOf("bash", "-i"),
                arrayOf(
                    "HOME=${homeDir.absolutePath}",
                    "PREFIX=${onlinePrefix.absolutePath}",
                    "TMPDIR=${tmpDir.absolutePath}",
                    "PATH=$ocaBin:$nodeDir:${onlinePrefix.absolutePath}/bin:${onlinePrefix.absolutePath}/bin/applets:/system/bin:/bin",
                    "LD_LIBRARY_PATH=${onlinePrefix.absolutePath}/lib:$glibcLib",
                    "SSL_CERT_FILE=$certPem",
                    "CURL_CA_BUNDLE=$certPem",
                    "GIT_SSL_CAINFO=$certPem",
                    "OA_GLIBC=1",
                    "CONTAINER=1",
                    "TERM=xterm-256color",
                    "LANG=en_US.UTF-8",
                    "GIT_CONFIG_NOSYSTEM=1",
                    "CLAWDHUB_WORKDIR=${homeDir.absolutePath}/.openclaw/workspace",
                ),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 4. Payload / legacy mode ─────────────────────────────────────────
        val config = com.openclaw.android.core.env.EnvironmentResolver.resolve(activity.filesDir)
        val env = com.openclaw.android.core.env.EnvironmentResolver
            .buildEnvMap(config, activity.packageName)
            .toMutableMap()

        val prefixPath = env["PREFIX"] ?: "$base/usr"
        val prefix = File(prefixPath).also { if (!it.exists()) it.mkdirs() }
        env["HOME"] = homeDir.absolutePath
        env["PREFIX"] = prefix.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath

        val installerManager = InstallerManager(activity)
        val isEnvironmentReady = installerManager.isReady()
        val hasGlibcLinker = File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasTermuxExec = File(prefix, "lib/libtermux-exec.so").exists()

        val isBashWrapper = try {
            val f = File(prefix, "bin/bash")
            f.exists() && f.length() < 200 && f.readText().contains("# Emergency bash wrapper")
        } catch (_: Exception) { false }

        val isShWrapper = try {
            val f = File(prefix, "bin/sh")
            f.exists() && f.length() < 200 && f.readText().contains("# Emergency")
        } catch (_: Exception) { false }

        val mustUseSafeMode = !isEnvironmentReady ||
            (!hasGlibcLinker && !hasTermuxExec) ||
            isBashWrapper || isShWrapper

        if (mustUseSafeMode) {
            AppLogger.w(TAG, "Safe mode: envReady=$isEnvironmentReady glibc=$hasGlibcLinker termuxExec=$hasTermuxExec")
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
        }

        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !File(ldPreload).exists()) env.remove("LD_PRELOAD")

        val shellBin = if (mustUseSafeMode) {
            "/system/bin/sh"
        } else {
            listOf(
                File(prefix, "bin/bash").absolutePath,
                File(prefix, "bin/sh").absolutePath,
                "/system/bin/sh",
            ).firstOrNull { File(it).exists() } ?: "/system/bin/sh"
        }

        if (shellBin == "/system/bin/sh") {
            env.remove("LD_PRELOAD")
            // CRITICAL: never put glibc/lib in LD_LIBRARY_PATH for Bionic shells.
            // It causes: "CANNOT LINK EXECUTABLE: cannot find libc.so from verneed[0]"
            env.remove("LD_LIBRARY_PATH")
        }

        val shellArgs = if (shellBin.endsWith("/bash")) {
            arrayOf("bash", "-i", "--norc", "--noprofile")
        } else {
            arrayOf("sh", "-i")
        }

        AppLogger.i(TAG, "Payload/legacy mode: shell=$shellBin")
        if (isEnvironmentReady) {
            return TerminalSession(
                shellBin,
                homeDir.absolutePath,
                shellArgs,
                env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

        // ── 5. Fallback — no environment installed ───────────────────────────
        AppLogger.w(TAG, "Fallback: no environment installed, using /system/bin/sh")

        // Build the banner as a single echo command so the shell prints it
        // instead of trying to execute each line as a command (which causes
        // "inaccessible or not found" errors for box-drawing characters).
        val bannerLines = listOf(
            "OpenClaw Android - Terminal",
            "No environment installed.",
            "Go to Dashboard -> Setup to install the runtime.",
            "Limited shell: ls, cd, echo, cat available.",
        )
        val bannerCmd = bannerLines.joinToString("\\n") { "  $it" }
            .let { "printf '\\n$it\\n\\n'" }

        val fallbackSession = TerminalSession(
            "/system/bin/sh",
            homeDir.absolutePath,
            arrayOf("sh", "-i"),
            arrayOf(
                "HOME=${homeDir.absolutePath}",
                "TERM=xterm-256color",
                "PATH=/system/bin:/bin",
            ),
            TRANSCRIPT_ROWS,
            sessionClient,
        )

        activity.runOnUiThread {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Write as a shell command so the shell executes printf and
                // outputs the text — no box-drawing chars that confuse sh.
                fallbackSession.write("$bannerCmd\n")
            }, 300)
        }

        return fallbackSession
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
