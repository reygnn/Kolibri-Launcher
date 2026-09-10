package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PreferencesRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun defaults_to_false() = runTest(mainDispatcherRule.dispatcher) {
        val repo = PreferencesRepositoryImpl(FakeDataStore())
        assertThat(repo.monochromeIcons().first()).isFalse()
    }

    @Test
    fun set_then_reads_back() = runTest(mainDispatcherRule.dispatcher) {
        val repo = PreferencesRepositoryImpl(FakeDataStore())
        repo.setMonochromeIcons(true)
        assertThat(repo.monochromeIcons().first()).isTrue()
    }
}
