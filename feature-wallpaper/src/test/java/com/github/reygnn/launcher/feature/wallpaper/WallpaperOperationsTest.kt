package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.common.ui.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WallpaperOperations] (3a-9): the executing rules around the session — copy → save → delete,
 * the rollback generation, "remove wallpaper" behind pending saves, commit/cancel with the re-sync,
 * and the start-up wiring. Real session and image store; the persistence port and the composite
 * are small fakes.
 */
@RunWith(RobolectricTestRunner::class) // android.net.Uri
@Config(sdk = [36])
class WallpaperOperationsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = "file:///w/a"
    private val b = "file:///w/b"
    private val picked = Uri.parse("content://picker/1")

    /** The persisted state; save can be held at a gate to keep a write pending. */
    private class FakePersistence(initial: WallpaperState) : WallpaperPersistence {
        val state = MutableStateFlow(initial)
        val writes = mutableListOf<String>()
        var saveGate: CompletableDeferred<Unit>? = null
        /** A swallowed clear, like a release-build failure: returns, persists nothing. */
        var clearSwallowed = false
        override suspend fun save(state: WallpaperState) {
            saveGate?.await()
            writes += "save"
            this.state.value = state
        }
        override suspend fun clear() {
            writes += "clear"
            if (!clearSwallowed) state.value = WallpaperState.NONE
        }
        override fun observe(): Flow<WallpaperState> = state
    }

    /** Records refills; [exclusive] is a real lock, like the implementation's. */
    private class FakeComposite : WallpaperComposite {
        val refilled = mutableListOf<WallpaperState>()
        var invalidated = 0
        private val lock = Mutex()
        override fun refill(target: WallpaperState, host: WallpaperComposite.Host) {
            refilled += target
        }
        override fun cachedKeyFor(state: WallpaperState, widthPx: Int, heightPx: Int): String? = null
        override fun cachedBitmap(key: String): DecodedWallpaperBitmap? = null
        override fun invalidate(dropLuminance: Boolean) {
            invalidated++
        }
        override suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
    }

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true) {
        coEvery { copyToInternal(any()) } returns Uri.parse(b)
    }

    private fun operations(scope: CoroutineScope, persistence: FakePersistence, composite: FakeComposite = FakeComposite()): WallpaperOperations {
        // The store reads what the fake persisted, like the real repository would.
        val repository = mockk<WallpaperRepository>(relaxed = true) {
            coEvery { readPersistedImageUris() } answers { persistence.state.value.referencedUris }
        }
        val session = WallpaperEditSession().apply { onPersistedState(persistence.state.value) }
        val host = object : WallpaperComposite.Host {
            override fun currentState() = session.state.value
            override fun isEditing() = session.isEditMode.value
            override fun displaySize() = 1080 to 2340
            override fun launch(block: suspend () -> Unit) {
                scope.launch { block() }
            }
        }
        return WallpaperOperations(
            session = session,
            persistence = persistence,
            imageStore = WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher),
            composite = composite,
            compositeHost = host,
            launch = { _, block -> scope.launch { block() } },
        )
    }

    @Test
    fun replace_copies_saves_the_single_image_state_and_deletes_the_old_file() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)

        val result = ops.replace(picked, ops.session.rollbackGeneration)

        assertThat(result).isEqualTo(WallpaperOperations.ImageResult.Applied)
        // K2: the single-image state (a single layer on the copy); layer ids are generated.
        assertThat(persistence.state.value.layers.map { it.imageUri }).containsExactly(b)
        verify(exactly = 1) { fileManager.deleteFile(a) }
    }

    @Test
    fun a_failed_copy_changes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { fileManager.copyToInternal(any()) } returns null
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)

        assertThat(ops.replace(picked, ops.session.rollbackGeneration)).isEqualTo(WallpaperOperations.ImageResult.CopyFailed)
        assertThat(persistence.writes).isEmpty()
    }

    @Test
    fun a_cancel_during_the_copy_discards_replace_and_add() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        ops.session.enter()
        val generation = ops.session.rollbackGeneration
        ops.cancel()
        advanceUntilIdle()
        persistence.writes.clear()

        assertThat(ops.replace(picked, generation)).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(ops.addLayer(picked, generation)).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.writes).isEmpty()
        verify { fileManager.deleteFile(b) } // the discarded add's copy goes again
    }

    @Test
    fun remove_waits_for_a_pending_save_and_then_clears() = runTest(mainDispatcherRule.testDispatcher) {
        // 3a-3b: removing is a write too; it must land after every earlier save.
        val twoLayers = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "1", imageUri = a), WallpaperLayerState(id = "2", imageUri = b)))
        val persistence = FakePersistence(twoLayers)
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)
        persistence.saveGate = CompletableDeferred()

        ops.persistLater("save", ops.session.swapLayers(0, 1))
        var removed: Boolean? = null
        launch { removed = ops.clear() }
        advanceUntilIdle()
        assertThat(persistence.writes).isEmpty() // both wait for the gated save

        persistence.saveGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(persistence.writes).containsExactly("save", "clear").inOrder()
        assertThat(removed).isTrue()
        assertThat(composite.invalidated).isEqualTo(1)
        assertThat(ops.session.state.value).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun a_remove_that_did_not_take_effect_keeps_everything() = runTest(mainDispatcherRule.testDispatcher) {
        // 3a-2d: the clear was swallowed, the persisted state still references its file.
        val persistence = FakePersistence(WallpaperState.single(uri = a)).apply { clearSwallowed = true }
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)

        assertThat(ops.clear()).isFalse()
        verify(exactly = 0) { fileManager.clearAll() }
        assertThat(composite.invalidated).isEqualTo(0)
        assertThat(ops.session.state.value.referencedUris).containsExactly(a)
    }

    @Test
    fun cancel_persists_the_snapshot_and_deletes_the_session_added_file() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        ops.session.enter()
        ops.addLayer(picked, ops.session.rollbackGeneration) // adds b, persisted

        ops.cancel()
        advanceUntilIdle()

        assertThat(persistence.state.value.referencedUris).containsExactly(a)
        verify { fileManager.deleteFile(b) }
    }

    @Test
    fun commit_deletes_the_removed_file_after_the_saves_and_resyncs() = runTest(mainDispatcherRule.testDispatcher) {
        val twoLayers = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "1", imageUri = a), WallpaperLayerState(id = "2", imageUri = b)))
        val persistence = FakePersistence(twoLayers)
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)
        ops.session.enter()
        ops.persistLater("remove", ops.session.removeLayer(0))
        var imageChanged = false

        ops.commit(onImageChanged = { imageChanged = true })
        advanceUntilIdle()

        verify { fileManager.deleteFile(a) }
        assertThat(imageChanged).isFalse() // a removal is no image change
        assertThat(composite.refilled).isNotEmpty() // leaving the session refills
        assertThat(ops.session.state.value.referencedUris).containsExactly(b)
    }

    @Test
    fun start_feeds_the_session_runs_the_gc_once_and_refills_on_each_emission() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val composite = FakeComposite()
        // The observation never ends on its own: run it in the background scope.
        val ops = operations(backgroundScope, persistence, composite)

        ops.start()
        advanceUntilIdle()
        persistence.state.value = WallpaperState.single(uri = b)
        advanceUntilIdle()

        assertThat(ops.session.state.value.referencedUris).containsExactly(b)
        verify(exactly = 1) { fileManager.gcOrphans(any<Set<String>>()) }
        assertThat(composite.refilled).hasSize(2)
    }
}
