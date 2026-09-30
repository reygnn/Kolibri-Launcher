package com.github.reygnn.launcher.feature.backup.engine

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerLimits
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The engine part of the BackupEngineContract / BackupFormatContract (SPEC_NYX_REWRITE
 * 2a-3): sections reference blobs by hash, another app's backup is refused and leaves
 * nothing staged, unknown sections are reported, legacy archives go through the port,
 * staged blobs are claimed or cleaned up.
 */
class BackupEngineTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val image = ByteArray(2048) { (it % 97).toByte() }
    private val producer = ContainerManifest.Producer("kolibri", "1.0", 1L)

    private fun engine(legacy: Set<LegacyFormatReader> = emptySet()) =
        BackupEngine(mainDispatcherRule.testDispatcher, legacy, ContainerLimits())

    private suspend fun exported(appId: String = "kolibri", extraSection: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        engine().export(out, producer.copy(appId = appId), schemaVersion = 3, blobs = listOf(source(image), source(image))) { hashes ->
            buildMap {
                put("wallpaper", ContainerManifest.Section(1, JsonArray(hashes.map { JsonPrimitive(it) })))
                put("favorites", ContainerManifest.Section(2, JsonPrimitive("a/a.Main")))
                if (extraSection != null) put(extraSection, ContainerManifest.Section(1, JsonPrimitive("?")))
            }
        }
        return out.toByteArray()
    }

    private fun opener(bytes: ByteArray): () -> InputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `sections reference blobs by hash and the blob is stored once`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val read = engine().read(opener(exported()), staging, "kolibri", setOf("wallpaper", "favorites"))
        assertThat(read).isInstanceOf(BackupRead.Ok::class.java)
        read as BackupRead.Ok
        read.blobs.use { blobs ->
            val refs = (read.manifest.sections.getValue("wallpaper").data as JsonArray).map { it.jsonPrimitive.content }
            assertThat(refs.distinct()).hasSize(1)
            assertThat(blobs.hashes).containsExactly(refs.first())
            assertThat(read.manifest.schemaVersion).isEqualTo(3)
            assertThat(read.unknownSections).isEmpty()
            assertThat(read.fromLegacyFormat).isFalse()
        }
    }

    @Test
    fun `claimed blobs survive close, unclaimed ones are deleted`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val read = engine().read(opener(exported()), staging, "kolibri", setOf("wallpaper", "favorites")) as BackupRead.Ok
        val hash = read.blobs.hashes.single()
        val target = File(tmp.root, "wallpapers/layer.png")
        read.blobs.use { assertThat(it.claim(hash, target)).isEqualTo(target) }
        assertThat(target.readBytes()).isEqualTo(image)
        assertThat(staging.listFiles().orEmpty()).isEmpty()

        val second = engine().read(opener(exported()), staging, "kolibri", setOf("wallpaper", "favorites")) as BackupRead.Ok
        second.blobs.close() // nothing claimed
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `another app's backup is refused and leaves nothing staged`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val read = engine().read(opener(exported(appId = "nyx")), staging, "kolibri", setOf("wallpaper", "favorites"))
        assertThat(read).isEqualTo(BackupRead.ForeignApp("nyx"))
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `an unknown section is reported, not an error`() = runTest(mainDispatcherRule.testDispatcher) {
        val read = engine().read(opener(exported(extraSection = "future.thing")), tmp.newFolder(), "kolibri", setOf("wallpaper", "favorites"))
        read as BackupRead.Ok
        read.blobs.close()
        assertThat(read.unknownSections).containsExactly("future.thing")
    }

    @Test
    fun `a legacy archive without a reader is the targeted outdated outcome`() = runTest(mainDispatcherRule.testDispatcher) {
        val read = engine().read(opener(legacyArchive()), tmp.newFolder(), "kolibri", emptySet())
        assertThat(read).isEqualTo(BackupRead.OutdatedFormat)
    }

    @Test
    fun `a legacy archive goes through the matching reader and reads as current sections`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val otherApp = FakeLegacyReader("nyx", staging)
        val kolibri = FakeLegacyReader("kolibri", staging)
        val read = engine(setOf(otherApp, kolibri)).read(opener(legacyArchive()), staging, "kolibri", setOf("favorites"))
        assertThat(otherApp.calls).isEqualTo(0)
        assertThat(read).isInstanceOf(BackupRead.Ok::class.java)
        read as BackupRead.Ok
        read.blobs.use {
            assertThat(read.fromLegacyFormat).isTrue()
            assertThat(read.manifest.sections.keys).containsExactly("favorites")
            assertThat(it.hashes).containsExactly("f".repeat(64))
        }
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    // ---- helpers ----

    private fun source(bytes: ByteArray) = BlobSource("image/png") { ByteArrayInputStream(bytes) }

    private fun legacyArchive(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    private class FakeLegacyReader(override val appId: String, private val staging: File) : LegacyFormatReader {
        var calls = 0
        override suspend fun read(open: () -> InputStream, stagingDir: File): LegacyConversion? {
            calls++
            open().use { it.readBytes() }
            val blob = File(staging, "f".repeat(64)).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return LegacyConversion(
                producer = ContainerManifest.Producer(appId, "0.9", 0L),
                schemaVersion = 1,
                sections = mapOf("favorites" to ContainerManifest.Section(1, JsonPrimitive("a/a.Main"))),
                staged = mapOf(blob.name to blob),
            )
        }
    }
}
