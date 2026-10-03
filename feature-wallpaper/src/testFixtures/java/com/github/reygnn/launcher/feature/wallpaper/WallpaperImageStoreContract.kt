package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The wallpaper image files of a launcher (SPEC_NYX_REWRITE A2, 3a-2): a wrongly deleted file is
 * a lost wallpaper, a missing delete only an orphan. Written FIRST against each app's code as it
 * is, before the file lifecycle moves into `:feature-wallpaper`; it must stay green through the
 * move.
 *
 * After EVERY case the contract checks the one invariant that matters most: no layer of the
 * saved state references a file that does not exist.
 *
 * The three W lines of the spec, and the mandatory cases of 3a-2:
 *  - replacing deletes the old file — right away outside an edit session (W1, Nyx's behaviour),
 *    kept until commit inside one, kept entirely on cancel;
 *  - the orphan GC never deletes a file a saved layer references, nor a file of an open edit
 *    session (Kolibri's edit guard), and removes a real orphan;
 *  - a copy without the following save (process death in between) leaves at most an orphan;
 *  - a file is deleted only once no layer references it anymore (O2 safeguard);
 *  - "an export never reads a file an edit session is changing" is checked through a PROXY: an
 *    export reads the persisted state, so during an open session the persisted state must
 *    reference only existing files and no layer the session removed. That the export reads that
 *    state is covered by the backup tests.
 *
 * Cases that only make sense with immediate deletion on replace are SKIPPED (not absent) while
 * [deletesReplacedImageImmediately] is false — JUnit reports them, so every run shows what is
 * still open. Kolibri flips it with 3a-2; Nyx's subclass sets it from the start.
 *
 * The contract ages files itself, so the GC's protection of young files (60 s) does not hide a
 * wrong decision, and settles the scheduler after every step (apps finish work in launched
 * coroutines). Standard project contract shape (`NoAutoPruneContract`): `MainDispatcherRule` +
 * `runTest(testDispatcher)`; the subclass wires the SAME dispatcher into its store.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
abstract class WallpaperImageStoreContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sources: File = Files.createTempDirectory("wallpaper-sources").toFile()

    @After
    fun deleteSources() {
        sources.deleteRecursively()
    }

    /** W1: replacing deletes the old file right away (outside an edit session). */
    protected abstract val deletesReplacedImageImmediately: Boolean

    /** Where the launcher keeps its wallpaper image files. */
    protected abstract val wallpaperDir: File

    /** A fresh store with an empty [wallpaperDir], running as after an app start. */
    protected abstract suspend fun startStore()

    /** "Choose wallpaper": the picked [image] becomes the whole wallpaper (one layer). */
    protected abstract suspend fun setWallpaper(image: File)

    protected abstract suspend fun enterEditSession()
    protected abstract suspend fun commitEditSession()
    protected abstract suspend fun cancelEditSession()

    /** Adds [image] as a new layer (inside an edit session). */
    protected abstract suspend fun addLayer(image: File)

    /** Removes the layer at [index] (inside an edit session). */
    protected abstract suspend fun removeLayer(index: Int)

    /** Triggers the launcher's orphan GC now, as it does it (e.g. on start). */
    protected abstract suspend fun runOrphanGc()

    /** Copies [image] into the store like a pick would, but the state is never saved (process death). */
    protected abstract suspend fun copyWithoutSave(image: File)

    /** Saves a state whose two layers share ONE internal copy of [image] (breaks O2 on purpose). */
    protected abstract suspend fun saveTwoLayersOnOneFile(image: File)

    /** Names of the files the persisted state's layers reference, in layer order. */
    protected abstract suspend fun savedLayerFiles(): List<String>

    // ---- the cases ----

    @Test
    fun `replacing the wallpaper deletes the old file`() = runTest(mainDispatcherRule.testDispatcher) {
        assumeTrue("W1 immediate deletion comes with 3a-2", deletesReplacedImageImmediately)
        step { startStore() }
        step { setWallpaper(image("a")) }
        val old = savedLayerFiles().single()

        step { setWallpaper(image("b")) }

        assertWithMessage("the replaced file is gone").that(storedFiles()).doesNotContain(old)
        assertWithMessage("only the new file is left").that(storedFiles()).containsExactlyElementsIn(savedLayerFiles())
        assertNoDanglingReference()
    }

    @Test
    fun `the GC never deletes a file a saved layer references`() = runTest(mainDispatcherRule.testDispatcher) {
        step { startStore() }
        step { setWallpaper(image("a")) }
        val referenced = savedLayerFiles()
        ageAllFiles()

        step { runOrphanGc() }

        assertWithMessage("referenced files survive the GC").that(storedFiles()).containsAtLeastElementsIn(referenced)
        assertNoDanglingReference()
    }

    @Test
    fun `the GC never deletes a file of an open edit session`() = runTest(mainDispatcherRule.testDispatcher) {
        step { startStore() }
        step { setWallpaper(image("a")) }
        val file = savedLayerFiles().single()
        step { enterEditSession() }
        step { removeLayer(0) }
        ageAllFiles()

        step { runOrphanGc() }

        assertWithMessage("a file the open session may still restore survives the GC").that(storedFiles()).contains(file)
        step { cancelEditSession() }
        assertWithMessage("cancel brings the layer back").that(savedLayerFiles()).containsExactly(file)
        assertNoDanglingReference()
    }

    @Test
    fun `the GC removes a real orphan`() = runTest(mainDispatcherRule.testDispatcher) {
        step { startStore() }
        step { setWallpaper(image("a")) }
        val orphan = File(wallpaperDir, "orphan_left_behind").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ageAllFiles()

        step { runOrphanGc() }

        assertWithMessage("the orphan is removed").that(orphan.exists()).isFalse()
        assertWithMessage("the wallpaper is untouched").that(storedFiles()).containsExactlyElementsIn(savedLayerFiles())
        assertNoDanglingReference()
    }

    @Test
    fun `a copy without a save leaves at most an orphan, never a dangling reference`() =
        runTest(mainDispatcherRule.testDispatcher) {
            step { startStore() }
            step { setWallpaper(image("a")) }
            val saved = savedLayerFiles()

            step { copyWithoutSave(image("b")) }

            assertWithMessage("the unsaved copy is not referenced").that(savedLayerFiles()).isEqualTo(saved)
            assertNoDanglingReference()
            ageAllFiles()
            step { runOrphanGc() }
            assertWithMessage("the GC removes the unsaved copy").that(storedFiles()).containsExactlyElementsIn(saved)
        }

    @Test
    fun `during an edit session the persisted state an export reads holds only existing files`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Proxy for the W line "an export never reads a file an edit session is changing".
            step { startStore() }
            step { setWallpaper(image("a")) }
            val removed = savedLayerFiles().single()
            step { enterEditSession() }
            step { addLayer(image("b")) }
            step { removeLayer(0) }

            assertNoDanglingReference()
            assertWithMessage("the persisted state holds no layer the session removed")
                .that(savedLayerFiles()).doesNotContain(removed)
            step { cancelEditSession() }
            assertNoDanglingReference()
        }

    @Test
    fun `replacing during an edit session keeps the old file until commit`() = runTest(mainDispatcherRule.testDispatcher) {
        assumeTrue("W1 immediate deletion comes with 3a-2", deletesReplacedImageImmediately)
        step { startStore() }
        step { setWallpaper(image("a")) }
        val old = savedLayerFiles().single()
        step { enterEditSession() }

        step { setWallpaper(image("b")) }
        assertWithMessage("inside the session the old file stays").that(storedFiles()).contains(old)

        step { commitEditSession() }
        assertWithMessage("commit deletes it").that(storedFiles()).doesNotContain(old)
        assertWithMessage("only the new file is left").that(storedFiles()).containsExactlyElementsIn(savedLayerFiles())
        assertNoDanglingReference()
    }

    @Test
    fun `replacing during an edit session and cancelling keeps the old file and orphans the new one`() =
        runTest(mainDispatcherRule.testDispatcher) {
            assumeTrue("W1 immediate deletion comes with 3a-2", deletesReplacedImageImmediately)
            step { startStore() }
            step { setWallpaper(image("a")) }
            val old = savedLayerFiles().single()
            step { enterEditSession() }
            step { setWallpaper(image("b")) }

            step { cancelEditSession() }

            assertWithMessage("cancel keeps the old wallpaper and its file").that(savedLayerFiles()).containsExactly(old)
            assertNoDanglingReference()
            ageAllFiles()
            step { runOrphanGc() }
            assertWithMessage("the newly copied file was an orphan, the GC removes it")
                .that(storedFiles()).containsExactly(old)
        }

    @Test
    fun `a file is deleted only once no layer references it anymore`() = runTest(mainDispatcherRule.testDispatcher) {
        // O2 safeguard: no file is shared between layers — but if that ever breaks, removing one of
        // two layers on the same file must not take the other layer's image with it.
        assumeTrue("the reference check before deleting comes with 3a-2", deletesReplacedImageImmediately)
        step { startStore() }
        step { saveTwoLayersOnOneFile(image("a")) }
        val shared = savedLayerFiles().distinct().single()
        step { enterEditSession() }
        step { removeLayer(0) }

        step { commitEditSession() }

        assertWithMessage("the other layer still references the file").that(savedLayerFiles()).containsExactly(shared)
        assertWithMessage("so it stays").that(storedFiles()).contains(shared)
        assertNoDanglingReference()
    }

    // ---- helpers ----

    /** Runs [block] and lets every coroutine it started finish. */
    private suspend fun TestScope.step(block: suspend () -> Unit) {
        block()
        advanceUntilIdle()
    }

    private var imageSeed = 0

    /** A distinct source image outside the store. */
    private fun image(name: String): File =
        File(sources, "$name.png").apply { writeBytes(ByteArray(64) { (it + imageSeed).toByte() }.also { imageSeed++ }) }

    private fun storedFiles(): Set<String> = wallpaperDir.list().orEmpty().toSet()

    /** Two minutes old: past the GC's 60 s protection of fresh files. */
    private fun ageAllFiles() {
        val old = System.currentTimeMillis() - 120_000L
        wallpaperDir.listFiles().orEmpty().forEach { it.setLastModified(old) }
    }

    private suspend fun assertNoDanglingReference() {
        assertWithMessage("every file the saved state references exists")
            .that(storedFiles()).containsAtLeastElementsIn(savedLayerFiles())
    }
}
