package com.openclaw.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell command execution via ProcessBuilder.
 *
 * Design principles:
 *   - Never calls /usr/bin/node or node directly.
 *   - Always uses the glibc-wrapped node from OCA_BIN.
 *   - All paths are resolved from the app sandbox (filesDir).
 *   - Termux paths are kept as legacy constants for detection only.
 *   - bash -l is NOT used: we build the full environment explicitly,
 *     so there is no dependency on Termux's /etc/profile or .bashrc.
 */
object CommandRunner {
    private const val TAG = "CommandRunner"

    // Legacy Termux paths — used only for detection/fallback, never written to.
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val OPENCLAW_DIR = "$TERMUX_HOME/.openclaw-android"
    const val OPENCLAW_BIN = "$OPENCLAW_DIR/bin"
    const val INSTALLED_MARKER = "$OPENCLAW_DIR/installed.json"
    const val WRAPPER_SCRIPT = "$TERMUX_HOME/openclaw-start.sh"

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** Build the environment map using app-local paths. */
    fun buildTermuxEnv(context: Context? = null): Map<String, String> =
        EnvironmentBuilder.buildTermuxEnvironment(context)

    /** Build a safe working directory. */
    private fun safeWorkDir(workDir: File): File = when {
        workDir.exists() -> workDir
        else -> {
            // Derive from HOME env var (set by EnvironmentBuilder)
            val home = System.getenv("HOME")
            if (home != null && File(home).exists()) File(home)
            else File("/data/local/tmp").also { it.mkdirs() }
        }
    }

    /**
     * Strip LD_PRELOAD if the referenced library doesn't exist.
     * Prevents crash when libtermux-exec.so is referenced but not present.
     */
    private fun safeEnv(env: Map<String, String>): Map<String, String> =
        env.toMutableMap().apply {
            val ldPreload = get("LD_PRELOAD")
            if (ldPreload != null && !File(ldPreload).exists()) remove("LD_PRELOAD")
        }

    /**
     * Run a command synchronously.
     * Uses /system/bin/sh (always available on Android) as the shell.
     * The full environment is passed explicitly — no login shell needed.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun runSync(
        command: String,
        env: Map<String, String> = buildTermuxEnv(),
        workDir: File = resolveHomeDir(),
        timeoutMs: Long = 5_000,
    ): CommandResult =
        try {
            val shell = resolveShell(env)
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            pb.environment().putAll(safeEnv(env))
            pb.directory(safeWorkDir(workDir))
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exited = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                // Fallback for API < 26: synchronous wait with manual timeout thread
                val waiter = Thread { try { process.waitFor() } catch (e: InterruptedException) {} }
                waiter.start()
                waiter.join(timeoutMs)
                if (waiter.isAlive) {
                    waiter.interrupt()
                    false
                } else {
                    true
                }
            }

            if (!exited) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.destroyForcibly()
                } else {
                    process.destroy()
                }
                CommandResult(-1, stdout, "Command timed out (${timeoutMs}ms)")
            } else {
                CommandResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "runSync failed: ${e.message}", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }

    /**
     * Run a command asynchronously, streaming output line-by-line.
     *
     * Streams stdout and stderr to [onOutput] as they arrive.
     * Returns a [CommandResult] with the process exit code and accumulated stderr.
     * Used by both JsBridge (fire-and-forget) and PayloadManager (needs exit code).
     */
    suspend fun runStreaming(
        command: String,
        env: Map<String, String> = buildTermuxEnv(),
        workDir: File = resolveHomeDir(),
        onOutput: (String) -> Unit,
    ): CommandResult = withContext(Dispatchers.IO) {
        val stderrLines = StringBuilder()
        try {
            val shell = resolveShell(env)
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            pb.environment().putAll(safeEnv(env))
            pb.directory(safeWorkDir(workDir))
            pb.redirectErrorStream(false)

            val process = pb.start()

            // Stream stdout line-by-line to caller
            val stdoutThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    onOutput(line)
                }
            }.also { it.start() }

