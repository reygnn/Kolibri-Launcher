package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

/**
 * Pins the catch-block at [BackupRepositoryImpl.importFromJson] (the
 * canonical Kotlin coroutine boundary pattern: `CancellationException`
 * rethrow + `Exception` → Result-type conversion).
 *
 * The other `BackupRepositoryImpl*Test` spec files exercise import behavior
 * with real fakes that succeed; none of them reach the catch. This file
 * substitutes a mocked [FavoritesRepository] that throws on the PHASE 1
 * `saveFavoriteComponents` write inside [BackupDataAssembler.performImport],
 * which is the first repository write the assembler performs after the
 * installed-apps prime step.
 *
 * What is pinned:
 *   1. `CancellationException` MUST propagate (structured-concurrency rule).
 *      A future "cleanup" that swallows it will turn this test red.
 *   2. A generic `Exception` MUST be converted to `ImportResult.Error` and
 *      MUST carry the original message forward.
 *   3. The `?: "Unknown error"` fallback applies when `e.message` is null.
 *
 * Pattern note: like the other Backup spec files this uses real fakes via
 * [BackupRepositoryImplTestFactory], with one strategic mock substitution.
 * No Robolectric needed — `importFromJson` takes the JSON string directly
 * and never touches `Uri.parse`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRepositoryImplImportFromJsonCatchTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var backupManager: BackupRepositoryImpl

    /**
     * The favorite component the JSON below references. It must match the
     * preinstalled [AppInfo] so PHASE 1 of `performImport` actually invokes
     * `saveFavoriteComponents` (which the test then makes throw). If the
     * JSON-side and the prefill diverge, the assembler filters the favorite
     * out as "not installed" and the catch is never reached — silent green
     * tests would result.
     */
    private val targetComponent = "com.example.app/com.example.app.MainActivity"

    private val validJsonWithFavorite = """
        {
          "version": "1.0.0",
          "timestamp": 123456789,
          "settings": {
            "favoriteComponents": ["$targetComponent"]
          }
        }
    """.trimIndent()

    @Before
    fun setup() {
        favoritesRepository = mockk(relaxed = true)

        val installedAppsRepo = FakeInstalledAppsRepository().apply {
            installedApps = listOf(
                AppInfo(
                    originalName = "Example App",
                    displayName = "Example App",
                    packageName = "com.example.app",
                    className = "com.example.app.MainActivity",
                    isFavorite = false,
                ),
            )
        }

        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = favoritesRepository,
            favoritesOrderRepository = FakeFavoritesOrderRepository(),
            hiddenAppsRepository = FakeHiddenAppsRepository(),
            customNamesRepository = FakeCustomNamesRepository(),
            installedAppsRepository = installedAppsRepo,
            swipeActionsRepository = FakeSwipeActionsRepository(),
            settingsRepository = FakeSettingsRepository(),
            wallpaperRepository = FakeWallpaperRepository(),
            wallpaperFileManager = wallpaperFileManager,
            context = context,
        )
    }

    @Test
    fun `importFromJson - when assembler throws CancellationException - rethrows it`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { favoritesRepository.saveFavoriteComponents(any()) } throws
                CancellationException("Coroutine cancelled mid-import")

            assertFailsWith<CancellationException> {
                backupManager.importFromJson(validJsonWithFavorite, ImportOptions())
            }
        }

    @Test
    fun `importFromJson - when assembler throws IOException - returns Error with original message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { favoritesRepository.saveFavoriteComponents(any()) } throws
                IOException("DataStore disk full")

            val result = backupManager.importFromJson(validJsonWithFavorite, ImportOptions())

            assertThat(result).isInstanceOf(ImportResult.Error::class.java)
            assertThat((result as ImportResult.Error).message).isEqualTo("DataStore disk full")
        }

    @Test
    fun `importFromJson - when thrown exception has null message - returns Error with fallback message`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { favoritesRepository.saveFavoriteComponents(any()) } throws
                RuntimeException()  // message is null

            val result = backupManager.importFromJson(validJsonWithFavorite, ImportOptions())

            assertThat(result).isInstanceOf(ImportResult.Error::class.java)
            assertThat((result as ImportResult.Error).message).isEqualTo("Unknown error")
        }
}
