package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.FabPosition
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM over [FakeDataStore]: unset → [FabPosition.DEFAULT], save→read
 * round-trip, and the partial-write guard — with only one fraction persisted the
 * store still reports DEFAULT (both x and y must be present to form a position).
 */
class NyxFabPositionStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun unset_yields_default() = runTest(mainDispatcherRule.dispatcher) {
        val store = NyxFabPositionStore(FakeDataStore())

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }

    @Test
    fun saved_position_is_read_back() = runTest(mainDispatcherRule.dispatcher) {
        val store = NyxFabPositionStore(FakeDataStore())

        store.saveFabPosition(FabPosition(xFraction = 0.1f, yFraction = 0.2f))

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition(0.1f, 0.2f))
    }

    @Test
    fun only_x_present_still_yields_default() = runTest(mainDispatcherRule.dispatcher) {
        val dataStore = FakeDataStore()
        dataStore.edit { it[floatPreferencesKey("wallpaper_edit_fab_x_fraction")] = 0.3f }
        val store = NyxFabPositionStore(dataStore)

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }

    @Test
    fun only_y_present_still_yields_default() = runTest(mainDispatcherRule.dispatcher) {
        val dataStore = FakeDataStore()
        dataStore.edit { it[floatPreferencesKey("wallpaper_edit_fab_y_fraction")] = 0.6f }
        val store = NyxFabPositionStore(dataStore)

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }
}
