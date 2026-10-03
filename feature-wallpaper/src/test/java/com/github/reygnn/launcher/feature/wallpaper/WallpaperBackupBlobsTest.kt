package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [WallpaperBackupBlobs] (3a-7): collecting and binding back on plain types, O2 in one place, and
 * the cleanup through the store — only unclaimed copies, also after a cancellation.
 */
@RunWith(RobolectricTestRunner::class) // android.net.Uri
@Config(sdk = [36])
class WallpaperBackupBlobsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private var copies = 0
    private val fileManager = mockk<WallpaperFileManager>(relaxed = true) {
        every { copyFromInputStream(any()) } answers {
            firstArg<java.io.InputStream>().readBytes()
            Uri.parse("file:///w/copy_${copies++}")
        }
    }
    private val repository = mockk<WallpaperRepository>(relaxed = true) {
        coEvery { readPersistedImageUris() } returns emptySet()
    }
    private val blobs = WallpaperBackupBlobs(fileManager, WallpaperImageStore(fileManager, repository, mainDispatcherRule.testDispatcher))

    // ---- export ----

    @Test
    fun collect_and_rebind_keep_the_format() {
        val a = tmp.newFile("a.img")
        val layers = listOf(
            WallpaperLayerBackup(id = "l0", imageUri = "file:///w/a"),
            WallpaperLayerBackup(id = "l1", imageUri = "file:///gone"),
            WallpaperLayerBackup(id = "l2", imageUri = "file:///w/a"),
        )

        val collected = blobs.collect(layers) { uri -> if (uri == "file:///w/a") a else null }
        val rebound = collected.rebind(layers, listOf("h0", "h1", "single-of-the-app"))

        assertThat(collected.files).containsExactly(a, a).inOrder() // the engine stores equal content once
        assertThat(rebound.map { it.imageFileName }).containsExactly("h0", null, "h1").inOrder()
        assertThat(rebound.map { it.imageUri }).containsExactly(null, "file:///gone", null).inOrder()
    }

    // ---- import ----

    @Test
    fun extract_copies_each_referenced_blob_once_and_skips_rejected_ones() {
        val staging = tmp.newFolder("staging")
        val into = mutableMapOf<String, String>()

        blobs.extract(listOf("ok", "ok", "rejected"), claim = { hash, target ->
            if (hash == "ok") target.apply { writeBytes(byteArrayOf(1)) } else null
        }, staging = staging, into = into)

        assertThat(into).containsExactly("ok", "file:///w/copy_0")
        assertThat(File(staging, "claimed-ok").exists()).isFalse() // staging file gone after its copy
    }

    @Test
    fun every_layer_gets_its_own_file() {
        // O2: equal content resolves to one extracted file; the second layer gets its own copy.
        val owned = blobs.assignOwnFiles(listOf("file:///w/x", "file:///w/y", "file:///w/x", null)) {
            ByteArrayInputStream(byteArrayOf(7))
        }

        assertThat(owned).containsExactly("file:///w/x", "file:///w/y", "file:///w/copy_0", null).inOrder()
    }

    @Test
    fun a_layer_whose_own_copy_fails_is_left_out() {
        val owned = blobs.assignOwnFiles(listOf("file:///w/x", "file:///w/x")) { null }

        assertThat(owned).containsExactly("file:///w/x", null).inOrder()
    }

    // ---- cleanup ----

    @Test
    fun release_deletes_only_what_it_is_given_and_what_nothing_persisted_references() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { repository.readPersistedImageUris() } returns setOf("file:///w/kept")

            blobs.release(listOf("file:///w/unclaimed", "file:///w/kept"))

            verify(exactly = 1) { fileManager.deleteFile("file:///w/unclaimed") }
            verify(exactly = 0) { fileManager.deleteFile("file:///w/kept") }
        }

    @Test
    fun release_runs_after_a_cancellation_and_the_cancellation_keeps_propagating() =
        runTest(mainDispatcherRule.testDispatcher) {
            val job = launch {
                try {
                    awaitCancellation()
                } finally {
                    blobs.release(listOf("file:///w/unclaimed"))
                }
            }
            testScheduler.runCurrent()

            job.cancel()
            job.join()

            verify(exactly = 1) { fileManager.deleteFile("file:///w/unclaimed") }
            assertThat(job.isCancelled).isTrue()
        }
}
