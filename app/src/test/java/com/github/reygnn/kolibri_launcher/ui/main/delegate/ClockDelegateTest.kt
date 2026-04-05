package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockDelegateTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var context: Context
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        context = mockk {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }

        observeTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke(any()) } returns emptyFlow()
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate(
        observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase = this.observeTimeBasedEventsUseCase
    ) = ClockDelegate(
        context = context,
        observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
        scope = createDelegateScope()
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial timeString is default placeholder`() {
        val delegate = createDelegate()
        assertEquals("--:--", delegate.timeString.value)
    }

    @Test
    fun `initial dateString is default placeholder`() {
        val delegate = createDelegate()
        assertEquals("---", delegate.dateString.value)
    }

    @Test
    fun `initial batteryString is default placeholder`() {
        val delegate = createDelegate()
        assertEquals("---%", delegate.batteryString.value)
    }

    @Test
    fun `initial timeBasedEvents is empty`() {
        val delegate = createDelegate()
        assertEquals(emptyList<Any>(), delegate.timeBasedEvents.value)
    }

    // ===========================================
    // TIME & DATE
    // ===========================================

    @Test
    fun `refreshTimeNow updates timeString from default`() = runTest {
        val delegate = createDelegate()

        delegate.refreshTimeNow()

        assertNotEquals("--:--", delegate.timeString.value)
    }

    @Test
    fun `refreshTimeNow updates dateString from default`() = runTest {
        val delegate = createDelegate()

        delegate.refreshTimeNow()

        assertNotEquals("---", delegate.dateString.value)
    }

    @Test
    fun `refreshTimeNow produces consistent format`() = runTest {
        val delegate = createDelegate()

        delegate.refreshTimeNow()

        val time = delegate.timeString.value
        val date = delegate.dateString.value

        assert(time.contains(":")) { "Time '$time' should contain ':'" }
        assert(date.contains(",")) { "Date '$date' should contain ','" }
    }

    // ===========================================
    // BATTERY
    // ===========================================

    @Test
    fun `updateBatteryLevel calculates percentage correctly`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 75, scale = 100)

        assertEquals("75%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel handles full battery`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 100, scale = 100)

        assertEquals("100%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel handles empty battery`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 0, scale = 100)

        assertEquals("0%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel handles non-100 scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 128, scale = 255)

        assertEquals("50%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel falls back on invalid level`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = -1, scale = 100)

        assertEquals("---%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel falls back on invalid scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 50, scale = -1)

        assertEquals("---%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevel falls back on zero scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 50, scale = 0)

        assertEquals("---%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevelFromIntent extracts values correctly`() {
        val delegate = createDelegate()

        val intent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 80
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        }

        delegate.updateBatteryLevelFromIntent(intent)

        assertEquals("80%", delegate.batteryString.value)
    }

    @Test
    fun `updateBatteryLevelFromIntent handles null intent`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevelFromIntent(null)

        assertEquals("---%", delegate.batteryString.value)
    }

    // ===========================================
    // TIME-BASED EVENTS
    // ===========================================

    @Test
    fun `start observes time-based events`() = runTest {
        val testEvents = listOf(mockk<TimeBasedEvent>())
        val useCase: ObserveTimeBasedEventsUseCase = mockk(relaxed = true)
        every { useCase.invoke(any()) } returns flowOf(testEvents)

        val delegate = createDelegate(observeTimeBasedEventsUseCase = useCase)

        delegate.start()
        advanceUntilIdle()

        assertEquals(testEvents, delegate.timeBasedEvents.value)
    }

    // ===========================================
    // REFRESH ALL
    // ===========================================

    @Test
    fun `refreshAll updates time, battery, and triggers event refresh`() = runTest {
        val delegate = createDelegate()

        delegate.refreshAll()

        assertNotEquals("--:--", delegate.timeString.value)
        verify { observeTimeBasedEventsUseCase.refresh() }
    }
}