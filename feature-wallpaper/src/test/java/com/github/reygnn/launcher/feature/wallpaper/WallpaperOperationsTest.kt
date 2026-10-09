package com.github.reygnn.launcher.feature.wallpaper

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** When set, the store reads this instead of the persisted state (null = unreadable). */
    private var persistedRead: (() -> Set<String>?)? = null

    private fun operations(scope: CoroutineScope, persistence: FakePersistence, composite: FakeComposite = FakeComposite()): WallpaperOperations {
        // The store reads what the fake persisted, like the real repository would.
        val repository = mockk<WallpaperRepository>(relaxed = true) {
            coEvery { readPersistedImageUris() } answers { persistedRead.let { r -> if (r != null) r() else persistence.state.value.referencedUris } }
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
    fun remove_during_an_open_session_is_refused() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-3: the open session still references the files and would write them back on commit
        // or cancel — so nothing is cleared, nothing deleted, and the session stays as it was.
        val twoLayers = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "1", imageUri = a), WallpaperLayerState(id = "2", imageUri = b)))
        val persistence = FakePersistence(twoLayers)
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)
        ops.session.enter()

        val removed = ops.clear()
        advanceUntilIdle()

        assertThat(removed).isFalse()
        assertThat(persistence.writes).isEmpty()
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
        assertThat(composite.invalidated).isEqualTo(0)
        assertThat(ops.session.isEditMode.value).isTrue()
        assertThat(ops.session.state.value.referencedUris).containsExactly(a, b)
    }

    @Test
    fun a_session_opened_while_remove_waits_for_the_lock_refuses_it() = runTest(mainDispatcherRule.testDispatcher) {
        // 3b-3b-b: the outer check passed (no session yet); remove then waits for a gated save
        // holding the persist lock, and a session opens meanwhile. The check under the lock decides.
        val twoLayers = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "1", imageUri = a), WallpaperLayerState(id = "2", imageUri = b)))
        val persistence = FakePersistence(twoLayers)
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)
        persistence.saveGate = CompletableDeferred()
        ops.persistLater("save", ops.session.swapLayers(0, 1))
        var removed: Boolean? = null
        launch { removed = ops.clear() }
        advanceUntilIdle() // remove waits for the gated save

        ops.session.enter()
        persistence.saveGate!!.complete(Unit)
        advanceUntilIdle()

        assertThat(removed).isFalse()
        assertThat(persistence.writes).containsExactly("save") // no "clear"
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
        assertThat(composite.invalidated).isEqualTo(0)
        assertThat(ops.session.isEditMode.value).isTrue()
    }

    // ---- 3b/29, D2: "remove" deletes exactly the previous state's files ----

    @Test
    fun remove_deletes_exactly_the_previous_states_files() = runTest(mainDispatcherRule.testDispatcher) {
        // Never the whole directory: a foreign file (an orphan, a copy still running) is untouched.
        val twoLayers = WallpaperState.multiLayer(listOf(WallpaperLayerState(id = "1", imageUri = a), WallpaperLayerState(id = "2", imageUri = b)))
        val persistence = FakePersistence(twoLayers)
        val ops = operations(this, persistence)

        assertThat(ops.clear()).isTrue()

        verify(exactly = 1) { fileManager.deleteFile(a) }
        verify(exactly = 1) { fileManager.deleteFile(b) }
        verify(exactly = 2) { fileManager.deleteFile(any<String>()) } // nothing else
    }

    @Test
    fun remove_with_an_unreadable_previous_state_empties_it_and_deletes_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        // Fail closed: which files were the wallpaper's can't be told — they stay as GC orphans.
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        var reads = 0
        persistedRead = { if (reads++ == 0) null else persistence.state.value.referencedUris }

        assertThat(ops.clear()).isTrue() // the state is empty afterwards: the removal took effect

        assertThat(persistence.state.value.hasWallpaper).isFalse()
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
    }

    // ---- 3b/29, d′: outside a session the last CHOSEN image wins, a foreign writer discards ----

    @Test
    fun an_older_replace_finishing_after_a_newer_one_is_discarded() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        val olderGate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(Uri.parse("content://picker/older")) } coAnswers { olderGate.await(); Uri.parse("file:///w/older") }
        coEvery { fileManager.copyToInternal(Uri.parse("content://picker/newer")) } returns Uri.parse("file:///w/newer")

        val older = async { ops.replace(Uri.parse("content://picker/older"), ops.session.rollbackGeneration) }
        runCurrent() // the older copy waits
        val newer = ops.replace(Uri.parse("content://picker/newer"), ops.session.rollbackGeneration)
        olderGate.complete(Unit)

        assertThat(newer).isEqualTo(WallpaperOperations.ImageResult.Applied)
        assertThat(older.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.state.value.referencedUris).containsExactly("file:///w/newer")
        verify(exactly = 1) { fileManager.deleteFile("file:///w/older") }
    }

    @Test
    fun two_replaces_the_older_finishing_first_both_apply_and_the_newer_wins() = runTest(mainDispatcherRule.testDispatcher) {
        // The newer replace finds the older one's result, not its own start state: only the
        // remembered last applied refs let it through (d′), and it removes the older file.
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        val olderGate = CompletableDeferred<Unit>()
        val newerGate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(Uri.parse("content://picker/older")) } coAnswers { olderGate.await(); Uri.parse("file:///w/older") }
        coEvery { fileManager.copyToInternal(Uri.parse("content://picker/newer")) } coAnswers { newerGate.await(); Uri.parse("file:///w/newer") }

        val older = async { ops.replace(Uri.parse("content://picker/older"), ops.session.rollbackGeneration) }
        val newer = async { ops.replace(Uri.parse("content://picker/newer"), ops.session.rollbackGeneration) }
        runCurrent() // both copies wait at their gates
        olderGate.complete(Unit)
        runCurrent() // the older one applies first
        newerGate.complete(Unit)

        assertThat(older.await()).isEqualTo(WallpaperOperations.ImageResult.Applied)
        assertThat(newer.await()).isEqualTo(WallpaperOperations.ImageResult.Applied)
        assertThat(persistence.state.value.referencedUris).containsExactly("file:///w/newer")
        verify(exactly = 1) { fileManager.deleteFile("file:///w/older") }
    }

    @Test
    fun a_foreign_writer_during_the_copy_discards_the_replace() = runTest(mainDispatcherRule.testDispatcher) {
        // A reset or an import writes past the operations: the copy must not set the wallpaper again.
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        val gate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(any()) } coAnswers { gate.await(); Uri.parse(b) }

        val result = async { ops.replace(picked, ops.session.rollbackGeneration) }
        runCurrent()
        persistence.state.value = WallpaperState.NONE // the foreign writer (a reset)
        gate.complete(Unit)

        assertThat(result.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.state.value.hasWallpaper).isFalse()
        verify(exactly = 1) { fileManager.deleteFile(b) }
    }

    @Test
    fun an_unreadable_state_when_applying_discards_without_deleting() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(this, persistence)
        val gate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(any()) } coAnswers { gate.await(); Uri.parse(b) }

        val result = async { ops.replace(picked, ops.session.rollbackGeneration) }
        runCurrent()
        persistedRead = { null }
        gate.complete(Unit)

        assertThat(result.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.writes).isEmpty()
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) } // the copy is a GC orphan
    }

    @Test
    fun a_remove_that_did_not_take_effect_keeps_everything() = runTest(mainDispatcherRule.testDispatcher) {
        // 3a-2d: the clear was swallowed, the persisted state still references its file.
        val persistence = FakePersistence(WallpaperState.single(uri = a)).apply { clearSwallowed = true }
        val composite = FakeComposite()
        val ops = operations(this, persistence, composite)

        assertThat(ops.clear()).isFalse()
        verify(exactly = 0) { fileManager.deleteFile(any<String>()) }
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
        // The observation never ends on its own: run it in the background scope. Drive it with
        // runCurrent(), NOT advanceUntilIdle(): advanceUntilIdle() stops as soon as only
        // backgroundScope work is left, so the collector would never start and this test would
        // check nothing (3a-9c). There are no delays on the way, so runCurrent() suffices.
        val ops = operations(backgroundScope, persistence, composite)

        ops.start()
        runCurrent()
        persistence.state.value = WallpaperState.single(uri = b)
        runCurrent()

        assertThat(ops.session.state.value.referencedUris).containsExactly(b)
        verify(exactly = 1) { fileManager.gcOrphans(any<Set<String>>()) }
        assertThat(composite.refilled).hasSize(2)
    }

    // ---- "remove wallpaper" against a running copy (audit A2) ----
    // The removal deletes every image file, a running copy's too. A replace or add that resumes
    // afterwards must discard itself, never persist a reference to the deleted file.

    @Test
    fun clear_during_a_running_replace_discards_the_replace() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val copyGate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(any()) } coAnswers { copyGate.await(); Uri.parse(b) }
        val ops = operations(backgroundScope, persistence)

        val replace = async { ops.replace(picked, ops.session.rollbackGeneration) }
        runCurrent() // the copy is running
        assertThat(ops.clear()).isTrue()
        copyGate.complete(Unit)

        assertThat(replace.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.state.value).isEqualTo(WallpaperState.NONE)
        verify { fileManager.deleteFile(b) } // the discarded copy goes too
    }

    @Test
    fun clear_during_a_running_add_discards_the_add() = runTest(mainDispatcherRule.testDispatcher) {
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val copyGate = CompletableDeferred<Unit>()
        coEvery { fileManager.copyToInternal(any()) } coAnswers { copyGate.await(); Uri.parse(b) }
        val ops = operations(backgroundScope, persistence)

        val add = async { ops.addLayer(picked, ops.session.rollbackGeneration) }
        runCurrent()
        assertThat(ops.clear()).isTrue()
        copyGate.complete(Unit)

        assertThat(add.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.state.value).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun a_replace_whose_copy_finished_while_the_clear_waited_is_discarded() = runTest(mainDispatcherRule.testDispatcher) {
        // The narrower window: the copy is done, but the change waits for the persist lock that a
        // clear (itself behind a pending save) holds next. The generation check must run under
        // the lock, or the change passes first and saves after the removal.
        val persistence = FakePersistence(WallpaperState.single(uri = a))
        val ops = operations(backgroundScope, persistence)
        persistence.saveGate = CompletableDeferred()
        ops.persistLater("transform", ops.session.saveSingleTransform(2f, 0f, 0f, null))
        runCurrent() // that save now holds the persist lock

        val clear = async { ops.clear() }
        runCurrent() // queued behind the save
        val replace = async { ops.replace(picked, ops.session.rollbackGeneration) }
        runCurrent() // copied, queued behind the clear
        persistence.saveGate!!.complete(Unit)

        assertThat(clear.await()).isTrue()
        assertThat(replace.await()).isEqualTo(WallpaperOperations.ImageResult.Discarded)
        assertThat(persistence.state.value).isEqualTo(WallpaperState.NONE)
    }
}
