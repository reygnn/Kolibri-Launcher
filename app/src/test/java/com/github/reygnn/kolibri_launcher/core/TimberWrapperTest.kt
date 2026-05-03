package com.github.reygnn.kolibri_launcher.core

import android.util.Log
import com.github.reygnn.kolibri_launcher.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * Unit tests for [TimberWrapper].
 *
 * Tests do NOT use the project's `TimberRule` because they need to control
 * [TimberWrapper.preventCrashForTesting] per test — `TimberRule` would force
 * it to `true` for every test, hiding the DEBUG-throw path of [silentError].
 *
 * The `exitProcess(1)` path inside [silentDeath] is intentionally not tested
 * — it would terminate the test JVM. The semantic contract verified here is
 * the same one production callers rely on indirectly: with
 * `preventCrashForTesting=true`, `silentDeath` throws a `RuntimeException`
 * with the right message and cause, and logs FATAL beforehand. The
 * `Thread.sleep + exitProcess` tail is left to manual / integration coverage.
 *
 * `BuildConfig.DEBUG` is fixed per test variant: `testDebugUnitTest` runs
 * with `DEBUG=true`, `testReleaseUnitTest` with `DEBUG=false`. Tests that
 * only make sense in one variant gate themselves with `Assume`.
 */
class TimberWrapperTest {

    private data class LogEntry(
        val priority: Int,
        val tag: String?,
        val message: String,
        val throwable: Throwable?,
    )

    private lateinit var capturedLogs: MutableList<LogEntry>
    private lateinit var captureTree: Timber.Tree

