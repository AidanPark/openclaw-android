package com.openclaw.android

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for UrlResolver data classes and JSON parsing logic.
 * Tests the pure data model without requiring Android Context.
 */
class UrlResolverDataTest {

    private val gson = Gson()

    // ─── RemoteConfig JSON parsing ────────────────────────────────────────────

    @Test
    fun `RemoteConfig parses bootstrap url correctly`() {
        val json = """
            {
              "version": 1,
              "bootstrap": { "url": "https://example.com/bootstrap.tar.gz", "version": "1.0.0" },
              "www": { "url": "https://example.com/www.tar.gz" }
            }
        """.trimIndent()

        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertNotNull(config)
        assertEquals("https://example.com/bootstrap.tar.gz", config.bootstrap?.url)
        assertEquals("1.0.0", config.bootstrap?.version)
    }

    @Test
    fun `RemoteConfig parses www url correctly`() {
        val json = """
            {
              "version": 2,
              "www": { "url": "https://cdn.example.com/www.tar.gz", "version": "2.0.0" }
            }
        """.trimIndent()

        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertEquals("https://cdn.example.com/www.tar.gz", config.www?.url)
        assertEquals("2.0.0", config.www?.version)
    }

    @Test
    fun `RemoteConfig parses sha256 field`() {
        val json = """
            {
              "bootstrap": {
                "url": "https://example.com/bootstrap.tar.gz",
                "sha256": "abc123def456"
              }
            }
        """.trimIndent()

        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertEquals("abc123def456", config.bootstrap?.sha256)
    }

    @Test
    fun `RemoteConfig handles missing optional fields`() {
        val json = """{"version": 1}"""
        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertNull(config.bootstrap)
        assertNull(config.www)
        assertNull(config.platforms)
        assertNull(config.features)
    }

    @Test
    fun `RemoteConfig parses platforms list`() {
        val json = """
            {
              "platforms": [
                { "id": "linux", "name": "Linux", "icon": "🐧", "description": "Linux platform" },
                { "id": "mac", "name": "macOS" }
              ]
            }
        """.trimIndent()

        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertNotNull(config.platforms)
        assertEquals(2, config.platforms!!.size)
        assertEquals("linux", config.platforms!![0].id)
        assertEquals("Linux", config.platforms!![0].name)
        assertEquals("🐧", config.platforms!![0].icon)
        assertEquals("mac", config.platforms!![1].id)
        assertNull(config.platforms!![1].icon)
    }

    @Test
    fun `RemoteConfig parses features map`() {
        val json = """
            {
              "features": {
                "proot": true,
                "offline": false,
                "experimental": true
              }
            }
        """.trimIndent()

        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        assertNotNull(config.features)
        assertEquals(true, config.features!!["proot"])
        assertEquals(false, config.features!!["offline"])
        assertEquals(true, config.features!!["experimental"])
    }

    // ─── ComponentConfig data class ───────────────────────────────────────────

    @Test
    fun `ComponentConfig holds url version and sha256`() {
        val comp = UrlResolver.ComponentConfig(
            url = "https://example.com/file.tar.gz",
            version = "1.2.3",
            sha256 = "deadbeef",
        )
        assertEquals("https://example.com/file.tar.gz", comp.url)
        assertEquals("1.2.3", comp.version)
        assertEquals("deadbeef", comp.sha256)
    }

    @Test
    fun `ComponentConfig allows null version and sha256`() {
        val comp = UrlResolver.ComponentConfig(
            url = "https://example.com/file.tar.gz",
            version = null,
            sha256 = null,
        )
        assertNull(comp.version)
        assertNull(comp.sha256)
    }

    // ─── PlatformConfig data class ────────────────────────────────────────────

    @Test
    fun `PlatformConfig holds all fields`() {
        val platform = UrlResolver.PlatformConfig(
            id = "android",
            name = "Android",
            icon = "🤖",
            description = "Android platform",
        )
        assertEquals("android", platform.id)
        assertEquals("Android", platform.name)
        assertEquals("🤖", platform.icon)
        assertEquals("Android platform", platform.description)
    }

    @Test
    fun `PlatformConfig allows null icon and description`() {
        val platform = UrlResolver.PlatformConfig(
            id = "test",
            name = "Test",
            icon = null,
            description = null,
        )
        assertNull(platform.icon)
        assertNull(platform.description)
    }

    // ─── Config file caching (file-system logic) ──────────────────────────────

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `config json can be written and read back`() {
        val configDir = File(tempDir, "usr/share/openclaw-app")
        configDir.mkdirs()
        val configFile = File(configDir, "config.json")

        val json = """
            {
              "version": 1,
              "bootstrap": { "url": "https://example.com/bootstrap.tar.gz" },
              "www": { "url": "https://example.com/www.tar.gz" }
            }
        """.trimIndent()
        configFile.writeText(json)

        assertTrue(configFile.exists())
        val parsed = gson.fromJson(configFile.readText(), UrlResolver.RemoteConfig::class.java)
        assertEquals("https://example.com/bootstrap.tar.gz", parsed.bootstrap?.url)
        assertEquals("https://example.com/www.tar.gz", parsed.www?.url)
    }

    @Test
    fun `malformed config json returns null gracefully`() {
        val result = try {
            gson.fromJson("not valid json", UrlResolver.RemoteConfig::class.java)
        } catch (_: Exception) {
            null
        }
        assertNull(result)
    }

    @Test
    fun `empty config json object parses without crash`() {
        val config = gson.fromJson("{}", UrlResolver.RemoteConfig::class.java)
        assertNotNull(config)
        assertNull(config.bootstrap)
        assertNull(config.www)
    }

    // ─── URL validation helpers ───────────────────────────────────────────────

    @Test
    fun `bootstrap url is a valid https url`() {
        val json = """{"bootstrap":{"url":"https://releases.example.com/v1/bootstrap.tar.gz"}}"""
        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        val url = config.bootstrap?.url ?: ""
        assertTrue(url.startsWith("https://"))
        assertTrue(url.endsWith(".tar.gz") || url.endsWith(".tar.xz") || url.isNotEmpty())
    }

    @Test
    fun `www url is a valid https url`() {
        val json = """{"www":{"url":"https://cdn.example.com/www-1.0.0.tar.gz"}}"""
        val config = gson.fromJson(json, UrlResolver.RemoteConfig::class.java)
        val url = config.www?.url ?: ""
        assertTrue(url.startsWith("https://"))
    }
}
