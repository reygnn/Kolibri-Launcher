package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every import outcome has its own message (2b-3b); none falls back to "invalid" any more. */
class BackupMessageTest {

    @Test
    fun every_outcome_maps_to_its_own_message() {
        val expected = mapOf(
            ImportResult.Success() to BackupMessage.ImportDone(0),
            ImportResult.Success(droppedWallpaperLayers = 2) to BackupMessage.ImportDone(2),
            ImportResult.ForeignBackup("kolibri") to BackupMessage.ForeignBackup("kolibri"),
            ImportResult.OutdatedBackup to BackupMessage.OutdatedBackup,
            ImportResult.UnsupportedVersion("9.0") to BackupMessage.UnsupportedVersion("9.0"),
            ImportResult.InvalidFormat to BackupMessage.InvalidBackup,
            ImportResult.Error("Backup file is too large") to BackupMessage.ImportFailed,
        )
        for ((result, message) in expected) {
            assertThat(BackupMessage.forImport(result)).isEqualTo(message)
        }
    }
}
