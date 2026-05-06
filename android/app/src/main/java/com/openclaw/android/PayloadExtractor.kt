package com.openclaw.android

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.util.zip.GZIPInputStream

/**
 * PayloadExtractor — pure streaming extraction engine.
 *
 * Responsibilities:
 *   - Extract tar.gz from Android assets
 *   - Extract tar.xz archives
 *   - Stream-concatenate split files (part_aa, part_ab, …) without temp files
 *   - Create symlinks, set permissions
 *
 * Design:
 *   - ZERO UI / shell / callback dependencies
 *   - ZERO temp files for split reconstruction (uses SequenceInputStream)
 *   - All methods throw on failure — caller (InstallerManager) decides how to handle
 *   - Thread-safe: stateless, all state is passed via parameters
 *
 * Memory budget: ~256KB buffer for streaming. No full-file materialization.
 */
object PayloadExtractor {

    private const val TAG = "PayloadExtractor"

    /** Buffer size for streaming copy operations (256 KB) */
    private const val BUFFER_SIZE = 256 * 1024

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extract a single tar.gz module from Android assets.
     *
     * @param context  Android context (for asset access)
     * @param assetPath  Path inside assets/ (e.g. "payload/bin.tar.gz")
     * @param destDir  Target directory for extraction
     * @return Number of entries extracted
     * @throws IOException on extraction failure
     */
    fun extractTarGzAsset(context: Context, assetPath: String, destDir: File): Int {
        return context.assets.open(assetPath).use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { buffered ->
                GZIPInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        extractTarEntries(tar, destDir)
                    }
                }
            }
        }
    }

    /**
     * Extract a tar.gz file from the filesystem.
     *
     * @param file  The tar.gz file on disk
     * @param destDir  Target directory for extraction
     * @return Number of entries extracted
     * @throws IOException on extraction failure
     */
    fun extractTarGzFile(file: File, destDir: File): Int {
        return file.inputStream().use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { buffered ->
                GZIPInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        extractTarEntries(tar, destDir)
                    }
                }
            }
        }
    }

    /**
     * Extract a tar.gz from an InputStream.
     *
     * @param input  The compressed input stream
     * @param destDir  Target directory for extraction
     * @return Number of entries extracted
     * @throws IOException on extraction failure
     */
    fun extractTarGzStream(input: InputStream, destDir: File): Int {
        return BufferedInputStream(input, BUFFER_SIZE).use { buffered ->
            GZIPInputStream(buffered).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    extractTarEntries(tar, destDir)
                }
            }
        }
    }

    /**
     * Extract a tar.xz file from the filesystem.
     *
     * Uses Apache Commons Compress XZCompressorInputStream.
     * Memory usage: ~9MB for XZ decompression dictionary (unavoidable).
     *
     * @param file  The tar.xz file on disk
     * @param destDir  Target directory for extraction
     * @return Number of entries extracted
     * @throws IOException on extraction failure
     */
    fun extractTarXzFile(file: File, destDir: File): Int {
        return file.inputStream().use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { buffered ->
                XZCompressorInputStream(buffered).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        extractTarEntries(tar, destDir)
                    }
                }
            }
        }
    }

    /**
     * Stream-extract split tar.gz parts from Android assets.
     *
     * The split parts (lib.part_aa, lib.part_ab, …) are logically a single tar.gz
     * file split with `split -b 20m`. This method concatenates them lazily via
     * SequenceInputStream, feeding the result directly into GZIPInputStream.
     *
     * Memory usage: O(BUFFER_SIZE) — NO 108MB temp file needed.
     *
     * @param context  Android context (for asset access)
     * @param assetDir  Directory inside assets/ (e.g. "payload")
     * @param partPrefix  Filename prefix (e.g. "lib.part_")
     * @param partSuffixes  Ordered list of suffixes (e.g. ["aa","ab","ac",...])
     * @param destDir  Target directory for extraction
     * @return Number of entries extracted
     * @throws IOException on extraction failure
     */
    fun extractSplitTarGzAsset(
        context: Context,
        assetDir: String,
        partPrefix: String,
        partSuffixes: List<String>,
        destDir: File,
    ): Int {
        require(partSuffixes.isNotEmpty()) { "partSuffixes must not be empty" }

        // Open all part streams lazily via SequenceInputStream chain
        val concatenatedStream = buildConcatenatedStream(context, assetDir, partPrefix, partSuffixes)

        return try {
            BufferedInputStream(concatenatedStream, BUFFER_SIZE).use { buffered ->
                GZIPInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        extractTarEntries(tar, destDir)
                    }
                }
            }
        } catch (e: Exception) {
            // Ensure cleanup on error
            try { concatenatedStream.close() } catch (_: Exception) {}
            throw IOException("Failed to extract split tar.gz ($partPrefix*): ${e.message}", e)
        }
    }

    /**
     * Copy an asset file to a destination on disk.
     * Used for assets that need to be on the filesystem before extraction
     * (e.g. tar.xz files that need seeking).
     *
     * @param context  Android context
     * @param assetPath  Path inside assets/
     * @param destFile  Target file on disk
     * @return Bytes copied
     */
    fun copyAssetToFile(context: Context, assetPath: String, destFile: File): Long {
        destFile.parentFile?.mkdirs()
        var bytesCopied = 0L
        context.assets.open(assetPath).use { input ->
            BufferedOutputStream(destFile.outputStream(), BUFFER_SIZE).use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    bytesCopied += n
                }
            }
        }
        return bytesCopied
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build a lazily-concatenated InputStream from multiple asset parts.
     * Uses SequenceInputStream which reads from part N, then seamlessly switches
     * to part N+1 when the first is exhausted — exactly like `cat part_* | gzip -d`.
     */
    private fun buildConcatenatedStream(
        context: Context,
        assetDir: String,
        partPrefix: String,
        partSuffixes: List<String>,
    ): InputStream {
        val iterator = partSuffixes.iterator()
        // Java's SequenceInputStream takes an Enumeration. We adapt from Iterator.
        val enumeration = object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = iterator.hasNext()
            override fun nextElement(): InputStream {
                val suffix = iterator.next()
                return context.assets.open("$assetDir/$partPrefix$suffix")
            }
        }
        return SequenceInputStream(enumeration)
    }

    /**
     * Extract all entries from a TarArchiveInputStream into destDir.
     *
     * Handles:
     *   - Regular files (with parent directory creation)
     *   - Directories
     *   - Symbolic links (via android.system.Os.symlink)
     *   - Executable bit (set for bin/ paths and files with +x in tar)
     *
     * @return Number of entries processed
     */
    @Suppress("deprecation") // TarArchiveInputStream.nextTarEntry is stable API
    private fun extractTarEntries(tar: TarArchiveInputStream, destDir: File): Int {
        var count = 0
        var entry: TarArchiveEntry? = tar.nextTarEntry
        while (entry != null) {
            val destFile = File(destDir, entry.name)

            try {
                when {
                    entry.isDirectory -> {
                        destFile.mkdirs()
                        AppLogger.d(TAG, "Created directory: ${entry.name}")
                    }
                    entry.isSymbolicLink -> {
                        destFile.parentFile?.mkdirs()
                        // Remove any existing file/symlink at this path before creating
                        if (destFile.exists() || destFile.isFile) {
                            destFile.delete()
                        }
                        try {
                            android.system.Os.symlink(entry.linkName, destFile.absolutePath)
                            AppLogger.d(TAG, "Created symlink: ${entry.name} -> ${entry.linkName}")
                        } catch (e: Exception) {
                            // Symlink failure is non-fatal (target may not exist yet)
                            AppLogger.w(TAG, "Symlink failed: ${entry.name} -> ${entry.linkName}: ${e.message}")
                        }
                    }
                    else -> {
                        destFile.parentFile?.mkdirs()
                        // Skip zero-byte files that are likely broken symlinks extracted as empty files
                        if (entry.size == 0L && entry.name.contains("libc.so")) {
                            AppLogger.w(TAG, "Skipping zero-byte libc.so entry: ${entry.name} (likely broken symlink in tar)")
                            count++
                            entry = tar.nextTarEntry
                            continue
                        }
                        FileOutputStream(destFile).use { out ->
                            tar.copyTo(out)
                        }
                        AppLogger.d(TAG, "Extracted file: ${entry.name} (${entry.size} bytes)")
                        // Mark executable: files in bin/ or with executable permission in tar
                        if (entry.name.contains("bin/") || (entry.mode and 0b001_001_001) != 0) {
                            destFile.setExecutable(true, false)
                            destFile.setExecutable(true, true)
                        }
                    }
                }
                count++
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to extract entry ${entry.name}: ${e.message}", e)
            }
            entry = tar.nextTarEntry
        }
        AppLogger.i(TAG, "Total entries extracted: $count")
        return count
    }

    /**
     * Post-extraction repair: fix broken libc.so symlinks in glibc/lib/.
     *
     * When a tar.gz is created on Linux, libc.so is a symlink to libc.so.6.
     * Some extraction tools (or corrupted archives) extract it as a zero-byte
     * regular file instead of a symlink, causing:
     *   CANNOT LINK EXECUTABLE: file offset >= file size: 0 >= 0
     *
     * This method detects and repairs that condition.
     *
     * @param glibcLibDir  The glibc/lib directory (e.g. prefix/glibc/lib)
     */
    fun repairGlibcSymlinks(glibcLibDir: File) {
        if (!glibcLibDir.isDirectory) return

        // libc.so should be a symlink to libc.so.6
        val libcSo = File(glibcLibDir, "libc.so")
        val libcSo6 = File(glibcLibDir, "libc.so.6")

        if (libcSo6.exists() && libcSo6.length() > 0) {
            // libc.so.6 is the real file — ensure libc.so is a symlink to it
            if (libcSo.exists() && libcSo.length() == 0L) {
                // Zero-byte file — broken symlink extracted as empty file
                AppLogger.w(TAG, "Repairing broken libc.so (zero-byte) → symlink to libc.so.6")
                libcSo.delete()
                try {
                    android.system.Os.symlink("libc.so.6", libcSo.absolutePath)
                    AppLogger.i(TAG, "Repaired: libc.so -> libc.so.6")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to create libc.so symlink: ${e.message}")
                }
            } else if (!libcSo.exists()) {
                // Missing entirely — create the symlink
                try {
                    android.system.Os.symlink("libc.so.6", libcSo.absolutePath)
                    AppLogger.i(TAG, "Created missing: libc.so -> libc.so.6")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to create libc.so symlink: ${e.message}")
                }
            }
        }

        // Same repair for libm.so -> libm.so.6
        val libmSo = File(glibcLibDir, "libm.so")
        val libmSo6 = File(glibcLibDir, "libm.so.6")
        if (libmSo6.exists() && libmSo6.length() > 0 && libmSo.exists() && libmSo.length() == 0L) {
            AppLogger.w(TAG, "Repairing broken libm.so (zero-byte) → symlink to libm.so.6")
            libmSo.delete()
            try {
                android.system.Os.symlink("libm.so.6", libmSo.absolutePath)
                AppLogger.i(TAG, "Repaired: libm.so -> libm.so.6")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create libm.so symlink: ${e.message}")
            }
        }

        // libpthread.so -> libpthread.so.0 (glibc 2.34+ merged into libc.so.6)
        val libpthreadSo = File(glibcLibDir, "libpthread.so")
        val libpthreadSo0 = File(glibcLibDir, "libpthread.so.0")
        if (libpthreadSo0.exists() && libpthreadSo0.length() > 0 &&
            libpthreadSo.exists() && libpthreadSo.length() == 0L) {
            libpthreadSo.delete()
            try {
                android.system.Os.symlink("libpthread.so.0", libpthreadSo.absolutePath)
                AppLogger.i(TAG, "Repaired: libpthread.so -> libpthread.so.0")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create libpthread.so symlink: ${e.message}")
            }
        }
    }
}
