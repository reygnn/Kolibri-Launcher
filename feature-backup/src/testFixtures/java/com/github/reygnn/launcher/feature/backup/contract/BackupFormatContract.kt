package com.github.reygnn.launcher.feature.backup.contract

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerLimits
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.container.ContainerManifestCodec
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a launcher does with a backup the shared engine refuses (SPEC_NYX_REWRITE A2, 2b-3c) —
 * the app-visible part of `BackupFormatContract`. Format and engine internals are tested in
 * `:feature-backup` itself (`ContainerFormatTest`, `BackupEngineTest`,
 * `BackupEngineReadStagedTest`); this contract pins, per launcher, that every refusal
 *
 *  - ends in the same outcome in both launchers (until one shared result type exists, O4),
 *  - ends in the SAME outcome for the preview and for the import (2a-7b, 2b-3b: one mapping),
 *  - writes nothing, and
 *  - leaves no file behind: no image in the launcher's storage, no staging directory.
 *
 * The refused documents are built here, with the real engine and codec, so both launchers see
 * byte-identical input. Standard project contract shape (`NoAutoPruneContract`):
 * `MainDispatcherRule` + `runTest(testDispatcher)`.
 */
abstract class BackupFormatContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** How the launcher's import or preview ended, in terms both launchers share. */
    sealed interface Outcome {
        /** Imported, or (preview) readable. */
        data object Accepted : Outcome
        data class ForeignApp(val appId: String) : Outcome
        data object OutdatedFormat : Outcome
        data class UnsupportedVersion(val version: String) : Outcome
        data object Invalid : Outcome
        /** The launcher's error result — e.g. a backup too large to read. */
        data object Error : Outcome
    }

    /** The launcher's producer id and its schema in the container. */
    protected abstract val appId: String
    protected abstract val schemaVersion: Int
    protected abstract val sectionId: String
    protected abstract val sectionVersion: Int

    /** Fresh stores holding a distinctive current state — what a refused import must leave alone. */
    protected abstract suspend fun seedCurrentState()

    /** The current state, comparable before and after. */
    protected abstract suspend fun snapshot(): Any?

    /** Names of the files in the launcher's image storage (where imported images would land). */
    protected abstract fun storedFiles(): Set<String>

    /**
     * Imports [document] through the launcher's real path with every option on. [declaredSize] is
     * what the platform reports for the document; null means it reports the real size.
     */
    protected abstract suspend fun import(document: ByteArray, declaredSize: Long? = null): Outcome

    /** The launcher's real preview of [document]; [declaredSize] as for [import]. */
    protected abstract suspend fun preview(document: ByteArray, declaredSize: Long? = null): Outcome

    // ---- the refusals ----

    @Test
    fun `a backup of another app is refused as foreign`() = runTest(mainDispatcherRule.testDispatcher) {
        assertRefused(foreignBackup(), Outcome.ForeignApp(OTHER_APP))
    }

    /**
     * Was the golden case `kolibri-in-nyx` (a real Kolibri archive from before the container
     * format, imported by Nyx). The engine decides by structure alone — `backup.json` without
     * `manifest.json` — so the structure is all this case needs, in both launchers.
     */
    @Test
    fun `an archive from before the container format is refused as outdated`() = runTest(mainDispatcherRule.testDispatcher) {
        assertRefused(zipOf("backup.json" to "{}".toByteArray()), Outcome.OutdatedFormat)
    }

    @Test
    fun `a newer container format is refused as unsupported`() = runTest(mainDispatcherRule.testDispatcher) {
        val manifest = ContainerManifest(
            formatVersion = NEWER_FORMAT,
            producer = ContainerManifest.Producer(appId, "contract", 1L),
            schemaVersion = schemaVersion,
        )
        assertRefused(zipOf("manifest.json" to ContainerManifestCodec.encode(manifest)), Outcome.UnsupportedVersion(NEWER_FORMAT))
    }

    @Test
    fun `a file that is no ZIP is refused as invalid`() = runTest(mainDispatcherRule.testDispatcher) {
        assertRefused("not a backup".toByteArray(), Outcome.Invalid)
    }

    @Test
    fun `a section that does not decode is refused as invalid`() = runTest(mainDispatcherRule.testDispatcher) {
        val manifest = ContainerManifest(
            producer = ContainerManifest.Producer(appId, "contract", 1L),
            schemaVersion = schemaVersion,
            sections = mapOf(sectionId to ContainerManifest.Section(sectionVersion, JsonPrimitive("not a backup"))),
        )
        assertRefused(zipOf("manifest.json" to ContainerManifestCodec.encode(manifest)), Outcome.Invalid)
    }

    @Test
    fun `a declared size over the archive cap is refused unread`() = runTest(mainDispatcherRule.testDispatcher) {
        // Unread: read, these bytes would be Invalid; refused by size they are the error result.
        assertRefused("not a backup".toByteArray(), Outcome.Error, declaredSize = ContainerLimits().maxArchiveBytes + 1)
    }

    // ---- helpers ----

    private suspend fun assertRefused(document: ByteArray, expected: Outcome, declaredSize: Long? = null) {
        seedCurrentState()
        val state = snapshot()
        val files = storedFiles()
        val staging = stagingDirs()

        assertWithMessage("import").that(import(document, declaredSize)).isEqualTo(expected)
        assertWithMessage("preview (same mapping as the import)").that(preview(document, declaredSize)).isEqualTo(expected)

        assertWithMessage("a refused backup writes nothing").that(snapshot()).isEqualTo(state)
        assertWithMessage("a refused backup leaves no image behind").that(storedFiles()).isEqualTo(files)
        assertWithMessage("a refused backup leaves no staging directory behind").that(stagingDirs()).isEqualTo(staging)
    }

    /** A real container of another app, with a blob, so a refusal after staging would show. */
    private suspend fun foreignBackup(): ByteArray {
        val out = ByteArrayOutputStream()
        BackupEngine(mainDispatcherRule.testDispatcher, emptySet()).export(
            out,
            ContainerManifest.Producer(OTHER_APP, "contract", 1L),
            schemaVersion = 1,
            blobs = listOf(BlobSource("image/png") { ByteArrayInputStream(ByteArray(512) { it.toByte() }) }),
        ) { hashes -> mapOf("$OTHER_APP.backup" to ContainerManifest.Section(1, JsonPrimitive(hashes.single()))) }
        return out.toByteArray()
    }

    /** The engine's staging directories of this launcher under java.io.tmpdir (`<appId>-backup-…`). */
    private fun stagingDirs(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("$appId-backup-") }.orEmpty().toSet()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private companion object {
        const val OTHER_APP = "contract.other.app"
        const val NEWER_FORMAT = "9.0"
    }
}
