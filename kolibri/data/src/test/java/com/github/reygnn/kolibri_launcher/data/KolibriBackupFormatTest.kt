package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.feature.backup.contract.BackupFormatContract
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kolibri's run of the shared [BackupFormatContract] (2b-3c) through `loadBackupFromFile` and
 * `previewBackup` on fresh fakes ([KolibriBackupHarness]); no legacy reader is bound, as after
 * the sunset of `:kolibri:backup-legacy`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriBackupFormatTest : BackupFormatContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var harness: KolibriBackupHarness

    override val appId = KolibriBackupSchema.APP_ID
    override val schemaVersion = KolibriBackupSchema.SCHEMA_VERSION
    override val sectionId = KolibriBackupSchema.SECTION_BACKUP
    override val sectionVersion = KolibriBackupSchema.SECTION_VERSION

    override suspend fun seedCurrentState() {
        harness = KolibriBackupHarness(tmp.newFolder())
        harness.favorites.favorites = setOf("com.cur/com.cur.Main")
        harness.order.order = listOf("com.cur/com.cur.Main")
        harness.hidden.hiddenApps = setOf("com.curh/com.curh.Main")
        harness.names.setCustomNamesInBatch(mapOf("com.cur" to "Current"))
        harness.settings.color = 0xFF123456.toInt()
    }

    override suspend fun snapshot(): Any? = listOf(
        harness.favorites.favorites,
        harness.order.order,
        harness.hidden.hiddenApps,
        harness.names.getAllCustomNames(),
        harness.settings.color,
        harness.wallpaper.currentState,
    )

    override fun storedFiles(): Set<String> = harness.storedImages()

    override suspend fun import(document: ByteArray, declaredSize: Long?): Outcome {
        harness.declaredSize = declaredSize
        return outcomeOf(harness.import(document, ImportOptions()))
    }

    override suspend fun preview(document: ByteArray, declaredSize: Long?): Outcome {
        harness.declaredSize = declaredSize
        return when (val result = harness.preview(document)) {
            is PreviewResult.Readable -> Outcome.Accepted
            is PreviewResult.Refused -> outcomeOf(result.result)
        }
    }

    private fun outcomeOf(result: ImportResult): Outcome = when (result) {
        is ImportResult.Success -> Outcome.Accepted
        is ImportResult.ForeignBackup -> Outcome.ForeignApp(result.appId)
        ImportResult.OutdatedBackup -> Outcome.OutdatedFormat
        is ImportResult.UnsupportedVersion -> Outcome.UnsupportedVersion(result.version)
        ImportResult.InvalidFormat -> Outcome.Invalid
        is ImportResult.Error, is ImportResult.LimitExceeded -> Outcome.Error
    }
}
