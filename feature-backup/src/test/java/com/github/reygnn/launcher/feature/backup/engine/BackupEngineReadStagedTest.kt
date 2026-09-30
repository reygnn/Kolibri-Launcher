package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerFormat
import com.github.reygnn.launcher.feature.backup.container.ContainerLimits
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertFailsWith

/**
 * The staging frame of [BackupEngine.readStaged] (2b-0, BackupEngineContract "unclaimed
 * blobs are cleaned up"): every path leaves the staging root empty, claimed blobs survive,
 * and a declared size above the archive cap is refused without reading.
 */
class BackupEngineReadStagedTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val image = ByteArray(2048) { (it % 89).toByte() }
    private val known = setOf("wallpaper")

    private fun engine(stagingRoot: File) =
        BackupEngine(mainDispatcherRule.testDispatcher, emptySet(), ContainerLimits(), stagingRoot)

    private suspend fun exported(appId: String = "kolibri"): ByteArray {
        val out = ByteArrayOutputStream()
        engine(tmp.newFolder()).export(
            out,
            ContainerManifest.Producer(appId, "1.0", 1L),
            schemaVersion = 1,
            blobs = listOf(BlobSource("image/png") { ByteArrayInputStream(image) }),
        ) { hashes -> mapOf("wallpaper" to ContainerManifest.Section(1, JsonArray(hashes.map { JsonPrimitive(it) }))) }
        return out.toByteArray()
    }

    private fun opener(bytes: ByteArray): () -> InputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `ok - claimed blob survives, staging is gone afterwards`() = runTest(mainDispatcherRule.testDispatcher) {
        val root = tmp.newFolder()
        val target = File(tmp.root, "wallpapers/layer.png")
        val bytes = exported()

        val name = engine(root).readStaged(opener(bytes), "kolibri", known, kind = "import") { read, stagingDir ->
            assertThat(read).isInstanceOf(BackupRead.Ok::class.java)
            read as BackupRead.Ok
            read.blobs.claim(read.blobs.hashes.single(), target)
            stagingDir.name
        }

        assertThat(name).startsWith("kolibri-backup-import-")
        assertThat(target.readBytes()).isEqualTo(image)
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `ok - an unclaimed blob is deleted with the staging dir`() = runTest(mainDispatcherRule.testDispatcher) {
        val root = tmp.newFolder()
        var staged: Set<String> = emptySet()

        engine(root).readStaged(opener(exported()), "kolibri", known, kind = "preview") { read, _ ->
            staged = (read as BackupRead.Ok).blobs.hashes
        }

        assertThat(staged).hasSize(1)
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a refused backup reaches onRead and leaves nothing behind`() = runTest(mainDispatcherRule.testDispatcher) {
        val root = tmp.newFolder()

        val read = engine(root).readStaged(opener(exported(appId = "nyx")), "kolibri", known, kind = "import") { read, _ -> read }

        assertThat(read).isEqualTo(BackupRead.ForeignApp("nyx"))
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a throwing onRead still deletes the staging dir`() = runTest(mainDispatcherRule.testDispatcher) {
        val root = tmp.newFolder()

        assertFailsWith<CancellationException> {
            engine(root).readStaged(opener(exported()), "kolibri", known, kind = "import") { _, _ ->
                throw CancellationException("left the screen")
            }
        }

        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a declared size above the archive cap is too large without opening the document`() = runTest(mainDispatcherRule.testDispatcher) {
        var opened = 0
        val limits = ContainerLimits()

        val read = engine(tmp.newFolder()).readStaged(
            open = { opened++; ByteArrayInputStream(ByteArray(0)) },
            appId = "kolibri",
            knownSections = known,
            kind = "import",
            declaredSize = limits.maxArchiveBytes + 1,
        ) { read, _ -> read }

        assertThat(read).isEqualTo(BackupRead.TooLarge(ContainerFormat.CAP_ARCHIVE))
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a declared size at the cap or unknown is read normally`() = runTest(mainDispatcherRule.testDispatcher) {
        val bytes = exported()
        val atCap = ContainerLimits().maxArchiveBytes

        for (size in listOf(atCap, UNKNOWN_SIZE)) {
            val read = engine(tmp.newFolder()).readStaged(opener(bytes), "kolibri", known, "import", size) { read, _ -> read }
            assertThat(read).isInstanceOf(BackupRead.Ok::class.java)
        }
    }
}