            // Collect stderr; also forward to caller prefixed so it's visible
            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    stderrLines.appendLine(line)
                    onOutput("[stderr] $line")
                }
            }.also { it.start() }

            stdoutThread.join()
            stderrThread.join()
            val exitCode = process.waitFor()

            CommandResult(exitCode, "", stderrLines.toString())
        } catch (e: Exception) {
            AppLogger.e(TAG, "runStreaming failed: ${e.message}", e)
            onOutput("Error: ${e.message}")
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Check if OpenClaw is installed in the app sandbox.
     * Only checks app-local executable/package paths — never Termux paths.
     */
    fun isOpenClawInstalled(context: Context? = null): Boolean {
        val (prefixPath, _) = EnvironmentBuilder.resolveActivePaths(context?.filesDir)
        val prefix = File(prefixPath)
        return prefix.resolve("bin/openclaw").exists() ||
            prefix.resolve("lib/node_modules/openclaw/openclaw.mjs").exists()
    }

    /**
     * Create the openclaw-start.sh wrapper script.
     * Writes to the app-local home dir (not Termux home).
     * The script is self-contained: it sets all env vars and launches OpenClaw
     * via the glibc-wrapped node, with no dependency on Termux.
     */
    fun createWrapperScript(filesDir: File? = null): Boolean {
        val env = if (filesDir != null) {
            EnvironmentBuilder.buildEnvironment(filesDir)
        } else {
            buildTermuxEnv()
        }

        val home = env["HOME"] ?: return false
        val prefix = env["PREFIX"] ?: return false
        val appFilesDir = env["APP_FILES_DIR"] ?: filesDir?.absolutePath ?: prefix.removeSuffix("/usr")
        val appPackage = env["APP_PACKAGE"] ?: "com.openclaw.android"
        val ocaBin = "$home/.openclaw-android/bin"
        val nodeDir = "$home/.openclaw-android/node"
        val nodeBin = listOf(
            "$ocaBin/node",
            "$nodeDir/bin/node",
            "$prefix/bin/node",
        ).firstOrNull { File(it).exists() } ?: "$prefix/bin/node"
        val glibcLib = "$prefix/glibc/lib"
        val tmpDir = env["TMPDIR"] ?: "$prefix/../tmp"
        val certBundle = "$prefix/etc/tls/cert.pem"
        val ocaMjs = File(prefix, "lib/node_modules/openclaw/openclaw.mjs").absolutePath

        val compatJs = "$home/.openclaw-android/patches/glibc-compat.js"
        val scriptPath = File(home, "openclaw-start.sh")
        val content = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OpenClaw gateway launcher — generated by CommandRunner")
            appendLine("# Runs OpenClaw independently of Termux.")
            appendLine()
            appendLine("export HOME=\"$home\"")
            appendLine("export PREFIX=\"$prefix\"")
            appendLine("export TMPDIR=\"$tmpDir\"")
            appendLine("export APP_FILES_DIR=\"$appFilesDir\"")
            appendLine("export APP_PACKAGE=\"$appPackage\"")
            appendLine("export PATH=\"$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin\"")
            appendLine("export NPM_CONFIG_PREFIX=\"$prefix\"")
            appendLine("export npm_config_prefix=\"$prefix\"")
            appendLine("export LD_LIBRARY_PATH=\"$ocaBin:$nodeDir/bin:$prefix/lib:$glibcLib\"")
            appendLine("export SSL_CERT_FILE=\"$certBundle\"")
            appendLine("export CURL_CA_BUNDLE=\"$certBundle\"")
            appendLine("export GIT_SSL_CAINFO=\"$certBundle\"")
            appendLine("export RESOLV_CONF=\"$prefix/etc/resolv.conf\"")
            appendLine("export GIT_CONFIG_NOSYSTEM=1")
            appendLine("export GIT_EXEC_PATH=\"$prefix/libexec/git-core\"")
            appendLine("export GIT_TEMPLATE_DIR=\"$prefix/share/git-core/templates\"")
            appendLine("export LANG=en_US.UTF-8")
            appendLine("export TERM=xterm-256color")
            appendLine("export ANDROID_DATA=/data")
            appendLine("export ANDROID_ROOT=/system")
            appendLine("export OA_GLIBC=1")
            appendLine("export CONTAINER=1")
            appendLine("export CLAWDHUB_WORKDIR=\"$home/.openclaw/workspace\"")
            appendLine("export _OA_WRAPPER_PATH=\"$ocaBin/node\"")
            appendLine()
            appendLine("# Unset LD_PRELOAD: prevents bionic libtermux-exec.so from")
            appendLine("# loading into the glibc node process (causes PHDR crash).")
            appendLine("unset LD_PRELOAD")
            appendLine()
            appendLine("# Load glibc-compat.js shim: fixes process.execPath, os.cpus(),")
            appendLine("# os.networkInterfaces() and auto-disables Bonjour on Android.")
            appendLine("if [ -f \"$compatJs\" ]; then")
            appendLine("  case \"\${NODE_OPTIONS:-}\" in")
            appendLine("    *\"$compatJs\"*) ;;")
            appendLine("    *) export NODE_OPTIONS=\"\${NODE_OPTIONS:+\$NODE_OPTIONS }-r $compatJs\" ;;")
            appendLine("  esac")
            appendLine("fi")
            appendLine()
            appendLine("exec \"$nodeBin\" \"$ocaMjs\" gateway --host 0.0.0.0")
        }

        return try {
            scriptPath.parentFile?.mkdirs()
            scriptPath.writeText(content)
            scriptPath.setExecutable(true)
            AppLogger.i(TAG, "Wrapper script created at ${scriptPath.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create wrapper script: ${e.message}", e)
            false
        }
    }

    /**
     * Write the installed.json marker file to the app-local home dir.
     */
    fun writeInstalledMarker(filesDir: File? = null): Boolean {
        val home = if (filesDir != null) {
            filesDir.resolve("home").absolutePath
        } else {
            System.getenv("HOME") ?: TERMUX_HOME
        }
        val ocaDir = File("$home/.openclaw-android")
        val marker = File(ocaDir, "installed.json")
        return try {
            ocaDir.mkdirs()
            marker.writeText("""{"installed":true,"path":"${ocaDir.absolutePath}"}""")
            AppLogger.i(TAG, "installed.json marker written at ${marker.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write installed.json: ${e.message}", e)
            false
        }
    }

    /**
     * Launch the OpenClaw gateway.
     * Uses the app-local openclaw-start.sh if it exists, otherwise builds
     * the command directly from the environment.
     * Returns the running Process so the caller can monitor/stream output.
     */
    fun launchGateway(filesDir: File? = null): Process? {
        val env = if (filesDir != null) {
            EnvironmentBuilder.buildEnvironment(filesDir)
        } else {
            buildTermuxEnv()
        }

        val home = env["HOME"] ?: return null
        val prefix = env["PREFIX"] ?: return null
        val ocaBin = "$home/.openclaw-android/bin"
        val nodeBin = listOf(
            "$ocaBin/node",
            "$home/.openclaw-android/node/bin/node",
            "$prefix/bin/node",
        ).firstOrNull { File(it).exists() } ?: "$prefix/bin/node"
        val ocaMjs = "$prefix/lib/node_modules/openclaw/openclaw.mjs"

        // Prefer the pre-written start script (idempotent, logged)
        val startScript = File(home, "openclaw-start.sh")
        if (!startScript.exists()) {
            createWrapperScript(filesDir)
        }

        return try {
            val shell = resolveShell(env)
            val cmd = if (startScript.exists()) {
                listOf(shell, startScript.absolutePath)
            } else {
                // Direct launch fallback
                listOf(shell, "-c", "exec \"$nodeBin\" \"$ocaMjs\" gateway --host 0.0.0.0")
            }

            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(safeEnv(env))
            pb.directory(File(home).also { it.mkdirs() })
            pb.redirectErrorStream(true)

            val process = pb.start()
            AppLogger.i(TAG, "OpenClaw gateway launched")
            process
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch gateway: ${e.message}", e)
            null
        }
    }

    /**
     * Resolve the best available shell from the app sandbox only.
     *
     * For non-interactive command execution (runSync, runStreaming) we prefer
     * /system/bin/sh over the bootstrap bash because:
     *   1. post-setup.sh runs BEFORE the bootstrap is fully configured, so
     *      $PREFIX/bin/bash may not exist yet or may fail to start if
     *      libtermux-exec.so is missing (hardcoded com.termux paths in ELF).
     *   2. /system/bin/sh (mksh/ash on Android) is always available and has
     *      no dependency on the app sandbox state.
     *
     * Bootstrap bash is only used if /system/bin/sh is genuinely absent
     * (should never happen on Android 6+).
     */
    private fun resolveShell(env: Map<String, String>): String {
        // Always prefer the system shell for non-interactive execution.
        // It has no dependency on the bootstrap or libtermux-exec.so.
        if (File("/system/bin/sh").exists()) return "/system/bin/sh"
        if (File("/bin/sh").exists()) return "/bin/sh"

        // Last resort: bootstrap bash (may fail if bootstrap not yet configured)
        val prefix = env["PREFIX"]
        if (prefix != null) {
            val bash = File("$prefix/bin/bash")
            if (bash.canExecute()) return bash.absolutePath
            val sh = File("$prefix/bin/sh")
            if (sh.canExecute()) return sh.absolutePath
        }
        return "/system/bin/sh"
    }

    /**
     * Resolve the home directory from the current process environment.
     */
    private fun resolveHomeDir(): File {
        val home = System.getenv("HOME")
        if (home != null && File(home).exists()) return File(home)
        return File("/data/local/tmp").also { it.mkdirs() }
    }

    // ── Legacy Termux helpers (kept for backward compatibility) ───────────────

    /** Check if termux-setup-storage has been run. */
    fun isStorageSetupDone(): Boolean = File("$TERMUX_HOME/storage").exists()

    /**
     * Run termux-setup-storage only if Termux is actually installed and accessible.
     * This is a legacy helper — the app does not depend on it for normal operation.
     * No-op if Termux is not installed.
     */
    fun runTermuxSetupStorage(onOutput: (String) -> Unit = {}) {
        if (!File(TERMUX_HOME).exists() || !File(TERMUX_HOME).canRead()) {
            AppLogger.i(TAG, "Termux not accessible, skipping termux-setup-storage")
            onOutput("Termux not installed, skipping storage setup.")
            return
        }
        if (isStorageSetupDone()) {
            AppLogger.i(TAG, "termux-setup-storage already done")
            onOutput("Storage already configured.")
            return
        }
        AppLogger.i(TAG, "Running termux-setup-storage...")
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", "yes | termux-setup-storage")
            pb.environment().clear()
            pb.environment().putAll(safeEnv(buildTermuxEnv()))
            pb.directory(File(TERMUX_HOME))
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                AppLogger.i(TAG, "setup-storage: $line")
                onOutput(line)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(30, TimeUnit.MILLISECONDS)
            } else {
                // Fallback for API < 26
                Thread.sleep(30)
            }
            AppLogger.i(TAG, "termux-setup-storage completed")
        } catch (e: Exception) {
            AppLogger.e(TAG, "termux-setup-storage failed: ${e.message}", e)
            onOutput("Error: ${e.message}")
        }
    }

    /**
     * Sets up storage symlinks in the app sandbox, similar to termux-setup-storage.
     * Creates a ~/storage directory with symlinks to standard Android external storage directories.
     */
    fun setupAppStorage(context: Context, onOutput: (String) -> Unit = {}) {
        val env = buildTermuxEnv(context)
        val home = env["HOME"] ?: return
        val storageDir = File(home, "storage")

        if (storageDir.exists() && storageDir.isDirectory && storageDir.list()?.isNotEmpty() == true) {
            AppLogger.i(TAG, "setupAppStorage already done")
            onOutput("Storage already configured.")
            return
        }

        AppLogger.i(TAG, "Running setupAppStorage...")
        onOutput("Configuring storage symlinks...")

        try {
            storageDir.mkdirs()

            val extStorage = Environment.getExternalStorageDirectory()

            val symlinks = mapOf(
                "shared" to extStorage,
                "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            )

            for ((name, target) in symlinks) {
                val linkFile = File(storageDir, name)
                if (!linkFile.exists()) {
                    try {
                        android.system.Os.symlink(target.absolutePath, linkFile.absolutePath)
                        AppLogger.d(TAG, "Symlink created: ${linkFile.absolutePath} -> ${target.absolutePath}")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Os.symlink failed for $name, trying fallback: ${e.message}")
                        // Fallback using ln -s
                        val result = runSync("ln -s \"${target.absolutePath}\" \"${linkFile.absolutePath}\"", env, storageDir)
                        if (result.exitCode != 0) {
                            AppLogger.e(TAG, "ln -s fallback failed for $name: ${result.stderr}")
                        }
                    }
                }
            }

            // Write a marker file so isStorageSetupDone() or similar checks pass if needed
            File(home, "storage-setup-done").writeText(System.currentTimeMillis().toString())

            AppLogger.i(TAG, "setupAppStorage completed")
            onOutput("Storage configuration complete.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setupAppStorage failed: ${e.message}", e)
            onOutput("Error configuring storage: ${e.message}")
        }
    }
}
