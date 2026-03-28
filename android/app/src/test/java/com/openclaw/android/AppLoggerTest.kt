package com.openclaw.android

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppLoggerTest {
    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `v delegates to Log v`() {
        AppLogger.v("TAG", "msg")
        verify { Log.v("TAG", "msg") }
    }

    @Test
    fun `d delegates to Log d`() {
        AppLogger.d("TAG", "msg")
        verify { Log.d("TAG", "msg") }
    }

    @Test
    fun `i delegates to Log i`() {
        AppLogger.i("TAG", "msg")
        verify { Log.i("TAG", "msg") }
    }

    @Test
    fun `w delegates to Log w`() {
        AppLogger.w("TAG", "msg")
        verify { Log.w("TAG", "msg") }
    }

    @Test
    fun `w with throwable delegates to Log w`() {
        val ex = RuntimeException("test")
        AppLogger.w("TAG", "msg", ex)
        verify { Log.w("TAG", "msg", ex) }
    }

    @Test
    fun `e delegates to Log e`() {
        AppLogger.e("TAG", "msg")
        verify { Log.e("TAG", "msg") }
    }

    @Test
    fun `e with throwable delegates to Log e`() {
        val ex = RuntimeException("test")
        AppLogger.e("TAG", "msg", ex)
        verify { Log.e("TAG", "msg", ex) }
    }
}
