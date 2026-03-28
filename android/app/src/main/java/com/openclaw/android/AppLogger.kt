package com.openclaw.android

import android.util.Log

/**
 * Centralized logging wrapper.
 * All app code should use AppLogger instead of android.util.Log directly.
 * detekt ForbiddenMethodCall enforces this rule for new code.
 */
@Suppress("ForbiddenMethodCall")
object AppLogger {
    fun v(
        tag: String,
        message: String,
    ) = Log.v(tag, message)

    fun d(
        tag: String,
        message: String,
    ) = Log.d(tag, message)

    fun i(
        tag: String,
        message: String,
    ) = Log.i(tag, message)

    fun w(
        tag: String,
        message: String,
    ) = Log.w(tag, message)

    fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = Log.w(tag, message, throwable)

    fun e(
        tag: String,
        message: String,
    ) = Log.e(tag, message)

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) = Log.e(tag, message, throwable)
}
