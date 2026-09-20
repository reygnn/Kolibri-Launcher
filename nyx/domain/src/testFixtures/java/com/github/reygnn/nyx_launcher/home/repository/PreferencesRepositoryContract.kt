package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The behavioural contract every [PreferencesRepository] must satisfy (A1-16),
 * mirroring the [HomeLayoutRepositoryContract] triple: `FakePreferencesRepositoryContractTest`
 * and (in `:data`) `PreferencesRepositoryImplContractTest` extend it and only
 * supply [createRepository]; if the fake and the DataStore-backed impl drift,
 * one side goes red.
 */
abstract class PreferencesRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Provide a fresh repository in its default (unset) state. */
    abstract fun createRepository(): PreferencesRepository

    @Test
    fun icon_style_defaults_to_color() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.COLOR)
    }

    @Test
    fun setting_icon_style_monochrome_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setIconStyle(IconStyle.MONOCHROME)
        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.MONOCHROME)
    }

    @Test
    fun setting_icon_style_grayscale_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setIconStyle(IconStyle.GRAYSCALE)
        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.GRAYSCALE)
    }

    @Test
    fun switching_icon_style_back_to_color_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setIconStyle(IconStyle.MONOCHROME)
        repo.setIconStyle(IconStyle.COLOR)
        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.COLOR)
    }

    @Test
    fun icon_style_flow_emits_the_new_value_on_change() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.iconStyle().test {
            assertThat(awaitItem()).isEqualTo(IconStyle.COLOR) // initial
            repo.setIconStyle(IconStyle.GRAYSCALE)
            assertThat(awaitItem()).isEqualTo(IconStyle.GRAYSCALE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- search auto-launch flag ---

    @Test
    fun search_auto_launch_defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        assertThat(createRepository().searchAutoLaunch().first()).isFalse()
    }

    @Test
    fun setting_search_auto_launch_true_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setSearchAutoLaunch(true)
        assertThat(repo.searchAutoLaunch().first()).isTrue()
    }

    @Test
    fun search_auto_launch_flow_emits_the_new_value_on_change() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.searchAutoLaunch().test {
            assertThat(awaitItem()).isFalse() // initial
            repo.setSearchAutoLaunch(true)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- notification dots flag ---

    @Test
    fun notification_dots_defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        assertThat(createRepository().notificationDots().first()).isFalse()
    }

    @Test
    fun setting_notification_dots_true_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setNotificationDots(true)
        assertThat(repo.notificationDots().first()).isTrue()
    }

    @Test
    fun notification_dots_flow_emits_the_new_value_on_change() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.notificationDots().test {
            assertThat(awaitItem()).isFalse() // initial
            repo.setNotificationDots(true)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- home-info flags (TimeInfoSettings port) ---

    @Test
    fun show_alarm_defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        assertThat(createRepository().showAlarmFlow.first()).isFalse()
    }

    @Test
    fun setting_show_alarm_true_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setShowAlarm(true)
        assertThat(repo.showAlarmFlow.first()).isTrue()
    }

    @Test
    fun show_calendar_event_defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        assertThat(createRepository().showCalendarEventFlow.first()).isFalse()
    }

    @Test
    fun setting_show_calendar_event_true_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setShowCalendarEvent(true)
        assertThat(repo.showCalendarEventFlow.first()).isTrue()
    }

    @Test
    fun home_info_flags_are_independent() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setShowAlarm(true)
        assertThat(repo.showAlarmFlow.first()).isTrue()
        assertThat(repo.showCalendarEventFlow.first()).isFalse() // unaffected
    }
}
