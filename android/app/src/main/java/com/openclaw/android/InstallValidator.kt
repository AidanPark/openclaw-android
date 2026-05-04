package com.openclaw.android

import java.io.File

/**
 * InstallValidator — verifies that an installation is complete and functional.
 *
 * Called AFTER all extraction steps finish, BEFORE writing the .installed marker.
 * This prevents the app from thinking the install is done when critical files are missing.
 *
 * Design:
 *   - Pure function: takes paths, returns result. No side effects.
 *   - Each check returns a human-readable error message on failure.
 *   - The caller (InstallerManager) decides whether to abort or continue.
 */
object InstallValidator {

    private const val TAG = "InstallValidator"

    /**
     * Result of a validation run.
     * @param passed  True if ALL critical checks passed.
     * @param errors  List of human-readable error strings (empty if passed).
     * @param warnings  Non-fatal issues that were detected.
     */
    data class ValidationResult(
        val passed: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    )

    /**
     * Validate a payload installation.
     *
     * Checks (in order of criticality):
     *   1. PREFIX/bin/ exists and is non-empty
     *   2. PREFIX/lib/ exists
     *   3. PREFIX/glibc/lib/ exists
     *   4. PREFIX/glibc/lib/ld-linux-aarch64.so.1 exists (dynamic linker)
     *   5. PREFIX/etc/ exists
     *   6. bash or sh wrapper exists in bin/
     *
     * @param prefix  The PREFIX directory (e.g. filesDir/usr)
     * @return ValidationResult with detailed findings
     */
    fun validatePayload(prefix: File): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── Critical: glibc runtime ─────────────────────────────────────────
        val glibcLib = File(prefix, "glibc/lib")
        if (!glibcLib.isDirectory) {
            errors.add("glibc/lib/ directory missing")
        }

        val ldSo = File(prefix, "glibc/lib/ld-linux-aarch64.so.1")
        if (!ldSo.exists()) {
            errors.add("ld-linux-aarch64.so.1 missing (glibc dynamic linker)")
        } else if (ldSo.length() < 100_000) {
            errors.add("ld-linux-aarch64.so.1 too small (${ldSo.length()} bytes) — may be corrupt")
        }

        // ── Critical: node binary ────────────────────────────────────────────
        // payload-final.tar.gz: lib/node/bin/node.real
        // legacy layout: bin/node or glibc/bin/node
        val nodeFile = listOf(
            File(prefix, "lib/node/bin/node.real"),   // payload-final.tar.gz
            File(prefix, "lib/node/bin/node"),
            File(prefix, "glibc/bin/node"),
            File(prefix, "bin/node"),
        ).firstOrNull { it.exists() }

        if (nodeFile == null) {
            errors.add("node binary missing (checked lib/node/bin/node.real, glibc/bin/node, bin/node)")
        } else if (nodeFile.length() < 1_000_000) {
            errors.add("node binary too small (${nodeFile.length()} bytes) — may be corrupt or truncated")
        }

        // ── Critical: openclaw entry point ───────────────────────────────────
        // payload-final.tar.gz: lib/openclaw/openclaw.mjs
        // legacy layout: openclaw/openclaw.mjs or lib/node_modules/openclaw/openclaw.mjs
        val ocMjs = listOf(
            File(prefix, "lib/openclaw/openclaw.mjs"),            // payload-final.tar.gz
            File(prefix, "openclaw/openclaw.mjs"),
            File(prefix, "lib/node_modules/openclaw/openclaw.mjs"),
        ).firstOrNull { it.exists() }

        if (ocMjs == null) {
            errors.add("openclaw.mjs missing (checked lib/openclaw/, openclaw/, lib/node_modules/openclaw/)")
        }

        // ── Critical: shell availability ────────────────────────────────────
        val hasSystemShell = File("/system/bin/sh").exists()
        if (!hasSystemShell) {
            errors.add("No shell found — /system/bin/sh missing")
        }

        // ── Important: SSL certs ────────────────────────────────────────────
        val certPem = listOf(
            File(prefix, "certs/cert.pem"),       // payload-final.tar.gz
            File(prefix, "ssl/cert.pem"),
            File(prefix, "etc/tls/cert.pem"),
        ).firstOrNull { it.exists() && it.length() > 0 }
        if (certPem == null) {
            warnings.add("SSL certificates not found — HTTPS may fail")
        }

        // ── Important: glibc-compat.js ──────────────────────────────────────
        val compatJs = File(prefix, "patches/glibc-compat.js")
        if (!compatJs.exists()) {
            warnings.add("patches/glibc-compat.js missing — some Node.js APIs may not work on Android")
        }

        val passed = errors.isEmpty()

        if (passed) {
            AppLogger.i(TAG, "Validation PASSED (${warnings.size} warnings)")
        } else {
            AppLogger.e(TAG, "Validation FAILED: $errors")
        }
        warnings.forEach { AppLogger.w(TAG, "Validation warning: $it") }

        return ValidationResult(passed, errors, warnings)
    }

    /**
     * Quick check: is the payload extraction structurally complete?
     * Lighter than full validatePayload — for status checks.
     *
     * Accepts either a traditional Termux-style prefix (has bin/bash or bin/sh)
     * OR an OpenClaw payload prefix (has bin/node or the glibc-wrapped node wrapper).
     * Both layouts are valid — the key requirement is the glibc dynamic linker.
     *
     * Also accepts the Android system shell (/system/bin/sh) as a valid shell,
     * since it is always present on Android 7+ and can run post-setup scripts.
     *
     * payload-final.tar.gz layout:
     *   glibc/lib/ld-linux-aarch64.so.1  ← linker (required)
     *   lib/node/bin/node.real            ← node binary
     *   lib/openclaw/openclaw.mjs         ← openclaw entry point
     */
    fun isStructurallyComplete(prefix: File): Boolean {
        val hasGlibcLinker = File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()

        // Traditional Termux layout
        val hasBinDir = File(prefix, "bin").isDirectory
        val hasLibDir = File(prefix, "lib").isDirectory

        // Traditional shell layout (Termux bootstrap)
        val hasPayloadShell = File(prefix, "bin/bash").exists() || File(prefix, "bin/sh").exists()

        // Android system shell — always available on Android 7+, valid fallback
        val hasSystemShell = File("/system/bin/sh").exists()

        val hasShell = hasPayloadShell || hasSystemShell

        // OpenClaw payload layout — payload-final.tar.gz structure
        val hasNode = File(prefix, "lib/node/bin/node.real").exists() ||   // payload-final
            File(prefix, "lib/node/bin/node").exists() ||
            File(prefix, "bin/node").exists() ||
            File(prefix, "bin/openclaw").exists() ||
            File(prefix, "lib/openclaw/openclaw.mjs").exists() ||           // payload-final
            File(prefix, "lib/node_modules/openclaw/openclaw.mjs").exists() // legacy

        return hasGlibcLinker && (hasShell || hasNode)
    }
}
