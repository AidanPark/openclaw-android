package com.openclaw.android

import com.openclaw.android.core.env.EnvironmentResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * EnvironmentResolverTest — migrated from EnvironmentBuilderTest.
 * EnvironmentBuilder was removed (was a shim over EnvironmentResolver).
 */
class EnvironmentBuilderTest {
    private lateinit var env: Map<String, String>

    @BeforeEach
    fun setup() {
        // Use EnvironmentResolver directly — EnvironmentBuilder removed
        val filesDir = File(System.getenv("HOME") ?: "/data/local/tmp")
        val config = EnvironmentResolver.resolve(filesDir)
        env = EnvironmentResolver.buildEnvMap(config, "com.openclaw.android")
    }

    @Test
    fun `PREFIX is set and non-empty`() {
        assertNotNull(env["PREFIX"])
        assertTrue(env["PREFIX"]!!.isNotEmpty())
    }

    @Test
    fun `HOME is set and non-empty`() {
        assertNotNull(env["HOME"])
        assertTrue(env["HOME"]!!.isNotEmpty())
    }

    @Test
    fun `TMPDIR is set and non-empty`() {
        assertNotNull(env["TMPDIR"])
        assertTrue(env["TMPDIR"]!!.isNotEmpty())
    }

    @Test
    fun `PATH contains openclaw bin`() {
        assertTrue(env["PATH"]!!.contains(".openclaw-android/bin"))
    }

    @Test
    fun `PATH contains usr bin`() {
        assertTrue(env["PATH"]!!.contains("/usr/bin") || env["PATH"]!!.contains("bin"))
    }

    @Test
    fun `PATH contains applets`() {
        assertTrue(env["PATH"]!!.contains("applets"))
    }

    @Test
    fun `PATH has openclaw bin before usr bin`() {
        val path = env["PATH"]!!
        val openclawIdx = path.indexOf(".openclaw-android/bin")
        val usrBinIdx = path.indexOf("/usr/bin")
        if (openclawIdx >= 0 && usrBinIdx >= 0) {
            assertTrue(openclawIdx < usrBinIdx, "openclaw/bin must precede usr/bin in PATH")
        }
    }

    @Test
    fun `LD_LIBRARY_PATH is set`() {
        assertNotNull(env["LD_LIBRARY_PATH"])
        assertTrue(env["LD_LIBRARY_PATH"]!!.contains("lib"))
    }

    @Test
    fun `TERMUX_PREFIX matches PREFIX`() {
        assertEquals(env["PREFIX"], env["TERMUX_PREFIX"])
    }

    @Test
    fun `TERMUX__PREFIX matches PREFIX`() {
        assertEquals(env["PREFIX"], env["TERMUX__PREFIX"])
    }

    @Test
    fun `APT_CONFIG points to apt conf`() {
        assertTrue(env["APT_CONFIG"]!!.endsWith("/etc/apt/apt.conf"))
    }

    @Test
    fun `DPKG_ADMINDIR is set`() {
        assertNotNull(env["DPKG_ADMINDIR"])
        assertTrue(env["DPKG_ADMINDIR"]!!.contains("dpkg"))
    }

    @Test
    fun `DPKG_ROOT matches PREFIX`() {
        assertEquals(env["PREFIX"], env["DPKG_ROOT"])
    }

    @Test
    fun `SSL_CERT_FILE points to cert pem`() {
        assertTrue(env["SSL_CERT_FILE"]!!.endsWith("cert.pem"))
    }

    @Test
    fun `CURL_CA_BUNDLE matches SSL_CERT_FILE`() {
        assertEquals(env["SSL_CERT_FILE"], env["CURL_CA_BUNDLE"])
    }

    @Test
    fun `GIT_SSL_CAINFO matches SSL_CERT_FILE`() {
        assertEquals(env["SSL_CERT_FILE"], env["GIT_SSL_CAINFO"])
    }

    @Test
    fun `GIT_CONFIG_NOSYSTEM is set to 1`() {
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
    }

    @Test
    fun `GIT_EXEC_PATH points to git-core`() {
        assertTrue(env["GIT_EXEC_PATH"]!!.contains("git-core"))
    }

    @Test
    fun `LANG is en_US UTF-8`() {
        assertEquals("en_US.UTF-8", env["LANG"])
    }

    @Test
    fun `TERM is xterm-256color`() {
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `ANDROID_DATA is set`() {
        assertEquals("/data", env["ANDROID_DATA"])
    }

    @Test
    fun `ANDROID_ROOT is set`() {
        assertEquals("/system", env["ANDROID_ROOT"])
    }

    @Test
    fun `OA_GLIBC is set to 1`() {
        assertEquals("1", env["OA_GLIBC"])
    }

    @Test
    fun `CONTAINER is set to 1`() {
        assertEquals("1", env["CONTAINER"])
    }

    @Test
    fun `CLAWDHUB_WORKDIR is set`() {
        assertNotNull(env["CLAWDHUB_WORKDIR"])
        assertTrue(env["CLAWDHUB_WORKDIR"]!!.contains(".openclaw"))
    }

    @Test
    fun `resolve produces consistent results`() {
        val filesDir = File(System.getenv("HOME") ?: "/data/local/tmp")
        val config = EnvironmentResolver.resolve(filesDir)
        val env2 = EnvironmentResolver.buildEnvMap(config, "com.openclaw.android")
        assertEquals(env["HOME"], env2["HOME"])
        assertEquals(env["PREFIX"], env2["PREFIX"])
        assertEquals(env["PATH"], env2["PATH"])
    }
}
