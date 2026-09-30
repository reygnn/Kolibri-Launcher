package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.container.RejectedBlob
import java.io.File
import java.io.InputStream

/**
 * Port for reading an app's pre-E5a backups (SPEC_NYX_REWRITE E5a, "Legacy module").
 * Bound as a Hilt set with an empty default (BackupEngineModule): an app that binds no
 * reader gets the targeted "older version — no longer supported" outcome instead.
 *
 * A reader is an up-converter: it turns an old archive into the CURRENT sections plus
 * staged blobs, so an old backup takes exactly the import path of a new one and gets the
 * new semantics (E1, E2, B11, B13, B14) — no second import path, no migration code.
 */
interface LegacyFormatReader {

    /** The app whose old backups this reader understands (compared with the importing app). */
    val appId: String

    /**
     * Converts the archive behind [open] (opened fresh — the engine already consumed one
     * stream to detect the legacy format) into current sections, staging blobs under
     * [stagingDir]. Null when the archive is not this reader's format. Must leave nothing
     * staged when it throws or returns null.
     */
    suspend fun read(open: () -> InputStream, stagingDir: File): LegacyConversion?
}

/** An old backup, expressed in the current format. */
data class LegacyConversion(
    val producer: ContainerManifest.Producer,
    val schemaVersion: Int,
    val sections: Map<String, ContainerManifest.Section>,
    /** hash → staged file, each verified by the reader. */
    val staged: Map<String, File>,
    val rejected: List<RejectedBlob> = emptyList(),
)
