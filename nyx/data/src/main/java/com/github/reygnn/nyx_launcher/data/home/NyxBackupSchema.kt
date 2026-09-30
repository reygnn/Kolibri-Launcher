package com.github.reygnn.nyx_launcher.data.home

/**
 * Nyx's schema inside the shared E5a backup container (SPEC_NYX_REWRITE 2b-1). Built like
 * Kolibri's (decided 30.09.: where the two apps' backup behaviour differs, Kolibri's holds):
 * one versioned section holds the [NyxBackup] payload; wallpaper layers reference their
 * image blob by SHA-256 in `imageFileName`. Producer, app version, time and schema version
 * live in the container manifest, not in the section.
 *
 * Nyx starts directly in this format and binds no LegacyFormatReader: there are no older
 * Nyx backups in circulation (E5a), so a pre-E5a archive reads as "older version".
 */
object NyxBackupSchema {
    const val APP_ID = "nyx"
    const val SCHEMA_VERSION = 1
    const val SECTION_BACKUP = "nyx.backup"
    const val SECTION_VERSION = 1
    val KNOWN_SECTIONS: Set<String> = setOf(SECTION_BACKUP)
}
