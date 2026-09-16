package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Impl-specific behaviour not covered by the shared contract (which always seeds a
 * valid blob): the read path never crashes on absent OR corrupt state (DRAWER_FOLDERS_SPEC
 * §4, "missing/undecodable blob ⇒ EMPTY, never a crash"). The two fallbacks are
 * independent — a missing key and a present-but-undecodable blob — so both are pinned.
 */
class DrawerFoldersRepositoryImplTest {

    @Test
    fun missing_blob_reads_as_EMPTY() = runTest {
        val repo = DrawerFoldersRepositoryImpl(FakeDataStore(), DrawerFoldersSerializer())
        assertThat(repo.folders().first()).isEqualTo(DrawerFolders.EMPTY)
    }

    @Test
    fun undecodable_blob_reads_as_EMPTY() = runTest {
        // Seed a corrupt value under the impl's own key — mirrors DrawerFoldersRepositoryImpl.KEY,
        // keep in sync if that key ever changes. This exercises the `deserialize(raw) ?: EMPTY`
        // branch of the read flow, distinct from the missing-key branch above, so a future
        // `deserialize(raw)!!` regression (which would crash the whole drawer read Flow on a
        // corrupt blob) turns this red instead of shipping.
        val store = FakeDataStore(
            preferencesOf(stringPreferencesKey("drawer_folders_v1") to "{ not valid json"),
        )
        val repo = DrawerFoldersRepositoryImpl(store, DrawerFoldersSerializer())
        assertThat(repo.folders().first()).isEqualTo(DrawerFolders.EMPTY)
    }
}
