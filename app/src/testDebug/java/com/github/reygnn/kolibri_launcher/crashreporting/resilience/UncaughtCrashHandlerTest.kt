package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [UncaughtCrashHandler] flow (C.1/C2): delegate to the default (ACRA)
 * handler, then always reach the backstop kill — even for an OOM and even when
 * the default handler throws. Runs under Robolectric because the default-handler
 * failure swallow logs via `android.util.Log.e`. The kill is injected so the JVM
 * is not actually terminated.
 */
@RunWith(RobolectricTestRunner::class)
class UncaughtCrashHandlerTest {

    private var delegated = 0
    private var kills = 0

    private val recordingDefault = Thread.UncaughtExceptionHandler { _, _ -> delegated++ }

    private fun handler(default: Thread.UncaughtExceptionHandler? = recordingDefault) =
        UncaughtCrashHandler(defaultHandler = default, killSwitch = { kills++ })

    @Test
    fun `delegates to the default handler then backstop-kills`() {
        handler().uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, delegated)
        assertEquals(1, kills)
    }

    @Test
    fun `an OutOfMemoryError still delegates and backstop-kills`() {
        handler().uncaughtException(Thread.currentThread(), OutOfMemoryError("oom"))

        assertEquals(1, delegated)
        assertEquals(1, kills)
    }

    @Test
    fun `a throwing default handler still reaches the backstop kill`() {
        val throwingDefault = Thread.UncaughtExceptionHandler { _, _ -> throw RuntimeException("acra veto") }

        // Must not propagate; the backstop kill is still reached.
        handler(throwingDefault).uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, kills)
    }

    @Test
    fun `a null default handler still backstop-kills`() {
        handler(default = null).uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, kills)
    }
}
