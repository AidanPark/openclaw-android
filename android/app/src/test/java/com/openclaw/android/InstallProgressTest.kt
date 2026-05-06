package com.openclaw.android

import com.openclaw.android.core.install.InstallProgress
import com.openclaw.android.core.install.NoOpProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for InstallProgress interface and NoOpProgress implementation.
 */
class InstallProgressTest {

    // ─── NoOpProgress ─────────────────────────────────────────────────────────

    @Test
    fun `NoOpProgress onProgress does not throw`() {
        NoOpProgress.onProgress(0, "starting")
        NoOpProgress.onProgress(50, "halfway")
        NoOpProgress.onProgress(100, "done")
    }

    @Test
    fun `NoOpProgress onSuccess does not throw`() {
        NoOpProgress.onSuccess()
    }

    @Test
    fun `NoOpProgress onError does not throw`() {
        NoOpProgress.onError("something went wrong")
        NoOpProgress.onError("with cause", RuntimeException("test"))
    }

    @Test
    fun `NoOpProgress onError with null cause does not throw`() {
        NoOpProgress.onError("error", null)
    }

    // ─── Custom InstallProgress implementation ────────────────────────────────

    @Test
    fun `custom InstallProgress receives progress callbacks`() {
        val events = mutableListOf<Pair<Int, String>>()
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) {
                events.add(percent to message)
            }
            override fun onSuccess() {}
            override fun onError(message: String, cause: Throwable?) {}
        }

        progress.onProgress(10, "step 1")
        progress.onProgress(50, "step 2")
        progress.onProgress(100, "done")

        assertEquals(3, events.size)
        assertEquals(10 to "step 1", events[0])
        assertEquals(50 to "step 2", events[1])
        assertEquals(100 to "done", events[2])
    }

    @Test
    fun `custom InstallProgress receives success callback`() {
        var successCalled = false
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) {}
            override fun onSuccess() { successCalled = true }
            override fun onError(message: String, cause: Throwable?) {}
        }

        progress.onSuccess()
        assertEquals(true, successCalled)
    }

    @Test
    fun `custom InstallProgress receives error callback with cause`() {
        var receivedMessage: String? = null
        var receivedCause: Throwable? = null
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) {}
            override fun onSuccess() {}
            override fun onError(message: String, cause: Throwable?) {
                receivedMessage = message
                receivedCause = cause
            }
        }

        val ex = RuntimeException("network failure")
        progress.onError("Installation failed", ex)

        assertEquals("Installation failed", receivedMessage)
        assertEquals(ex, receivedCause)
    }

    @Test
    fun `custom InstallProgress receives error callback without cause`() {
        var receivedCause: Throwable? = RuntimeException("should be replaced")
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) {}
            override fun onSuccess() {}
            override fun onError(message: String, cause: Throwable?) {
                receivedCause = cause
            }
        }

        progress.onError("error without cause")
        assertNull(receivedCause)
    }

    // ─── Progress percent boundary values ────────────────────────────────────

    @Test
    fun `progress percent 0 is valid`() {
        val received = mutableListOf<Int>()
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) { received.add(percent) }
            override fun onSuccess() {}
            override fun onError(message: String, cause: Throwable?) {}
        }
        progress.onProgress(0, "start")
        assertEquals(0, received[0])
    }

    @Test
    fun `progress percent 100 is valid`() {
        val received = mutableListOf<Int>()
        val progress = object : InstallProgress {
            override fun onProgress(percent: Int, message: String) { received.add(percent) }
            override fun onSuccess() {}
            override fun onError(message: String, cause: Throwable?) {}
        }
        progress.onProgress(100, "complete")
        assertEquals(100, received[0])
    }
}
