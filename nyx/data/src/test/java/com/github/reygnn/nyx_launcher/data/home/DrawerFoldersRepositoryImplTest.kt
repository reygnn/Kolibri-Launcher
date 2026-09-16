package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Impl-specific behaviour not covered by the shared contract (which always seeds a
 * valid blob): the read path never crashes on absent state — a store with no drawer
 * blob reads as [DrawerFolders.EMPTY] (DRAWER_FOLDERS_SPEC §4). The undecodable-blob
 * path is pinned by [DrawerFoldersSerializerTest] (deserialize ⇒ null) plus the impl's
 * `?: EMPTY` mapping.
 */
class DrawerFoldersRepositoryImplTest {

    @Test
    fun missing_blob_reads_as_EMPTY() = runTest {
        val repo = DrawerFoldersRepositoryImpl(FakeDataStore(), DrawerFoldersSerializer())
        assertThat(repo.folders().first()).isEqualTo(DrawerFolders.EMPTY)
    }
}
