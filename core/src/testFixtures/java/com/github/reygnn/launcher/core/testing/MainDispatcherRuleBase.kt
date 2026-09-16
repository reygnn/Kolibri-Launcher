package com.github.reygnn.launcher.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Shared plumbing for the family's `MainDispatcherRule`: for the duration of a
 * test it swaps `Dispatchers.Main` for one [TestDispatcher] (convention: ONE
 * dispatcher source, passed to both `runTest(...)` and any code that reads Main).
 *
 * The TestWatcher logic lives here once; each module keeps a tiny no-arg
 * `MainDispatcherRule` subclass that supplies its dispatcher. The whole family
 * (Kolibri, Nyx, :common-ui) now uses `StandardTestDispatcher` — the
 * family-documented default: lazy execution that runs launched coroutines only on
 * `advanceUntilIdle`/`runCurrent`, so ordering is explicit and "launched but never
 * awaited" bugs surface in the test. (Kolibri used the eager `UnconfinedTestDispatcher`
 * until its suite was converted; the per-module constructor argument is kept so a
 * module could still diverge if it ever had reason to.)
 *
 * Two accessors expose the same instance: [testDispatcher] (Kolibri / :common-ui
 * call sites) and [dispatcher] (Nyx call sites). The pair exists so neither app's
 * existing ~330 call sites had to change when the three hand-copies were merged
 * onto this base. Prefer [testDispatcher] in new code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class MainDispatcherRuleBase(
    val testDispatcher: TestDispatcher,
) : TestWatcher() {

    /** Alias for [testDispatcher] (historical Nyx spelling). Same instance. */
    val dispatcher: TestDispatcher get() = testDispatcher

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
