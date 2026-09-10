package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings

/**
 * Bridge between the [BackupDataAssembler] (which coordinates repository
 * writes during import) and the file-system / Context-bound code that
 * lives in [BackupRepositoryImpl].
 *
 * The assembler calls this during phase 7 of import to apply the
 * wallpaper portion of a [LauncherSettings] payload — that step needs
 * `Context.contentResolver` (to test source-URI accessibility) and
 * [WallpaperFileManager] (to copy bytes into internal storage), neither
 * of which the assembler holds. The restorer's implementation in
 * [BackupRepositoryImpl] writes the resulting [com.github.reygnn
 * .kolibri_launcher.domain.model.WallpaperState] back through the
 * assembler so the [com.github.reygnn.kolibri_launcher.domain.repository
 * .WallpaperRepository] dependency stays in exactly one place.
 */
internal interface WallpaperRestorer {
    /**
     * Applies the wallpaper portion of [settings] and returns the number of
     * layers that were present in the backup but could not be restored
     * (image no longer accessible). `0` on a clean restore.
     */
    suspend fun restoreFromBackup(settings: LauncherSettings): Int
}
