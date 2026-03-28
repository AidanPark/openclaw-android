package com.openclaw.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell command execution via ProcessBuilder (§2.2.5).
 * Uses Termux bootstrap environment for all commands.
 */
object CommandRunner {
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Run a command synchronously with timeout.
     * Returns stdout/stderr and exit code.
     */
    fun runSync(
        command: String,
        env: Map<String, String>,
        workDir: File,
        timeoutMs: Long = 5_000,
    ): CommandResult =
        try {
            val shell = env["PREFIX"]?.let { "$it/bin/sh" } ?: "/system/bin/sh"
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(workDir)
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!exited) {
                process.destroyForcibly()
                CommandResult(-1, stdout, "Command timed out after ${timeoutMs}ms")
            } else {
                CommandResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown error")
        }

    /**
     * Run a command asynchronously, streaming output line-by-line via callback.
     */
    suspend fun runStreaming(
        command: String,
        env: Map<String, String>,
        workDir: File,
        onOutput: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            val shell = env["PREFIX"]?.let { "$it/bin/sh" } ?: "/system/bin/sh"
            val pb = ProcessBuilder(shell, "-c", command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(workDir)
            pb.redirectErrorStream(true)

            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                onOutput(line)
            }
            process.waitFor()
        } catch (e: Exception) {
            onOutput("Error: ${e.message}")
        }
    }
}
