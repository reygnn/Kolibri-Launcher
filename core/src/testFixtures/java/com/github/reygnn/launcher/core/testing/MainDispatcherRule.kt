package com.github.reygnn.launcher.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The ONE `MainDispatcherRule` for the whole monorepo (Kolibri, Nyx, and every
 * shared module). For the duration of a test it swaps `Dispatchers.Main` for a
 * single lazy [StandardTestDispatcher]; tests pass the same instance to
 * `runTest(mainDispatcherRule.testDispatcher)` and to every production object
 * that takes a dispatcher (see TESTING_CONVENTIONS.kt).
 *
 * Deliberately `final`, no-arg, and with exactly one accessor. The previous
 * shape — an open base with a per-module dispatcher argument, three thin
 * subclasses, and a `dispatcher` alias for Nyx — was a standing invitation to
 * drift (SPEC_NYX_REWRITE, Phase 1a). A module that believes it needs a
 * different dispatcher changes the conventions first, not this class.
 *
 * Lazy execution is the point: launched coroutines run only on
 * `advanceUntilIdle()` / `runCurrent()`, so ordering is explicit and
 * "launched but never awaited" bugs surface in the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {

    val testDispatcher: TestDispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
