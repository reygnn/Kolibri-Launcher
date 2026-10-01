package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.contract.ImportKeepsMissingAppsContract
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Kolibri's run of the shared [ImportKeepsMissingAppsContract] (2b-2c): the backup carries the
 * missing app in favorites, their order, the hidden apps and a swipe slot; only a sentinel
 * app is installed ([KolibriBackupHarness]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriImportKeepsMissingAppsTest : ImportKeepsMissingAppsContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timberRule = TimberRule()

    override suspend fun importReferencing(storedClassName: String): Map<String, Set<ComponentKey>> {
        val stored = "${missing.packageName}/$storedClassName"
        val settings = LauncherSettings(
            favoriteComponents = setOf(stored),
            favoritesOrder = listOf(stored),
            hiddenComponents = setOf(stored),
            swipeLeftApp = stored,
        )
        val out = ByteArrayOutputStream()
        BackupEngine(mainDispatcherRule.testDispatcher, emptySet()).export(
            out,
            ContainerManifest.Producer(KolibriBackupSchema.APP_ID, "test", 1L),
            KolibriBackupSchema.SCHEMA_VERSION,
            emptyList(),
        ) {
            mapOf(KolibriBackupSchema.SECTION_BACKUP to ContainerManifest.Section(KolibriBackupSchema.SECTION_VERSION, BackupSerializer().settingsToJson(settings)))
        }

        val harness = KolibriBackupHarness(tmp.root)
        val result = harness.import(out.toByteArray(), ImportOptions())
        check(result is ImportResult.Success) { "import failed: $result" }

        fun keys(flat: Collection<String?>) = flat.mapNotNullTo(HashSet()) { value -> value?.let { ComponentKey.parse(it) } }
        return mapOf(
            "favorites" to keys(harness.favorites.favorites),
            "favorites order" to keys(harness.order.order),
            "hidden apps" to keys(harness.hidden.hiddenApps),
            "swipe slot" to keys(listOf(harness.swipe.swipeLeftApp)),
        )
    }
}
