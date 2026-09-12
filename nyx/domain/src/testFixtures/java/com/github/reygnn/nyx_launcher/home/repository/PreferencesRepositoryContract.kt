package com.github.reygnn.nyx_launcher.home.repository

import app.cash.turbine.test
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
    fun monochrome_defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        assertThat(repo.monochromeIcons().first()).isFalse()
    }

    @Test
    fun setting_monochrome_true_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setMonochromeIcons(true)
        assertThat(repo.monochromeIcons().first()).isTrue()
    }

    @Test
    fun toggling_monochrome_back_to_false_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.setMonochromeIcons(true)
        repo.setMonochromeIcons(false)
        assertThat(repo.monochromeIcons().first()).isFalse()
    }

    @Test
    fun monochrome_flow_emits_the_new_value_on_change() = runTest(mainDispatcherRule.dispatcher) {
        val repo = createRepository()
        repo.monochromeIcons().test {
            assertThat(awaitItem()).isFalse() // initial
            repo.setMonochromeIcons(true)
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
