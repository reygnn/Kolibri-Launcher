package com.github.reygnn.nyx_launcher.data.home

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.github.reygnn.launcher.feature.wallpaper.WallpaperComposite
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric (only for real [android.net.Uri]): [NyxWallpaperImageSetter], since 3b-3 a thin
 * facade over the ONE shared session ([NyxWallpaperEditing]) — over mocked [WallpaperFileManager]
 * and [WallpaperRepository]. Pins the replace-vs-strand and copy-failure decisions (WV5), the
 * remove rules (state first, refused during an open session) and the start-up GC of the shared
 * component, without touching real files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NyxWallpaperImageSetterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true)
    private val repoState = MutableStateFlow(WallpaperState.NONE)
    private val repository = mockk<WallpaperRepository>(relaxed = true) {
        every { wallpaperState } returns repoState
    }

    // Not backgroundScope: advanceUntilIdle must drive the shared session's start-up (3a-9c).
    private val appScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)

    private val editing by lazy {
        NyxWallpaperEditing(
            repository = repository,
            imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
            composite = WallpaperComposite.None(), // placeholder: no composite in these tests (3b-6)
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            appScope = appScope,
            mainDispatcher = mainDispatcherRule.testDispatcher,
        )
    }
    private val setter by lazy { NyxWallpaperImageSetter(editing) }

    @After
    fun stopAppScope() {
        appScope.cancel()
    }

    /** What the store reads as persisted (the deletes and the GC decide against it). */
    private fun persisted(vararg uris: String) {
        coEvery { repository.readPersistedImageUris() } returns uris.toSet()
    }

    /** The shared session starts from [state] (the persisted wallpaper) — as MainActivity starts it. */
    private fun TestScope.startedWith(state: WallpaperState) {
        repoState.value = state
        editing.start()
        advanceUntilIdle()
    }

    private val sourceUri: Uri = Uri.parse("content://picker/image")

    private fun internalUri(value: String): Uri = Uri.parse(value)

    @Test
    fun setFromUri_saves_new_state_and_deletes_the_replaced_file() = runTest(mainDispatcherRule.testDispatcher) {
        persisted("file:///new")
        startedWith(WallpaperState.single("file:///old"))
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isEqualTo(WallpaperOperations.ImageResult.Applied)
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layerCount == 1 && it.layers.single().imageUri == "file:///new" }) }
        verify(exactly = 1) { fileManager.deleteFile("file:///old") }
    }

    @Test
    fun setFromUri_with_no_previous_wallpaper_saves_and_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        persisted("file:///new")
        startedWith(WallpaperState.NONE)
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isEqualTo(WallpaperOperations.ImageResult.Applied)
        coVerify(exactly = 1) { repository.saveWallpaperState(match { it.layerCount == 1 && it.layers.single().imageUri == "file:///new" }) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_does_not_delete_when_the_new_file_matches_the_old_reference() = runTest(mainDispatcherRule.testDispatcher) {
        persisted("file:///same")
        startedWith(WallpaperState.single("file:///same"))
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///same")

        setter.setFromUri(sourceUri)

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_returns_copy_failed_and_touches_nothing_when_the_copy_fails() = runTest(mainDispatcherRule.testDispatcher) {
        persisted("file:///old")
        startedWith(WallpaperState.single("file:///old"))
        coEvery { fileManager.copyToInternal(any()) } returns null

        val ok = setter.setFromUri(sourceUri)

        assertThat(ok).isEqualTo(WallpaperOperations.ImageResult.CopyFailed)
        coVerify(exactly = 0) { repository.saveWallpaperState(any()) }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_returns_discarded_when_a_removal_lands_during_the_copy() = runTest(mainDispatcherRule.testDispatcher) {
        // Audit A21: a removal wins the race with a running copy (3b/20) — the result is Discarded,
        // not a failure, so neither Settings nor the sheet shows "couldn't set wallpaper".
        persisted()
        startedWith(WallpaperState.single("file:///old"))
        val copyGate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(any()) } coAnswers {
            copyGate.await()
            internalUri("file:///new")
        }

        val result = async { setter.setFromUri(sourceUri) }
        runCurrent() // the copy waits at the gate
        assertThat(setter.clear()).isTrue()
        copyGate.complete(Unit)

        assertThat(result.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        coVerify(exactly = 0) { repository.saveWallpaperState(match { it.layers.any { l -> l.imageUri == "file:///new" } }) }
        verify(exactly = 1) { fileManager.deleteFile("file:///new") } // the discarded copy goes through the store
    }

    @Test
    fun clear_clears_state_and_deletes_the_files_once_nothing_is_persisted() = runTest(mainDispatcherRule.testDispatcher) {
        // State first, then exactly the previous state's files (3b/29, D2), through the store, once
        // the persisted state references nothing (as in Kolibri, 3a-2d).
        var refs = setOf("file:///a", "file:///b")
        coEvery { repository.readPersistedImageUris() } answers { refs }
        coEvery { repository.clearWallpaper() } answers { refs = emptySet() }
        startedWith(
            WallpaperState.multiLayer(listOf(WallpaperLayerState(imageUri = "file:///a"), WallpaperLayerState(imageUri = "file:///b"))),
        )

        val removed = setter.clear()

        assertThat(removed).isTrue()
        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 1) { fileManager.deleteFile("file:///a") }
        verify(exactly = 1) { fileManager.deleteFile("file:///b") }
    }

    @Test
    fun clear_with_no_wallpaper_still_clears_state_and_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        persisted()
        startedWith(WallpaperState.NONE)

        setter.clear()

        coVerify(exactly = 1) { repository.clearWallpaper() }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun component_start_reclaims_orphans_once() = runTest(mainDispatcherRule.testDispatcher) {
        // Retargeted from reclaimOrphans_sweeps_using_the_currently_referenced_uris (3b-3): the GC
        // runs in the shared session's start-up, once per process, with the persisted references.
        persisted("file:///keep")
        startedWith(WallpaperState.single("file:///keep"))
        editing.start() // a second host start does not run it again
        advanceUntilIdle()

        verify(exactly = 1) { fileManager.gcOrphans(setOf("file:///keep")) }
    }

    @Test
    fun setFromUri_deletes_nothing_when_the_persisted_state_cannot_be_read() = runTest(mainDispatcherRule.testDispatcher) {
        // A swallowed save or an unreadable store must not cost the old file (3a-2c).
        coEvery { repository.readPersistedImageUris() } returns null
        startedWith(WallpaperState.single("file:///old"))
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")

        setter.setFromUri(sourceUri)

        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun setFromUri_during_an_open_edit_session_is_a_session_change() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-3: during an open session the choose is a session change — the old file stays until
        // the commit (a cancel would bring the old image back), and goes with the commit.
        persisted("file:///new")
        startedWith(WallpaperState.single("file:///old"))
        coEvery { fileManager.copyToInternal(any()) } returns internalUri("file:///new")
        editing.session.enter()

        val ok = setter.setFromUri(sourceUri)
        advanceUntilIdle()

        assertThat(ok).isEqualTo(WallpaperOperations.ImageResult.Applied)
        assertThat(editing.session.state.value.referencedUris).containsExactly("file:///new")
        verify(exactly = 0) { fileManager.deleteFile("file:///old") }

        editing.operations.commit(onImageChanged = {})
        advanceUntilIdle()

        verify(exactly = 1) { fileManager.deleteFile("file:///old") }
    }

    @Test
    fun clear_that_did_not_land_reports_false_and_keeps_the_files() = runTest(mainDispatcherRule.testDispatcher) {
        // The clear was swallowed: the old state is still persisted, so its files stay (3a-2d).
        persisted("file:///a")
        startedWith(WallpaperState.single("file:///a"))

        val removed = setter.clear()

        assertThat(removed).isFalse()
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    @Test
    fun clear_during_an_open_edit_session_is_refused() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-3b: the open session still references the files and would write them back — so the
        // removal is refused: false, nothing cleared, nothing deleted, the session unchanged.
        persisted("file:///a")
        startedWith(WallpaperState.single("file:///a"))
        editing.session.enter()

        val removed = setter.clear()

        assertThat(removed).isFalse()
        coVerify(exactly = 0) { repository.clearWallpaper() }
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
        assertThat(editing.session.isEditMode.value).isTrue()
        assertThat(editing.session.state.value.referencedUris).containsExactly("file:///a")
    }

    @Test
    fun an_open_session_blocks_the_gc() = runTest(mainDispatcherRule.testDispatcher) {
        // Retargeted from reclaimOrphans_leaves_an_open_edit_session_alone (3b-3): the start-up GC
        // refuses while a session is open.
        persisted("file:///keep")
        repoState.value = WallpaperState.single("file:///keep")
        editing.session.enter()

        editing.start()
        advanceUntilIdle()

        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }
    }

    @Test
    fun component_start_does_not_reclaim_on_an_unreadable_state() = runTest(mainDispatcherRule.testDispatcher) {
        // Retargeted from reclaimOrphans_does_not_run_on_an_unreadable_state (3b-3): before 3b-1
        // an unreadable store read as "nothing referenced" — every file an orphan.
        coEvery { repository.readPersistedImageUris() } returns null

        startedWith(WallpaperState.single("file:///keep"))

        verify(exactly = 0) { fileManager.gcOrphans(any<Set<String>>()) }
    }
}
