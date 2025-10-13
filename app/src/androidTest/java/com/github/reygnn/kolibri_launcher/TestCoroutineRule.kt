/*
package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class TestCoroutineRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    override fun starting(description: Description) {
        super.starting(description)
        // Wir ersetzen den Main-Dispatcher für unsere Tests
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        super.finished(description)
        // Wir räumen den Test-Dispatcher auf
        Dispatchers.resetMain()
        // Wir räumen unseren UI-Thread-Dispatcher auf
        mainThreadSurrogate.close()
    }

    // ÄNDERUNG 3: Eine neue Run-Funktion, die den Kontext für UI-Interaktionen wechselt
    fun runTestAndLaunchUI(block: suspend CoroutineScope.() -> Unit) = runTest(testDispatcher) {
        val originalContext = coroutineContext
        // Wir starten einen neuen Scope auf unserem "echten" UI-Thread
        CoroutineScope(mainThreadSurrogate).launch {
            // Wir führen den Test-Block im richtigen Kontext aus
            withContext(originalContext) {
                block()
            }
        }
    }
}*/

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

/*
@ExperimentalCoroutinesApi
class TestCoroutineRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()  // ← ÄNDERUNG!
) : TestWatcher() {

    override fun starting(description: Description) {
        super.starting(description)
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        super.finished(description)
        Dispatchers.resetMain()
    }

    fun runTestAndLaunchUI(block: suspend CoroutineScope.() -> Unit) = runTest(testDispatcher) {
        block()
    }
}*/

@ExperimentalCoroutinesApi
class TestCoroutineRule(
    private val mode: Mode = Mode.FAST
) : TestWatcher() {

    enum class Mode { FAST, SAFE }

    private var _testDispatcher: TestDispatcher? = null

    val testDispatcher: TestDispatcher
        get() = _testDispatcher ?: throw IllegalStateException("TestDispatcher not initialized")

    override fun starting(description: Description) {
        super.starting(description)
        _testDispatcher = when(mode) {
            Mode.FAST -> UnconfinedTestDispatcher()
            Mode.SAFE -> StandardTestDispatcher()
        }
        Dispatchers.setMain(_testDispatcher!!)
    }

    override fun finished(description: Description) {
        super.finished(description)
        Dispatchers.resetMain()
        _testDispatcher = null
    }

    fun runTestAndLaunchUI(
        testMode: Mode = this.mode,
        block: suspend CoroutineScope.() -> Unit
    ) {
        val dispatcher = when(testMode) {
            Mode.FAST -> UnconfinedTestDispatcher()
            Mode.SAFE -> StandardTestDispatcher()
        }

        runTest(dispatcher) {
            block()
            if (testMode == Mode.SAFE) {
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
        }
    }
}