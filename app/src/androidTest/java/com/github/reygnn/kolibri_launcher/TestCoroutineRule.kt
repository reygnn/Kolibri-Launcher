package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@ExperimentalCoroutinesApi
class TestCoroutineRule(
    private val defaultMode: Mode = Mode.FAST
) : TestWatcher() {

    enum class Mode { SAFE, FAST, DIRTY }

    lateinit var testDispatcher: TestDispatcher
        private set

    override fun starting(description: Description?) {
        super.starting(description)
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        testDispatcher = dispatcher
    }

    override fun finished(description: Description?) {
        super.finished(description)
        Dispatchers.resetMain()
    }

    fun runTestAndLaunchUI(
        mode: Mode = defaultMode,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val dispatcher = when (mode) {
            Mode.SAFE -> StandardTestDispatcher()
            Mode.FAST, Mode.DIRTY -> testDispatcher // Verwende den bereits gesetzten Dispatcher
        }

        runTest(dispatcher) {
            // setMain wurde bereits in starting() aufgerufen
            try {
                block()
            } finally {
                // resetMain wird in finished() aufgerufen
            }
        }
    }
}