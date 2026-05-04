package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Why instrumented: BackupRepositoryImpl talks to ContentResolver via
 * openInputStream / openOutputStream / openFileDescriptor(uri, "r").
 * Robolectric's ContentResolver is a stub — it does not return a real
 * ParcelFileDescriptor (statSize is always 0 → the size-guard at line 534
 * of BackupRepositoryImpl is dead under Robolectric) and it doesn't
 * propagate SecurityException for revoked permissions. JVM tests cannot
 * exercise the actual file path this code takes in production.
 *
 * Strategy: use a real file:// URI rooted in cacheDir. file:// goes through
 * the same ContentResolver code path as content:// for openInputStream/
 * openOutputStream, AND through openFileDescriptor for the size check, but
 * doesn't require SAF dialog interaction (which we don't want in a unit-
 * scoped test — see OnboardingToHomeSmokeTest for the SAF-dialog level).
 */
@HiltAndroidTest
class BackupRoundTripSafTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var backup: BackupRepository
    @Inject lateinit var favorites: FavoritesRepository
    @Inject lateinit var hidden: HiddenAppsRepository
    @Inject lateinit var customNames: CustomNamesRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var backupFile: File

    @Before
    fun setUp() {
        hiltRule.inject()
        backupFile = File(context.cacheDir, "test-backup-${System.nanoTime()}.zip")
            .also { it.parentFile?.mkdirs(); it.delete() }
    }

    @Test
    fun saveAndLoad_throughRealContentResolver_restoresAllRepositories() = runBlocking {
        // ── ARRANGE: seed each repository with a distinguishable value ───────
        favorites.addFavoriteComponent("com.example.alpha")
        favorites.addFavoriteComponent("com.example.beta")
        hidden.hideComponent("com.example.hide.me")
        customNames.setCustomNameForPackage("com.example.alpha", "α")
        // Pick any settings flag whose getter returns a Flow<Boolean>; we
        // intentionally flip from default (false) to make the assertion
        // non-trivial. doubleTapToLock falls under importQualityOfLife,
        // which defaults to true in ImportOptions().
        settings.setDoubleTapToLock(true)

        val uri = backupFile.toUri().toString()

        // ── ACT 1: save through the real ContentResolver path ────────────────
        val saved = withTimeout(10_000) { backup.saveBackupToFile(uri) }
        assertThat(saved).isTrue()
        assertThat(backupFile.exists()).isTrue()
        assertThat(backupFile.length()).isGreaterThan(0L)

        // ── ARRANGE 2: wipe all live state to prove restore actually does work ─
        favorites.purgeRepository()
        hidden.purgeRepository()
        customNames.purgeRepository()
        settings.setDoubleTapToLock(false)

        assertThat(favorites.favoriteComponentsFlow.first()).isEmpty()

        // ── ACT 2: load through the real openFileDescriptor + openInputStream ─
        val result = withTimeout(10_000) {
            backup.loadBackupFromFile(uri, ImportOptions())
        }

        // ── ASSERT ───────────────────────────────────────────────────────────
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredFavs = favorites.favoriteComponentsFlow.first()
        assertThat(restoredFavs).containsExactly("com.example.alpha", "com.example.beta")
        assertThat(hidden.hiddenAppsFlow.first()).contains("com.example.hide.me")
        assertThat(customNames.getAllCustomNames()["com.example.alpha"]).isEqualTo("α")
        assertThat(settings.doubleTapToLockEnabledFlow.first()).isTrue()
    }

    @Test
    fun loadFromFile_oversizedFile_returnsErrorWithoutOOM() = runBlocking {
        // The size guard at BackupRepositoryImpl:534 only works if
        // openFileDescriptor(uri, MODE_READ_ONLY).statSize returns the
        // real size — under Robolectric this returns 0 and the guard is
        // dead. This is the test that proves the guard is wired correctly
        // against a real ContentResolver.
        backupFile.outputStream().use { out ->
            // Write enough non-zip garbage to exceed MAX_BACKUP_SIZE_BYTES.
            // We keep it just over the threshold to keep the test fast;
            // adapt the constant if MAX_BACKUP_SIZE_BYTES changes.
            val chunk = ByteArray(1024 * 1024) { 0x41 } // 1 MiB of 'A'
            repeat(60) { out.write(chunk) } // 60 MiB > default 50 MiB cap
        }

        val result = withTimeout(10_000) {
            backup.loadBackupFromFile(backupFile.toUri().toString(), ImportOptions())
        }

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        // sanity: we hit the size branch, not a parse branch.
        assertThat((result as ImportResult.Error).message).contains("too large")
    }
}
