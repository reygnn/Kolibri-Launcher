package com.github.reygnn.launcher.core.testing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Records EVERY emission of [flow] into [into] for the rest of the test — the one
 * way to observe a flow when the test asserts on the whole set of emissions
 * (`events.any { … }`, `results.size`, `results.last()`), see TESTING_CONVENTIONS
 * §5/§6. For step-by-step expectations ("next item is X, then Y") use Turbine.
 *
 * Collection starts eagerly — an [UnconfinedTestDispatcher] on this test's own
 * scheduler — so the collector is subscribed BEFORE the code under test emits
 * (event flows without replay drop emissions nobody is listening to;
 * `WhileSubscribed` flows only start with a subscriber). This is the only place in
 * test code where that dispatcher may be created for collecting (detector A7).
 *
 * Returns the collector [Job]; cancel it at the end of the test (runTest waits for
 * running children).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.recordEmissions(flow: Flow<T>, into: MutableCollection<in T>): Job =
    launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { into.add(it) } }
