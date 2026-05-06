package com.openclaw.android.core.process

import com.openclaw.android.AppLogger
import java.io.File

/**
 * GlibcRunner — executes ELF binaries via the glibc dynamic linker.
 *
 * This is the ONLY correct way to run Node.js on Android without proot.
 * Never use /system/bin/sh to invoke node directly — Bionic libc is
 * incompatible with glibc ELF binaries.
 *
 * Usage:
 *   GlibcRunner.run(payloadDir, nodeArgs = listOf("--version"))
 *   GlibcRunner.runOpenClaw(payloadDir, args = listOf("gateway", "--host", "0.0.0.0"))
 */
object GlibcRunner {

    private const val TAG = "GlibcRunner"

    data class RunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Resolve the glibc dynamic linker path from a payload directory.
     * Returns null if the linker is not present or too small to be valid.
     */
    fun resolveLinker(payloadDir: File): File? {
        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
        return if (ldso.exists() && ldso.length() > 100_000) ldso else null
    }

    /**
     * Resolve the glibc library directory from a payload directory.
     */
    fun resolveGlibcLib(payloadDir: File): File =
        File(payloadDir, "glibc/lib")

    /**
     * Resolve the node binary from a payload directory.
     *
     * Priority order:
     *   1. payload/lib/node/bin/node.real  — payload-final.tar.gz
     *   2. payload/lib/node/bin/node
     *   3. payload/../home/.openclaw-android/node/bin/node.real  — online install (curl | bash)
     *   4. payload/glibc/bin/node          — legacy layout
     */
    fun resolveNode(payloadDir: File): File? {
        // Derive homeDir from payloadDir: payloadDir is homeDir/payload or filesDir/payload
        val homeDir = payloadDir.parentFile
        val ocaDir = homeDir?.resolve(".openclaw-android")

        val candidates = buildList {
            add(File(payloadDir, "lib/node/bin/node.real"))   // payload-final.tar.gz
            add(File(payloadDir, "lib/node/bin/node"))
            if (ocaDir != null) {
                add(File(ocaDir, "node/bin/node.real"))        // online install (curl | bash)
                add(File(ocaDir, "node/bin/node"))
            }
            add(File(payloadDir, "glibc/bin/node"))            // legacy
        }
        return candidates.firstOrNull { it.exists() && it.length() > 1_000_000 }
    }

    /**
     * Resolve the openclaw.mjs entry point from a payload directory.
     *
     * Priority order:
     *   1. payload/lib/openclaw/openclaw.mjs   — payload-final.tar.gz
     *   2. prefix/lib/node_modules/openclaw/openclaw.mjs  — online install
     *   3. payload/openclaw/openclaw.mjs       — legacy layout
     */
    fun resolveOpenClawMjs(payloadDir: File): File? {
        // Derive prefix: if payloadDir is homeDir/payload, prefix is filesDir/usr
        val filesDir = payloadDir.parentFile?.parentFile
        val prefix = filesDir?.resolve("usr")

        val candidates = buildList {
            add(File(payloadDir, "lib/openclaw/openclaw.mjs"))   // payload-final.tar.gz
            if (prefix != null) {
                add(File(prefix, "lib/node_modules/openclaw/openclaw.mjs"))  // online install
            }
            add(File(payloadDir, "openclaw/openclaw.mjs"))        // legacy
        }
        return candidates.firstOrNull { it.exists() }
    }

