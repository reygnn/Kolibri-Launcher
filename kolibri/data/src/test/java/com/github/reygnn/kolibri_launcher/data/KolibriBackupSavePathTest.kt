package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.BackupException
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * U3 at app level for Kolibri (2b-3c follow-up, Nyx's pattern in `NyxBackupSavePathTest`): an
 * export that fails after the user picked the target leaves no document behind — whether the
 * write fails or reading a store does. Real `file://` documents; Robolectric for `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriBackupSavePathTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fails on the first write, like a full disk. */
    private var failWrites = false

    private val resolver = mockk<ContentResolver> {
        every { openOutputStream(any()) } answers {
            val file = File(firstArg<Uri>().path!!)
            if (!failWrites) {
                file.outputStream()
            } else {
                object : FileOutputStream(file) {
                    override fun write(b: Int) { throw IOException("disk full") }
                    override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("disk full") }
                }
            }
        }
    }
    private val context = mockk<Context> { every { contentResolver } returns resolver }

    private fun repository(favorites: FavoritesRepository = FakeFavoritesRepository()) =
        BackupRepositoryImplTestFactory.create(
            favoritesRepository = favorites,
            favoritesOrderRepository = FakeFavoritesOrderRepository(),
            hiddenAppsRepository = FakeHiddenAppsRepository(),
            customNamesRepository = FakeCustomNamesRepository(),
            installedAppsRepository = FakeInstalledAppsRepository(),
            swipeActionsRepository = FakeSwipeActionsRepository(),
            settingsRepository = FakeSettingsRepository(),
            wallpaperRepository = FakeWallpaperRepository(),
            wallpaperFileManager = mockk<WallpaperFileManager>(relaxed = true),
            context = context,
        )

    /** The document CreateDocument has just made for the export. */
    private fun createdDocument(): Uri = Uri.fromFile(tmp.newFile("kolibri-backup.zip"))

    @Test
    fun `a complete export keeps its document`() = runTest(mainDispatcherRule.testDispatcher) {
        val target = createdDocument()

        assertThat(repository().saveBackupToFile(target.toString())).isTrue()

        assertThat(File(target.path!!).length()).isGreaterThan(0L)
    }

    @Test
    fun `a failing write leaves no document`() = runTest(mainDispatcherRule.testDispatcher) {
        failWrites = true
        val target = createdDocument()

        assertFailsWith<BackupException> { repository().saveBackupToFile(target.toString()) }

        verify { resolver.openOutputStream(target) } // the export did start writing
        assertThat(File(target.path!!).exists()).isFalse()
    }

    @Test
    fun `a store that fails to read leaves no document`() = runTest(mainDispatcherRule.testDispatcher) {
        // Assembling the backup runs inside writeOrDiscard, so its failure discards the document
        // too (2b-3c follow-up; it used to run before the document was opened and left it empty).
        val failing = object : FavoritesRepository by FakeFavoritesRepository() {
            override suspend fun getFavoriteComponentsSnapshot(): Set<String> = throw IOException("store gone")
        }
        val target = createdDocument()

        assertFailsWith<BackupException> { repository(failing).saveBackupToFile(target.toString()) }

        assertThat(File(target.path!!).exists()).isFalse()
    }
}
