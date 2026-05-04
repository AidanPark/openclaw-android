package com.openclaw.android

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Manages multiple terminal sessions.
 * One TerminalView, many sessions — switch via attachSession().
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

    /**
     * Creates a new terminal session using the app-local sandbox exclusively.
     *
     * Shell selection priority:
     *   1. proot mode: openclaw-shell.sh (bash inside Ubuntu rootfs via proot)
     *   2. termux-bootstrap: bash from Termux Bootstrap installation
     *   3. online install: bash from prefix/bin/bash with full glibc env
     *   4. payload mode: glibc-wrapped bash from payload
     *   5. fallback: /system/bin/sh (limited, no glibc tools)
     *
     * NOTE: /system/bin/sh is ONLY used as last resort. It cannot run glibc
     * binaries (node, git, openclaw) and will fail with "CANNOT LINK EXECUTABLE"
     * if those are invoked.
     */
    fun createSession(): TerminalSession {
        val base = activity.filesDir.absolutePath
        val homeDir = File(base, "home").also { it.mkdirs() }
        val tmpDir = File(base, "tmp").also { it.mkdirs() }

        // ── Proot mode: use openclaw-shell.sh (bash inside Ubuntu rootfs) ──
        val prootShellScript = File(homeDir, "openclaw-shell.sh")
        val isProotInstalled = File(activity.filesDir, ".proot-installed").exists() &&
            prootShellScript.exists() && prootShellScript.canExecute()

        if (isProotInstalled) {
            AppLogger.i(TAG, "Proot mode: using openclaw-shell.sh as terminal shell")
            val prootEnv = arrayOf(
                "HOME=${homeDir.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PROOT_NO_SECCOMP=1",
                "PROOT_TMP_DIR=${activity.cacheDir.absolutePath}",
            )
            val session = TerminalSession(
                prootShellScript.absolutePath,
                homeDir.absolutePath,
                arrayOf("openclaw-shell.sh"),
                prootEnv,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
            sessions.add(session)
            switchSession(sessions.size - 1)
            eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
            activity.runOnUiThread { onSessionsChanged?.invoke() }
            return session
        }

        // ── Termux Bootstrap mode: bash from Termux Bootstrap installation ──
        // Detected when: .termux-bootstrap-installed marker exists
        val termuxBootstrapMarker = File(base, ".termux-bootstrap-installed")
        val termuxPrefix = File(base, "usr") // Siempre usar files/usr
        val termuxBash = File(termuxPrefix, "bin/bash")
        val termuxDpkg = File(termuxPrefix, "bin/dpkg")
        val termuxApt = File(termuxPrefix, "bin/apt")
        
        val isTermuxBootstrapInstalled = termuxBootstrapMarker.exists() &&
            termuxBash.exists() && termuxBash.canExecute() &&
            termuxDpkg.exists() && termuxApt.exists()

        if (isTermuxBootstrapInstalled) {
            AppLogger.i(TAG, "Termux Bootstrap mode: using ${termuxBash.absolutePath}")
            
            // Configurar entorno para Termux Bootstrap
            val termuxEnv = arrayOf(
                "HOME=${homeDir.absolutePath}",
                "PREFIX=${termuxPrefix.absolutePath}",
                "TMPDIR=${tmpDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=${termuxPrefix.absolutePath}/bin:${termuxPrefix.absolutePath}/bin/applets:/system/bin:/bin",
                "LD_LIBRARY_PATH=${termuxPrefix.absolutePath}/lib",
                // Variables de entorno específicas de Termux
                "PACKAGE_MANAGER=apt",
                "TERMUX_VERSION=1.0",
                "TERMUX_APP_PID=${android.os.Process.myPid()}",
                // Configuración para evitar problemas de permisos con dpkg/apt
                "DEBIAN_FRONTEND=noninteractive",
                "DEBCONF_NONINTERACTIVE_SEEN=true",
            )
            
            val session = TerminalSession(
                termuxBash.absolutePath,
                homeDir.absolutePath,
                arrayOf("bash", "-i"),
                termuxEnv,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
            sessions.add(session)
            switchSession(sessions.size - 1)
            eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
            activity.runOnUiThread { onSessionsChanged?.invoke() }
            return session
        }

        // ── Ningún entorno instalado: usar system shell con mensaje útil ──
        AppLogger.w(TAG, "No environment installed, using system shell with help message")
        
        // Escribir un mensaje de ayuda en el terminal
        val helpMessage = """
            ╔══════════════════════════════════════════════════════════════╗
            ║                 OpenClaw Android - Terminal                  ║
            ╠══════════════════════════════════════════════════════════════╣
            ║                                                              ║
            ║  ❗ Entorno de terminal no instalado                         ║
            ║                                                              ║
            ║  Para usar este terminal, necesitas instalar un entorno:    ║
            ║                                                              ║
            ║  1. Ve al Dashboard (botón superior izquierdo)              ║
            ║  2. Haz clic en "Instalar Termux Bootstrap"                 ║
            ║  3. Espera 2-3 minutos mientras se descarga (~50MB)         ║
            ║  4. ¡Listo! Podrás usar bash, apt, dpkg, git, curl, etc.    ║
            ║                                                              ║
            ║  Alternativa avanzada:                                      ║
            ║  • Proot + Ubuntu (sistema completo, ~250MB)                ║
            ║    - Resistente a Phantom Process Killer                    ║
            ║    - Ideal para procesos largos                             ║
            ║                                                              ║
            ╚══════════════════════════════════════════════════════════════╝
            
            Mientras tanto, solo tienes acceso limitado a /system/bin/sh
            Comandos disponibles: ls, cd, echo, cat, etc.
            
            Para instalar ahora, escribe: install-termux
            Para ver diagnóstico: diagnostic
            
        """.trimIndent()
        
        // Crear sesión con system shell
        val systemEnv = arrayOf(
            "HOME=${homeDir.absolutePath}",
            "TERM=xterm-256color",
            "PATH=/system/bin:/bin",
        )
        
        val session = TerminalSession(
            "/system/bin/sh",
            homeDir.absolutePath,
            arrayOf("sh", "-i"),
            systemEnv,
            TRANSCRIPT_ROWS,
            object : TerminalSessionClient by sessionClient {
                override fun onTextChanged(session: TerminalSession) {
                    sessionClient.onTextChanged(session)
                }
                
                override fun onSessionFinished(session: TerminalSession) {
                    sessionClient.onSessionFinished(session)
                }
                
                override fun onTitleChanged(session: TerminalSession) {
                    sessionClient.onTitleChanged(session)
                }
                
                override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                    sessionClient.onCopyTextToClipboard(session, text)
                }
                
                override fun onPasteTextFromClipboard(session: TerminalSession) {
                    sessionClient.onPasteTextFromClipboard(session)
                }
                
                override fun onBell(session: TerminalSession) {
                    sessionClient.onBell(session)
                }
                
                override fun onColorsChanged(session: TerminalSession) {
                    sessionClient.onColorsChanged(session)
                }
                
                override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
                    sessionClient.setTerminalShellPid(session, pid)
                }
            },
        )
        
        // Escribir mensaje de ayuda después de que la sesión se inicialice
        activity.runOnUiThread {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                session.write(helpMessage.toByteArray())
            }, 100)
        }
        
        sessions.add(session)
        switchSession(sessions.size - 1)
        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }
        return session

        // ── Online install mode: bash from Termux bootstrap with glibc env ──
        // Detected when: prefix/bin/bash exists + .openclaw-android/installed.json
        // This is the layout left by: curl -sL myopenclawhub.com/install | bash
        val onlinePrefix = File(base, "usr")
        val ocaDir = File(homeDir, ".openclaw-android")
        val bashBin = File(onlinePrefix, "bin/bash")
        val installedJson = File(ocaDir, "installed.json")
        val nodeReal = File(ocaDir, "node/bin/node.real")
        val glibcLdso = File(onlinePrefix, "glibc/lib/ld-linux-aarch64.so.1")
        val ocaMjs = File(onlinePrefix, "lib/node_modules/openclaw/openclaw.mjs")

        val isOnlineInstall = bashBin.exists() && bashBin.canExecute() &&
            installedJson.exists() && nodeReal.exists() && glibcLdso.exists() && ocaMjs.exists()

        if (isOnlineInstall) {
            AppLogger.i(TAG, "Online install mode: using ${bashBin.absolutePath}")
            val ocaBin = File(ocaDir, "bin").absolutePath
            val nodeDir = File(ocaDir, "node/bin").absolutePath
            val glibcLib = File(onlinePrefix, "glibc/lib").absolutePath
            val certPem = File(onlinePrefix, "etc/tls/cert.pem").absolutePath

            val onlineEnv = arrayOf(
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
            )
            val session = TerminalSession(
                bashBin.absolutePath,
                homeDir.absolutePath,
                arrayOf("bash", "-i"),
                onlineEnv,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
            sessions.add(session)
            switchSession(sessions.size - 1)
            eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
            activity.runOnUiThread { onSessionsChanged?.invoke() }
            return session
        }

        // ── Payload / legacy mode ────────────────────────────────────────────
        val env = EnvironmentBuilder.buildEnvironment(
            activity.filesDir,
            activity.packageName,
        ).toMutableMap()

        val prefixPath = env["PREFIX"] ?: "$base/usr"
        val prefix = File(prefixPath).also { if (!it.exists()) it.mkdirs() }

        env["HOME"] = homeDir.absolutePath
        env["PREFIX"] = prefix.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath

        val payloadManager = PayloadManager(activity)

        // ── SAFE MODE: Determine if we can use Termux environment or must fall back ──
        val isEnvironmentReady = payloadManager.isReady()
        val hasGlibcLinker = File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasTermuxExec = File(prefix, "lib/libtermux-exec.so").exists()

        // Check if bash/sh are real binaries or emergency wrappers
        val bashFile = File(prefix, "bin/bash")
        val shFile = File(prefix, "bin/sh")

        val isBashWrapper = try {
            bashFile.exists() &&
            bashFile.length() < 200 &&
            bashFile.readText().contains("# Emergency bash wrapper")
        } catch (e: Exception) {
            false
        }

        val isShWrapper = try {
            shFile.exists() &&
            shFile.length() < 200 &&
            shFile.readText().contains("# Emergency")
        } catch (e: Exception) {
            false
        }

        // Determine if we MUST use safe mode (system shell only)
        val mustUseSafeMode = !isEnvironmentReady ||
                              (!hasGlibcLinker && !hasTermuxExec) ||
                              (bashFile.exists() && isBashWrapper) ||
                              (shFile.exists() && isShWrapper)

        if (mustUseSafeMode) {
            AppLogger.w(TAG, "Safe Mode required: envReady=$isEnvironmentReady, glibc=$hasGlibcLinker, termuxExec=$hasTermuxExec")
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
        }

        // Final safety: if LD_PRELOAD file doesn't exist, remove it
        val ldPreload = env["LD_PRELOAD"]
        if (ldPreload != null && !File(ldPreload).exists()) {
            env.remove("LD_PRELOAD")
        }

        var shellBin = if (mustUseSafeMode) {
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
            // CRITICAL: Remove LD_LIBRARY_PATH when using /system/bin/sh (Bionic).
            // If glibc/lib is in LD_LIBRARY_PATH, Android's linker finds glibc's
            // libc.so and fails: "cannot find libc.so from verneed[0]"
            env.remove("LD_LIBRARY_PATH")
        }

        val shellArgs: Array<String> = if (shellBin.endsWith("/bash")) {
            arrayOf("bash", "-i", "--norc", "--noprofile")
        } else {
            arrayOf("sh", "-i")
        }

        AppLogger.i(TAG, "Creating session: shell=$shellBin home=${homeDir.absolutePath}")

        val session = TerminalSession(
            shellBin,
            homeDir.absolutePath,
            shellArgs,
            env.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            sessionClient,
        )

        sessions.add(session)
        switchSession(sessions.size - 1)

        eventBridge.emit("session_changed", mapOf("id" to session.mHandle, "action" to "created"))
        activity.runOnUiThread { onSessionsChanged?.invoke() }

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
        val session = sessions.removeAt(index)
        session.finishIfRunning()

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