    /**
     * Build a ProcessBuilder that runs a binary via the glibc dynamic linker.
     *
     * This is the correct pattern from OPENCLAW_ANDROID_FINAL.md §8:
     *   ld-linux-aarch64.so.1 --library-path <glibcLib> <binary> [args...]
     *
     * Critical: LD_PRELOAD must be removed to prevent bionic libtermux-exec.so
     * from being injected into the glibc process (causes PHDR crash).
     */
    fun buildProcess(
        payloadDir: File,
        binary: File,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessBuilder? {
        val linker = resolveLinker(payloadDir) ?: run {
            AppLogger.e(TAG, "glibc linker not found in ${payloadDir.absolutePath}")
            return null
        }
        val glibcLib = resolveGlibcLib(payloadDir)

        val cmd = buildList {
            add(linker.absolutePath)
            add("--library-path")
            add(glibcLib.absolutePath)
            add(binary.absolutePath)
            addAll(args)
        }

        AppLogger.d(TAG, "GlibcRunner cmd: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.environment().apply {
            // Start clean — no inherited Android env that could interfere
            clear()
            put("PATH", "/system/bin:/bin")
            put("LD_LIBRARY_PATH", glibcLib.absolutePath)
            // CRITICAL: never set LD_PRELOAD — bionic libs crash glibc processes
            remove("LD_PRELOAD")
            put("OA_GLIBC", "1")
            put("CONTAINER", "1")
            put("TMPDIR", "/data/local/tmp")
            // SSL certs — payload-final.tar.gz puts them in certs/cert.pem
            val certPem = listOf(
                File(payloadDir, "certs/cert.pem"),   // payload-final.tar.gz
                File(payloadDir, "ssl/cert.pem"),      // legacy
            ).firstOrNull { it.exists() && it.length() > 0 }
            if (certPem != null) {
                put("SSL_CERT_FILE", certPem.absolutePath)
                put("CURL_CA_BUNDLE", certPem.absolutePath)
                put("NODE_EXTRA_CA_CERTS", certPem.absolutePath)
            }
            // Caller overrides
            putAll(extraEnv)
        }
        return pb
    }

    /**
     * Run node --version via the glibc linker.
     * This is the correct way to verify node works (not via /system/bin/sh).
     */
    fun checkNodeVersion(payloadDir: File): String {
        val node = resolveNode(payloadDir) ?: return "not found"
        return try {
            val pb = buildProcess(payloadDir, node, listOf("--version")) ?: return "linker missing"
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifEmpty { "unknown" }
        } catch (e: Exception) {
            AppLogger.e(TAG, "checkNodeVersion failed: ${e.message}", e)
            "error: ${e.message}"
        }
    }

    /**
     * Run a node script via the glibc linker.
     * Returns RunResult with stdout, stderr, and exit code.
     */
    fun runNode(
        payloadDir: File,
        scriptArgs: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10_000,
    ): RunResult {
        val node = resolveNode(payloadDir)
            ?: return RunResult(-1, "", "node binary not found in payload")

        return try {
            val pb = buildProcess(payloadDir, node, scriptArgs, extraEnv)
                ?: return RunResult(-1, "", "glibc linker not found")
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exited = waitForProcess(process, timeoutMs)

            if (!exited) {
                process.destroyForcibly()
                RunResult(-1, stdout, "Process timed out after ${timeoutMs}ms")
            } else {
                RunResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "runNode failed: ${e.message}", e)
            RunResult(-1, "", e.message ?: "unknown error")
        }
    }

    /**
     * Launch the OpenClaw gateway as a long-running process.
     * Returns the Process so the caller can monitor/stream output.
     */
    fun launchOpenClaw(
        payloadDir: File,
        homeDir: File,
        args: List<String> = listOf("gateway", "--host", "0.0.0.0"),
        extraEnv: Map<String, String> = emptyMap(),
    ): Process? {
        val node = resolveNode(payloadDir) ?: run {
            AppLogger.e(TAG, "Cannot launch OpenClaw: node not found")
            return null
        }
        val ocMjs = resolveOpenClawMjs(payloadDir) ?: run {
            AppLogger.e(TAG, "Cannot launch OpenClaw: openclaw.mjs not found")
            return null
        }

        val scriptArgs = buildList {
            add(ocMjs.absolutePath)
            addAll(args)
        }

        val env = buildMap {
            put("HOME", homeDir.absolutePath)
            put("CLAWDHUB_WORKDIR", File(homeDir, ".openclaw/workspace").absolutePath)
            putAll(extraEnv)
        }

        return try {
            val pb = buildProcess(payloadDir, node, scriptArgs, env) ?: return null
            pb.directory(homeDir.also { it.mkdirs() })
            pb.redirectErrorStream(true)
            val process = pb.start()
            AppLogger.i(TAG, "OpenClaw gateway launched via glibc linker")
            process
        } catch (e: Exception) {
            AppLogger.e(TAG, "launchOpenClaw failed: ${e.message}", e)
            null
        }
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                // For API < 26, implement timeout with proper process destruction
                val startTime = System.currentTimeMillis()
                var exited = false
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        process.exitValue()  // Throws if still running
                        exited = true
                        break
                    } catch (_: IllegalThreadStateException) {
                        // Process still running, wait a bit
                        Thread.sleep(100)
                    }
                }
                if (!exited) {
                    // Timeout expired, destroy process
                    AppLogger.w(TAG, "Process timeout expired, destroying forcibly")
                    process.destroy()
                    // Give it a brief moment to terminate gracefully
                    Thread.sleep(500)
                    try {
                        process.exitValue()  // Check if destroyed
                    } catch (_: IllegalThreadStateException) {
                        // Still running, force destroy
                        process.destroyForcibly()
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