    @Before
    fun setup() {
        capturedLogs = mutableListOf()
        captureTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                capturedLogs.add(LogEntry(priority, tag, message, t))
            }
        }
        Timber.plant(captureTree)
        TimberWrapper.preventCrashForTesting.set(false)
        // Tests below assume DEBUG semantics. Default is `false` because the
        // wrapper now reads its build type from a flag set by KolibriLauncherApp;
        // tests have to wire it themselves.
        TimberWrapper.isDebugBuild = true
        // :domain's KolibriLog forwards through runtime-injected lambdas;
        // KolibriLauncherApp wires them in production. Tests wire the same
        // way so that silentError ends up on the captureTree as before.
        KolibriLog.taggedErrorHandler = { tag, throwable, message ->
            val tree = Timber.tag(tag)
            if (throwable != null) tree.e(throwable, message) else tree.e(message)
        }
    }

    @After
    fun teardown() {
        Timber.uproot(captureTree)
        TimberWrapper.preventCrashForTesting.set(false)
        TimberWrapper.isDebugBuild = false
        // Reset KolibriLog handlers to the safe no-op default for the next test.
        KolibriLog.taggedErrorHandler = { _, _, _ -> }
    }

    // ------------------------------------------------------------------
    // silentError — logging
    // ------------------------------------------------------------------

    @Test
    fun `silentError(message) logs to SILENT_ERROR tag with ERROR priority`() {
        TimberWrapper.preventCrashForTesting.set(true) // suppress DEBUG-throw
        TimberWrapper.silentError("oops")
        assertEquals(1, capturedLogs.size)
        val entry = capturedLogs[0]
        assertEquals(Log.ERROR, entry.priority)
        assertEquals(TimberWrapper.SILENT_LOG_TAG, entry.tag)
        assertEquals("oops", entry.message)
        assertNull(entry.throwable)
    }

    @Test
    fun `silentError(throwable, message) logs throwable and message`() {
        TimberWrapper.preventCrashForTesting.set(true)
        val cause = IllegalStateException("boom")
        TimberWrapper.silentError(cause, "context")
        assertEquals(1, capturedLogs.size)
        val entry = capturedLogs[0]
        assertEquals(Log.ERROR, entry.priority)
        assertEquals(TimberWrapper.SILENT_LOG_TAG, entry.tag)
        // Timber 5.x appends "\n<stackTrace>" when both message and throwable
        // are given — assert prefix, not equality.
        assertTrue(
            "Expected message to start with 'context', was '${entry.message}'",
            entry.message.startsWith("context"),
        )
        assertSame(cause, entry.throwable)
    }

    @Test
    fun `silentError(throwable) reaches the tree with tag and throwable`() {
        TimberWrapper.preventCrashForTesting.set(true)
        val cause = IllegalStateException("boom")
        TimberWrapper.silentError(cause)
        assertEquals(1, capturedLogs.size)
        val entry = capturedLogs[0]
        // Don't pin the message format — Timber synthesizes one from the
        // stack trace when no explicit message is given. The contract is
        // that tag and throwable both arrive at the tree.
        assertEquals(Log.ERROR, entry.priority)
        assertEquals(TimberWrapper.SILENT_LOG_TAG, entry.tag)
        assertSame(cause, entry.throwable)
    }

    // ------------------------------------------------------------------
    // silentError — throw vs no-throw
    // ------------------------------------------------------------------

    @Test
    fun `silentError throws in DEBUG when preventCrashForTesting is false`() {
        Assume.assumeTrue("Only meaningful in DEBUG variant", BuildConfig.DEBUG)
        TimberWrapper.preventCrashForTesting.set(false)
        try {
            TimberWrapper.silentError("debug-throw")
            fail("Expected RuntimeException, but silentError returned normally")
        } catch (e: RuntimeException) {
            assertEquals("SILENT_ERROR caught: debug-throw", e.message)
            assertNull(e.cause)
        }
    }

    @Test
    fun `silentError chains cause when throwable form throws in DEBUG`() {
        Assume.assumeTrue("Only meaningful in DEBUG variant", BuildConfig.DEBUG)
        TimberWrapper.preventCrashForTesting.set(false)
        val cause = IllegalArgumentException("inner")
        try {
            TimberWrapper.silentError(cause, "outer")
            fail("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("SILENT_ERROR caught: outer", e.message)
            assertSame(cause, e.cause)
        }
    }

    @Test
    fun `silentError throwable-only overload throws in DEBUG with throwable's message`() {
        Assume.assumeTrue("Only meaningful in DEBUG variant", BuildConfig.DEBUG)
        TimberWrapper.preventCrashForTesting.set(false)
        val cause = IllegalStateException("explicit-msg")
        try {
            TimberWrapper.silentError(cause)
            fail("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("SILENT_ERROR caught: explicit-msg", e.message)
            assertSame(cause, e.cause)
        }
    }

    @Test
    fun `silentError does not throw in RELEASE`() {
        Assume.assumeFalse("Only meaningful in RELEASE variant", BuildConfig.DEBUG)
        TimberWrapper.preventCrashForTesting.set(false)
        // Override the @Before's `isDebugBuild = true` for this RELEASE-semantics test.
        TimberWrapper.isDebugBuild = false
        // No fail() needed — assertion is implicit by lack of throw.
        TimberWrapper.silentError("release-no-throw")
    }

    @Test
    fun `silentError does not throw when preventCrashForTesting is true`() {
        TimberWrapper.preventCrashForTesting.set(true)
        TimberWrapper.silentError("test-mode-no-throw")
    }

    // ------------------------------------------------------------------
    // silentDeath — throw contract
    // ------------------------------------------------------------------

    @Test
    fun `silentDeath(message) throws SILENT_DEATH when preventCrashForTesting is true`() {
        TimberWrapper.preventCrashForTesting.set(true)
        try {
            TimberWrapper.silentDeath("dying")
            fail("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("SILENT_DEATH: dying", e.message)
            assertNull(e.cause)
        }
    }

    @Test
    fun `silentDeath(throwable, message) chains the cause`() {
        TimberWrapper.preventCrashForTesting.set(true)
        val cause = IllegalStateException("simulated-fatal")
        try {
            TimberWrapper.silentDeath(cause, "fatal")
            fail("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("SILENT_DEATH: fatal", e.message)
            assertSame(cause, e.cause)
        }
    }

    // ------------------------------------------------------------------
    // silentDeath — logging
    // ------------------------------------------------------------------

    @Test
    fun `silentDeath logs FATAL prefix on SILENT_ERROR tag before throwing`() {
        TimberWrapper.preventCrashForTesting.set(true)
        try {
            TimberWrapper.silentDeath("foo")
        } catch (expected: RuntimeException) {
            // expected — we want to inspect the log written before the throw
        }
        assertEquals(1, capturedLogs.size)
        val entry = capturedLogs[0]
        assertEquals(Log.ERROR, entry.priority)
        assertEquals(TimberWrapper.SILENT_LOG_TAG, entry.tag)
        assertEquals("FATAL: foo", entry.message)
        assertNull(entry.throwable)
    }

    @Test
    fun `silentDeath with cause logs throwable alongside FATAL message`() {
        TimberWrapper.preventCrashForTesting.set(true)
        val cause = IllegalStateException("inner")
        try {
            TimberWrapper.silentDeath(cause, "lying-state")
        } catch (expected: RuntimeException) {
            // expected
        }
        assertEquals(1, capturedLogs.size)
        val entry = capturedLogs[0]
        assertEquals(Log.ERROR, entry.priority)
        assertEquals(TimberWrapper.SILENT_LOG_TAG, entry.tag)
        // Timber appends the stack trace; check the human-readable prefix.
        assertTrue(
            "Expected message to start with 'FATAL: lying-state', was '${entry.message}'",
            entry.message.startsWith("FATAL: lying-state"),
        )
        assertSame(cause, entry.throwable)
    }
}
