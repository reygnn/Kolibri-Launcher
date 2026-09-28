package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeAppUsageRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class NyxResetManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val usageRepository = FakeAppUsageRepository()

    @Test
    fun reset_clears_every_datastore_key_purges_usage_and_deletes_wallpaper_files() =
        runTest(mainDispatcherRule.dispatcher) {
            val dataStore = FakeDataStore()
            dataStore.edit {
                it[stringPreferencesKey("home_layout_v1")] = "{...}"
                it[booleanPreferencesKey("monochrome_icons")] = true
                it[stringPreferencesKey("wallpaper_layers_json")] = "[...]"
                it[booleanPreferencesKey("home_dock_seeded_v1")] = true
            }
            usageRepository.recordPackageLaunch("com.a")
            val manager = NyxResetManager(dataStore, usageRepository, fileManager, mainDispatcherRule.dispatcher)

            val ok = manager.reset()

            assertThat(ok).isTrue()
            assertThat(dataStore.data.first().asMap()).isEmpty()
            // Usage lives in a separate store, so reset must purge it explicitly.
            assertThat(usageRepository.current).isEmpty()
            verify(exactly = 1) { fileManager.clearAll() }
        }

    @Test
    fun reset_returns_false_when_wiping_the_files_throws() =
        runTest(mainDispatcherRule.dispatcher) {
            val dataStore = FakeDataStore()
            val throwingFileManager = mockk<WallpaperFileManager> {
                every { clearAll() } throws RuntimeException("disk error")
            }
            val manager = NyxResetManager(dataStore, usageRepository, throwingFileManager, mainDispatcherRule.dispatcher)

            val ok = manager.reset()

            assertThat(ok).isFalse()
        }

    @Test
    fun a_failing_step_still_clears_the_others_and_returns_false() =
        runTest(mainDispatcherRule.dispatcher) {
            // §Audit-2 N9: the stores/files are independent, so a failure in the middle step
            // (usage purge) must NOT skip the last step (wallpaper files) — a reset clears as much
            // as it can — while the overall result is still false because a step failed.
            val dataStore = FakeDataStore()
            dataStore.edit { it[stringPreferencesKey("home_layout_v1")] = "{...}" }
            val throwingUsage = mockk<AppUsageRepository> {
                coEvery { purgeRepository() } throws RuntimeException("usage store error")
            }
            val manager = NyxResetManager(dataStore, throwingUsage, fileManager, mainDispatcherRule.dispatcher)

            val ok = manager.reset()

            assertThat(ok).isFalse() // a step failed
            assertThat(dataStore.data.first().asMap()).isEmpty() // step 1 still ran
            verify(exactly = 1) { fileManager.clearAll() } // step 3 ran despite step 2 failing
        }
}
