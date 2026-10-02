package com.github.reygnn.kolibri_launcher.data
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unit tests for the file-side primitives of [WallpaperFileManager] —
 * in particular the orphan GC, which deletes real files on disk and is
 * therefore the highest-risk operation in the wallpaper subsystem.
 *
 * Uses Robolectric so `Uri.fromFile(...)` produces a real parseable URI
 * without requiring the Android runtime, plus [TemporaryFolder] so the
 * tests operate on an actual filesystem directory.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperFileManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var manager: WallpaperFileManager
    private lateinit var wallpaperDir: File

    @Before
    fun setUp() {
        context = mockk()
        every { context.filesDir } returns tempFolder.root
        manager = WallpaperFileManager(context, mainDispatcherRule.testDispatcher)
        // WallpaperFileManager creates this lazily; pre-create here so
        // individual tests can write files into it without racing.
        wallpaperDir = File(tempFolder.root, "wallpapers").also { it.mkdirs() }
    }

    /** Creates a file with the given relative age (ms ago from now). */
    private fun createFileWithAge(name: String, ageMillis: Long): File {
        val file = File(wallpaperDir, name)
        file.writeText("dummy bitmap bytes")
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        return file
    }

    // ===========================================
    // ORPHAN GC — CORE BEHAVIOR
    // ===========================================

    @Test
    fun `gcOrphans deletes files not in referenced set`() {
        val kept = createFileWithAge("wp_keep", ageMillis = 120_000L)   // 2 min
        val orphan = createFileWithAge("wp_orphan", ageMillis = 120_000L)

        manager.gcOrphans(referencedUris = setOf(Uri.fromFile(kept)))

        assertWithMessage("referenced file must survive").that(kept.exists()).isTrue()
        assertWithMessage("unreferenced file must be removed").that(orphan.exists()).isFalse()
    }

    @Test
    fun `gcOrphans deletes every file when referenced set is empty`() {
        val o1 = createFileWithAge("wp_1", ageMillis = 120_000L)
        val o2 = createFileWithAge("wp_2", ageMillis = 120_000L)

        manager.gcOrphans(referencedUris = emptySet())

        assertThat(o1.exists()).isFalse()
        assertThat(o2.exists()).isFalse()
    }

    @Test
    fun `gcOrphans keeps multiple referenced files`() {
        val f1 = createFileWithAge("wp_1", ageMillis = 120_000L)
        val f2 = createFileWithAge("wp_2", ageMillis = 120_000L)
        val orphan = createFileWithAge("wp_orphan", ageMillis = 120_000L)

        manager.gcOrphans(
            referencedUris = setOf(Uri.fromFile(f1), Uri.fromFile(f2))
        )

        assertThat(f1.exists()).isTrue()
        assertThat(f2.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
    }

    // ===========================================
    // ORPHAN GC — AGE CUTOFF (THE SAFETY NET)
    // ===========================================

    @Test
    fun `gcOrphans respects the default minAgeMillis — young files survive`() {
        // Default cutoff is 60s. A file just written (1s old) must not
        // be mistaken for an orphan — it could be a fresh copyToInternal
        // whose saveWallpaperState is still in-flight.
        val young = createFileWithAge("wp_fresh", ageMillis = 1_000L)

        manager.gcOrphans(referencedUris = emptySet())

        assertWithMessage("file younger than minAgeMillis must survive GC even when not referenced").that(young.exists()).isTrue()
    }

    @Test
    fun `gcOrphans with custom small minAgeMillis can delete young files`() {
        val young = createFileWithAge("wp_fresh", ageMillis = 5_000L)

        manager.gcOrphans(referencedUris = emptySet(), minAgeMillis = 1_000L)

        assertWithMessage("with shrunk min age, file is now eligible for GC").that(young.exists()).isFalse()
    }

    @Test
    fun `gcOrphans with zero minAgeMillis treats all files as eligible`() {
        val youngOrphan = createFileWithAge("wp_fresh", ageMillis = 10L)
        val kept = createFileWithAge("wp_keep", ageMillis = 10L)

        manager.gcOrphans(
            referencedUris = setOf(Uri.fromFile(kept)),
            minAgeMillis = 0L
        )

        assertThat(youngOrphan.exists()).isFalse()
        assertThat(kept.exists()).isTrue()
    }

    // ===========================================
    // ORPHAN GC — NON-FILE URIs AND EDGE CASES
    // ===========================================

    @Test
    fun `gcOrphans ignores non-file URIs in the referenced set`() {
        // content:// URIs sometimes sneak into state via migration bugs.
        // They MUST NOT accidentally match and protect some random file —
        // but also must not crash.
        val contentUri: Uri = mockk {
            every { scheme } returns "content"
            every { path } returns "/external/media/42"
        }
        val orphan = createFileWithAge("wp_orphan", ageMillis = 120_000L)

        manager.gcOrphans(referencedUris = setOf(contentUri))

        // content:// URI doesn't match file paths → orphan is still gone
        assertThat(orphan.exists()).isFalse()
    }

    @Test
    fun `gcOrphans with empty wallpaper directory is a safe no-op`() {
        // Directory exists but is empty — must not crash.
        manager.gcOrphans(referencedUris = emptySet())
        assertThat(wallpaperDir.exists()).isTrue()
    }

    @Test
    fun `gcOrphans is safe when wallpaper directory does not exist yet`() {
        // Force-remove the directory we created in setUp()
        wallpaperDir.deleteRecursively()
        assertThat(wallpaperDir.exists()).isFalse()

        // Should NOT crash — just return silently.
        manager.gcOrphans(referencedUris = emptySet())
    }

    @Test
    fun `gcOrphans does not touch files outside wallpapers directory`() {
        // File in the parent filesDir — NOT in our managed subdir.
        val outsider = File(tempFolder.root, "other_app_file.dat")
        outsider.writeText("important")
        outsider.setLastModified(System.currentTimeMillis() - 120_000L)

        manager.gcOrphans(referencedUris = emptySet())

        assertWithMessage("files outside wallpapers/ must never be touched by GC").that(outsider.exists()).isTrue()
    }

    // ===========================================
    // UNRELATED (but colocated): deleteFile respects isInternalUri
    // ===========================================

    @Test
    fun `deleteFile only touches internal URIs`() {
        val external: Uri = mockk {
            every { scheme } returns "content"
            every { path } returns "/foo/bar"
        }
        // Must not crash on non-internal URI.
        manager.deleteFile(external)
    }

    @Test
    fun `deleteFile removes an internal file`() {
        val f = createFileWithAge("wp_to_delete", ageMillis = 120_000L)
        assertThat(f.exists()).isTrue()

        manager.deleteFile(Uri.fromFile(f))

        assertThat(f.exists()).isFalse()
    }

    // ---- clearAll reports whether everything is gone (2b-4c, F1) ----

    @Test
    fun `clearAll deletes every file and reports true`() {
        File(wallpaperDir, "a.jpg").writeBytes(byteArrayOf(1))
        File(wallpaperDir, "b.jpg").writeBytes(byteArrayOf(2))

        assertThat(manager.clearAll()).isTrue()
        assertThat(wallpaperDir.list().orEmpty()).isEmpty()
    }

    @Test
    fun `clearAll on an empty directory reports true`() {
        assertThat(manager.clearAll()).isTrue()
    }

    @Test
    fun `clearAll reports false when an entry can't be deleted, and still deletes the rest`() {
        File(wallpaperDir, "a.jpg").writeBytes(byteArrayOf(1))
        // A non-empty directory can't be deleted with File.delete() — the stand-in for a locked file.
        File(wallpaperDir, "stuck").apply { mkdirs() }.resolve("inner").writeBytes(byteArrayOf(2))

        assertThat(manager.clearAll()).isFalse()
        assertThat(wallpaperDir.list().orEmpty().toList()).containsExactly("stuck")
    }
}
