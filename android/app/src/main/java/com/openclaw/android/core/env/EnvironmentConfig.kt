package com.openclaw.android.core.env

import java.io.File

/**
 * EnvironmentConfig — immutable snapshot of all resolved paths for a session.
 *
 * Created once per app launch by EnvironmentResolver and passed around.
 * Eliminates repeated path resolution and makes dependencies explicit.
 */
data class EnvironmentConfig(
    /** App sandbox root: context.filesDir */
    val filesDir: File,
    /** Home directory: filesDir/home */
    val homeDir: File,
    /** Payload directory: homeDir/payload or homeDir/openclaw-payload */
    val payloadDir: File,
    /** Prefix (bin, lib, etc.): payloadDir or filesDir/usr */
    val prefix: File,
    /** Temp directory: filesDir/tmp */
    val tmpDir: File,
    /** .openclaw-android dir: homeDir/.openclaw-android */
    val ocaDir: File,
    /** glibc lib dir: payloadDir/glibc/lib */
    val glibcLib: File,
    /** glibc dynamic linker: glibcLib/ld-linux-aarch64.so.1 */
    val linker: File,
    /** node binary: payloadDir/glibc/bin/node */
    val nodeBin: File,
    /** openclaw.mjs: payloadDir/openclaw/openclaw.mjs */
    val openClawMjs: File,
    /** SSL cert bundle */
    val certPem: File,
) {
    val isGlibcReady: Boolean get() = linker.exists() && linker.length() > 100_000
    val isNodeReady: Boolean get() = nodeBin.exists() && nodeBin.length() > 1_000_000
    val isOpenClawReady: Boolean get() = openClawMjs.exists()
    val isFullyReady: Boolean get() = isGlibcReady && isNodeReady && isOpenClawReady
}
