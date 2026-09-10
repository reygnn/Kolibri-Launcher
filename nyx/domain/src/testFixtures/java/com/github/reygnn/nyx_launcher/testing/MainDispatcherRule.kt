package com.github.reygnn.nyx_launcher.testing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The ONE test dispatcher for the whole suite (project convention,
 * TESTING_CONVENTIONS): tests never create their own `TestScope` /
 * `StandardTestDispatcher`. They read [dispatcher] from this rule, pass it to
 * `runTest(...)`, and inject it wherever a use-case needs a `CoroutineDispatcher`
 * — so `Dispatchers.Main` and the injected dispatcher are the same instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
