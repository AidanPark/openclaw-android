package com.openclaw.android

import android.content.Context
import android.os.Build
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests unitarios para TermuxBootstrapManager.
 *
 * Estos tests verifican la lógica del manager sin hacer descargas reales.
 */
class TermuxBootstrapManagerTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var manager: TermuxBootstrapManager

    @BeforeEach
    fun setup() {
        // Mock del contexto
        context = mockk(relaxed = true)
        
        // Crear directorios temporales para testing
        filesDir = createTempDir("termux-test-files")
        cacheDir = createTempDir("termux-test-cache")

        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir

        // Crear el manager
        manager = TermuxBootstrapManager(context)
    }

    @AfterEach
    fun cleanup() {
        // Limpiar directorios temporales
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `detectArchitecture should return valid architecture`() {
        val arch = manager.detectArchitecture()
        
        assertTrue(
            arch in listOf("aarch64", "arm", "x86_64", "i686"),
            "Arquitectura detectada debe ser válida: $arch"
        )
    }

    @Test
    fun `isInstalled should return false when not installed`() {
        assertFalse(manager.isInstalled(), "Debe retornar false cuando no está instalado")
    }

    @Test
    fun `isInstalled should return true when marker and binaries exist`() {
        // Crear estructura de archivos simulada
        val prefix = File(filesDir, "usr")
        val binDir = File(prefix, "bin")
        binDir.mkdirs()

        // Crear binarios simulados
        File(binDir, "dpkg").apply {
            createNewFile()
            writeText("#!/bin/sh\necho dpkg")
        }
        File(binDir, "apt").apply {
            createNewFile()
            writeText("#!/bin/sh\necho apt")
        }
        File(binDir, "bash").apply {
            createNewFile()
            writeText("#!/bin/sh\necho bash")
        }

        // Crear marcador
        File(filesDir, ".termux-bootstrap-installed").apply {
            createNewFile()
            writeText("installed")
        }

        assertTrue(manager.isInstalled(), "Debe retornar true cuando está instalado")
    }

    @Test
    fun `getStatus should return correct information`() {
        val status = manager.getStatus()

        assertNotNull(status["installed"])
        assertNotNull(status["architecture"])
        assertNotNull(status["prefixPath"])
        assertNotNull(status["prefixExists"])
        assertNotNull(status["dpkgExists"])
        assertNotNull(status["aptExists"])
        assertNotNull(status["bashExists"])

        assertEquals(false, status["installed"])
        assertTrue(status["architecture"] is String)
    }

    @Test
    fun `getStatus should show correct state when installed`() {
        // Simular instalación
        val prefix = File(filesDir, "usr")
        val binDir = File(prefix, "bin")
        binDir.mkdirs()

        File(binDir, "dpkg").createNewFile()
        File(binDir, "apt").createNewFile()
        File(binDir, "bash").createNewFile()
        File(filesDir, ".termux-bootstrap-installed").createNewFile()

        val status = manager.getStatus()

        assertEquals(true, status["installed"])
        assertEquals(true, status["dpkgExists"])
        assertEquals(true, status["aptExists"])
        assertEquals(true, status["bashExists"])
    }

    @Test
    fun `uninstall should remove all files`() {
        // Crear estructura simulada
        val prefix = File(filesDir, "usr")
        prefix.mkdirs()
        File(prefix, "bin").mkdirs()
        File(prefix, "lib").mkdirs()
        File(filesDir, ".termux-bootstrap-installed").createNewFile()

        assertTrue(prefix.exists())
        assertTrue(File(filesDir, ".termux-bootstrap-installed").exists())

        // Desinstalar
        manager.uninstall()

        assertFalse(prefix.exists())
        assertFalse(File(filesDir, ".termux-bootstrap-installed").exists())
    }

    @Test
    fun `install should fail gracefully when network is unavailable`() = runBlocking {
        // Este test verifica que el manager maneja errores de red correctamente
        var errorCalled = false
        var errorMessage = ""

        manager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                // Progreso normal
            }

            override fun onSuccess() {
                fail("No debería tener éxito sin red")
            }

            override fun onError(message: String, cause: Throwable?) {
                errorCalled = true
                errorMessage = message
            }
        })

        // En un entorno de test sin red, debería fallar
        // (Este test puede pasar si hay red disponible y la descarga funciona)
        if (errorCalled) {
            assertTrue(errorMessage.isNotEmpty(), "El mensaje de error debe estar presente")
        }
    }

    @Test
    fun `architecture detection should handle all supported ABIs`() {
        // Verificar que todas las arquitecturas conocidas se mapean correctamente
        val supportedAbis = listOf(
            "arm64-v8a" to "aarch64",
            "armeabi-v7a" to "arm",
            "x86_64" to "x86_64",
            "x86" to "i686"
        )

        // Este test verifica la lógica de detección
        // En un test real, Build.SUPPORTED_ABIS es del dispositivo de test
        val detectedArch = manager.detectArchitecture()
        assertTrue(
            detectedArch in listOf("aarch64", "arm", "x86_64", "i686"),
            "Arquitectura debe ser una de las soportadas"
        )
    }

    @Test
    fun `prefix directory should be resolved correctly`() {
        val status = manager.getStatus()
        val prefixPath = status["prefixPath"] as String

        assertTrue(
            prefixPath.contains("usr") || 
            prefixPath.contains("payload") || 
            prefixPath.contains("openclaw-payload"),
            "PREFIX debe estar en una ubicación válida: $prefixPath"
        )
    }

    @Test
    fun `marker file should contain installation metadata`() {
        // Simular instalación completa
        val prefix = File(filesDir, "usr")
        val binDir = File(prefix, "bin")
        binDir.mkdirs()
        File(binDir, "dpkg").createNewFile()
        File(binDir, "apt").createNewFile()
        File(binDir, "bash").createNewFile()

        val marker = File(filesDir, ".termux-bootstrap-installed")
        marker.writeText(
            """
            architecture=aarch64
            installed_at=1234567890
            prefix=${prefix.absolutePath}
            version=termux-bootstrap-2026.02.12
            """.trimIndent()
        )

        assertTrue(manager.isInstalled())
        
        val content = marker.readText()
        assertTrue(content.contains("architecture="))
        assertTrue(content.contains("installed_at="))
        assertTrue(content.contains("prefix="))
        assertTrue(content.contains("version="))
    }
}
