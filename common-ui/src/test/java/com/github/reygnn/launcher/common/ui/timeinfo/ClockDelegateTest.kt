package com.github.reygnn.launcher.common.ui.timeinfo

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.timeinfo.ChargeState
import com.github.reygnn.launcher.core.timeinfo.ObserveTimeBasedEventsUseCase
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent
import com.google.common.truth.Truth.assertThat
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase

    @Before
    fun setUp() {
        // ClockDelegate reports caught failures via TimberWrapper.silentError, which
        // throws in DEBUG; suppress that for the duration of the test (mirrors the
        // apps' TimberRule) so error-path branches don't crash the test.
        TimberWrapper.preventCrashForTesting.set(true)

        context = mockk {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }

        observeTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke(any()) } returns emptyFlow()
    }

    @After
    fun tearDown() {
        TimberWrapper.preventCrashForTesting.set(false)
    }

    private fun createDelegate(
        observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase = this.observeTimeBasedEventsUseCase
    ) = ClockDelegate(
        context = context,
        observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
        scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial timeString is default placeholder`() {
        val delegate = createDelegate()
        assertThat(delegate.timeString.value).isEqualTo("--:--")
    }

    @Test
    fun `initial dateString is default placeholder`() {
        val delegate = createDelegate()
        assertThat(delegate.dateString.value).isEqualTo("---")
    }

    @Test
    fun `initial batteryString is default placeholder`() {
        val delegate = createDelegate()
        assertThat(delegate.batteryString.value).isEqualTo("---%")
    }

    @Test
    fun `initial chargeState is NONE`() {
        val delegate = createDelegate()
        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.NONE)
    }

    @Test
    fun `initial timeBasedEvents is empty`() {
        val delegate = createDelegate()
        assertThat(delegate.timeBasedEvents.value).isEqualTo(emptyList<Any>())
    }

    // ===========================================
    // TIME & DATE
    // ===========================================

    @Test
    fun `refreshTimeNow updates timeString from default`() = runTest {
        val delegate = createDelegate()

        delegate.refreshTimeNow()

        assertThat(delegate.timeString.value).isNotEqualTo("--:--")
    }

    @Test
    fun `refreshTimeNow updates dateString from default`() = runTest {
        val delegate = createDelegate()

        delegate.refreshTimeNow()

        assertThat(delegate.dateString.value).isNotEqualTo("---")
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

        assertThat(delegate.batteryString.value).isEqualTo("75%")
    }

    @Test
    fun `updateBatteryLevel handles full battery`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 100, scale = 100)

        assertThat(delegate.batteryString.value).isEqualTo("100%")
    }

    @Test
    fun `updateBatteryLevel handles empty battery`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 0, scale = 100)

        assertThat(delegate.batteryString.value).isEqualTo("0%")
    }

    @Test
    fun `updateBatteryLevel handles non-100 scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 128, scale = 255)

        assertThat(delegate.batteryString.value).isEqualTo("50%")
    }

    @Test
    fun `updateBatteryLevel falls back on invalid level`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = -1, scale = 100)

        assertThat(delegate.batteryString.value).isEqualTo("---%")
    }

    @Test
    fun `updateBatteryLevel falls back on invalid scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 50, scale = -1)

        assertThat(delegate.batteryString.value).isEqualTo("---%")
    }

    @Test
    fun `updateBatteryLevel falls back on zero scale`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevel(level = 50, scale = 0)

        assertThat(delegate.batteryString.value).isEqualTo("---%")
    }

    // Builds a mocked battery Intent stubbing the four extras the delegate reads.
    private fun batteryIntent(level: Int, scale: Int, status: Int, plugged: Int): Intent =
        mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
            every {
                getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            } returns status
            every { getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) } returns plugged
        }

    @Test
    fun `updateBatteryLevelFromIntent extracts values correctly`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 80,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_DISCHARGING,
                plugged = 0
            )
        )

        assertThat(delegate.batteryString.value).isEqualTo("80%")
        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.NONE)
    }

    @Test
    fun `updateBatteryLevelFromIntent handles null intent`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevelFromIntent(null)

        assertThat(delegate.batteryString.value).isEqualTo("---%")
        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.NONE)
    }

    @Test
    fun `updateBatteryLevelFromIntent sets CHARGING when status is charging`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 42,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                plugged = BatteryManager.BATTERY_PLUGGED_AC
            )
        )

        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.CHARGING)
    }

    @Test
    fun `updateBatteryLevelFromIntent treats full status as CHARGING`() {
        val delegate = createDelegate()

        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 100,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_FULL,
                plugged = BatteryManager.BATTERY_PLUGGED_AC
            )
        )

        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.CHARGING)
    }

    @Test
    fun `updateBatteryLevelFromIntent sets PROTECTED when plugged but not charging`() {
        val delegate = createDelegate()

        // Battery protection / charge limit: plugged in, charge held at the cap.
        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 80,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                plugged = BatteryManager.BATTERY_PLUGGED_AC
            )
        )

        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.PROTECTED)
    }

    @Test
    fun `updateBatteryLevelFromIntent sets NONE when unplugged and not charging`() {
        val delegate = createDelegate()

        // First mark it charging, then an unplugged not-charging update must clear it.
        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 50,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                plugged = BatteryManager.BATTERY_PLUGGED_AC
            )
        )
        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.CHARGING)

        delegate.updateBatteryLevelFromIntent(
            batteryIntent(
                level = 50,
                scale = 100,
                status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                plugged = 0
            )
        )

        assertThat(delegate.chargeState.value).isEqualTo(ChargeState.NONE)
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

        assertThat(delegate.timeBasedEvents.value).isEqualTo(testEvents)
    }

    // ===========================================
    // REFRESH ALL
    // ===========================================

    @Test
    fun `refreshAll updates time, battery, and triggers event refresh`() = runTest {
        val delegate = createDelegate()

        delegate.refreshAll()

        assertThat(delegate.timeString.value).isNotEqualTo("--:--")
        verify { observeTimeBasedEventsUseCase.refresh() }
    }
}
