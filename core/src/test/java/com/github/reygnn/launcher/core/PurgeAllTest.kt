package com.github.reygnn.launcher.core

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/** [purgeAll], the shared factory-reset loop of both apps (2b cleanup). */
class PurgeAllTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val purged = mutableListOf<String>()

    private fun store(name: String, failure: Throwable? = null) = name to object : Purgeable {
        override suspend fun purgeRepository() {
            failure?.let { throw it }
            purged += name
        }
    }

    @Test
    fun every_store_is_purged_in_order() = runTest(mainDispatcherRule.testDispatcher) {
        val ok = purgeAll(listOf(store("a"), store("b"), store("c")))

        assertThat(ok).isTrue()
        assertThat(purged).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun a_failing_store_is_counted_and_the_others_still_run() = runTest(mainDispatcherRule.testDispatcher) {
        val ok = purgeAll(listOf(store("a"), store("b", IOException("disk full")), store("c")))

        assertThat(ok).isFalse()
        assertThat(purged).containsExactly("a", "c").inOrder()
    }

    @Test
    fun a_cancellation_propagates_and_stops_the_loop() = runTest(mainDispatcherRule.testDispatcher) {
        assertFailsWith<CancellationException> {
            purgeAll(listOf(store("a"), store("b", CancellationException("left")), store("c")))
        }
        assertThat(purged).containsExactly("a")
    }

    @Test
    fun in_a_debug_build_the_first_failure_breaks_out() = runTest(mainDispatcherRule.testDispatcher) {
        // The house rule of TimberWrapper.silentError: throw in DEBUG. Pinned here so the KDoc of
        // purgeAll ("isolation applies in release builds and unit tests") stays true.
        val wasDebug = TimberWrapper.isDebugBuild
        val wasPrevented = TimberWrapper.preventCrashForTesting.get()
        TimberWrapper.isDebugBuild = true
        TimberWrapper.preventCrashForTesting.set(false)
        try {
            val error = assertFailsWith<RuntimeException> {
                purgeAll(listOf(store("a"), store("b", IOException("disk full")), store("c")))
            }
            assertThat(error).hasCauseThat().isInstanceOf(IOException::class.java)
            assertThat(purged).containsExactly("a")
        } finally {
            TimberWrapper.isDebugBuild = wasDebug
            TimberWrapper.preventCrashForTesting.set(wasPrevented)
        }
    }
}
