package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Contract for [BackupRepository] — deliberately thin, fake-only (like Kolibri's).
 *
 * NO IMPL CONTRACT TEST (ADR) — marker read by `./gradlew checkConventions`
 * (tools/check-contract-triple.sh); exempts the impl half only. The fake is a configurable
 * stub with no logic that could drift from the implementation, and the implementation
 * (`BackupRepositoryImpl` in `:nyx:data`) needs a ContentResolver, real
 * documents and every store behind it. Its behaviour is covered where that is honest:
 * `BackupRepositoryImplTest`, `NyxBackupSavePathTest` and the shared backup contracts
 * (`NyxBackupRoundTripTest`, `NyxImportKeepsMissingAppsTest`; refusals from 2b-3c).
 *
 * What this pins anyway: every operation returns without throwing on a default repository,
 * and the preview always returns a result — readable, or refused with the reason (2b-3b).
 */
abstract class BackupRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected abstract fun createRepository(): BackupRepository

    @Test
    fun saveBackupToFile_returns_without_throwing() = runTest(mainDispatcherRule.testDispatcher) {
        createRepository().saveBackupToFile(URI)
    }

    @Test
    fun loadBackupFromFile_returns_a_result() = runTest(mainDispatcherRule.testDispatcher) {
        assertThat(createRepository().loadBackupFromFile(URI, ImportOptions())).isNotNull()
    }

    @Test
    fun previewBackup_returns_without_throwing() = runTest(mainDispatcherRule.testDispatcher) {
        assertThat(createRepository().previewBackup(URI)).isNotNull()
    }

    private companion object {
        const val URI = "file:///tmp/nyx-backup.zip"
    }
}
