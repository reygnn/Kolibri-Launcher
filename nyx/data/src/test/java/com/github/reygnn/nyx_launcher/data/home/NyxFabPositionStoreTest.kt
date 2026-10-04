package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.github.reygnn.launcher.feature.wallpaper.FabPositionStore
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM over [FakeDataStore]: Nyx's FAB position on the shared [FabPositionStore] in its
 * home_layout store (3b-2) — unset → [FabPosition.DEFAULT], save→read round-trip, and the rule
 * for a missing fraction: since 3b-2 (L4) each coordinate falls back on its own, as in Kolibri
 * (before: "both or default").
 */
class NyxFabPositionStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun unset_yields_default() = runTest(mainDispatcherRule.testDispatcher) {
        val store = FabPositionStore(FakeDataStore())

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition.DEFAULT)
    }

    @Test
    fun saved_position_is_read_back() = runTest(mainDispatcherRule.testDispatcher) {
        val store = FabPositionStore(FakeDataStore())

        store.saveFabPosition(FabPosition(xFraction = 0.1f, yFraction = 0.2f))

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition(0.1f, 0.2f))
    }

    @Test
    fun only_x_present_falls_back_per_coordinate() = runTest(mainDispatcherRule.testDispatcher) {
        val dataStore = FakeDataStore()
        dataStore.edit { it[floatPreferencesKey("wallpaper_edit_fab_x_fraction")] = 0.3f }
        val store = FabPositionStore(dataStore)

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition(xFraction = 0.3f, yFraction = FabPosition.DEFAULT.yFraction))
    }

    @Test
    fun only_y_present_falls_back_per_coordinate() = runTest(mainDispatcherRule.testDispatcher) {
        val dataStore = FakeDataStore()
        dataStore.edit { it[floatPreferencesKey("wallpaper_edit_fab_y_fraction")] = 0.6f }
        val store = FabPositionStore(dataStore)

        assertThat(store.fabPositionFlow.first()).isEqualTo(FabPosition(xFraction = FabPosition.DEFAULT.xFraction, yFraction = 0.6f))
    }
}
