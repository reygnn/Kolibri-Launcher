package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Impl-only behavior the shared [PreferencesRepositoryContract] can't cover (the
 * fake has no legacy key and no corrupt-value path): the one-way migration from
 * the pre-tri-state boolean `monochrome_icons` into `icon_style`, corrupt-value
 * fallback, and that setting the style drops the legacy key.
 */
class PreferencesRepositoryImplMigrationTest {

    private val iconStyleKey = stringPreferencesKey("icon_style")
    private val legacyMonochromeKey = booleanPreferencesKey("monochrome_icons")

    @Test
    fun legacy_monochrome_true_migrates_to_monochrome_when_no_icon_style() = runTest {
        val store = FakeDataStore()
        store.edit { it[legacyMonochromeKey] = true }
        val repo = PreferencesRepositoryImpl(store)

        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.MONOCHROME)
    }

    @Test
    fun legacy_monochrome_false_or_absent_reads_as_color() = runTest {
        val store = FakeDataStore()
        store.edit { it[legacyMonochromeKey] = false }
        val repo = PreferencesRepositoryImpl(store)

        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.COLOR)
    }

    @Test
    fun icon_style_takes_precedence_over_the_legacy_flag() = runTest {
        val store = FakeDataStore()
        store.edit {
            it[iconStyleKey] = IconStyle.GRAYSCALE.name
            it[legacyMonochromeKey] = true // stale; must be ignored in favour of icon_style
        }
        val repo = PreferencesRepositoryImpl(store)

        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.GRAYSCALE)
    }

    @Test
    fun corrupt_icon_style_string_falls_back_to_color() = runTest {
        val store = FakeDataStore()
        store.edit { it[iconStyleKey] = "NOT_A_REAL_STYLE" }
        val repo = PreferencesRepositoryImpl(store)

        assertThat(repo.iconStyle().first()).isEqualTo(IconStyle.COLOR)
    }

    @Test
    fun setting_icon_style_writes_the_name_and_drops_the_legacy_key() = runTest {
        val store = FakeDataStore()
        store.edit { it[legacyMonochromeKey] = true }
        val repo = PreferencesRepositoryImpl(store)

        repo.setIconStyle(IconStyle.GRAYSCALE)

        val prefs = store.data.first()
        assertThat(prefs[iconStyleKey]).isEqualTo(IconStyle.GRAYSCALE.name)
        assertThat(prefs[legacyMonochromeKey]).isNull() // one-way migration removes it
    }
}
