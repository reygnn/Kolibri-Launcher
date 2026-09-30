package com.github.reygnn.kolibri_launcher.data

/**
 * Kolibri's schema inside the shared E5a backup container (SPEC_NYX_REWRITE 2a-5). Shared
 * by the writer/reader in BackupRepositoryImpl and by :kolibri:backup-legacy, which
 * up-converts pre-E5a archives into exactly this section — one import path for both.
 *
 * One versioned section holds the LauncherSettings; wallpaper layers reference their
 * image blob by SHA-256 in `imageFileName`. Splitting it into several sections later is
 * what the per-section version is for.
 */
object KolibriBackupSchema {
    const val APP_ID = "kolibri"
    const val SCHEMA_VERSION = 1
    const val SECTION_BACKUP = "kolibri.backup"
    const val SECTION_VERSION = 1
    val KNOWN_SECTIONS: Set<String> = setOf(SECTION_BACKUP)
}
