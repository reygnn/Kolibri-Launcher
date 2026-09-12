package com.github.reygnn.launcher.common.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for one [TestDispatcher] for the duration of a test
 * (project convention: ONE dispatcher source, passed to both `runTest` and any
 * code that reads Main). Mirrors the per-app rules; lives here so :common-ui
 * tests don't reach into an app module.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
