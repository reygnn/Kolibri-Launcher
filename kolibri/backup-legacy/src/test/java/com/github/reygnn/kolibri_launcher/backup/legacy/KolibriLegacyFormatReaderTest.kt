package com.github.reygnn.kolibri_launcher.backup.legacy

import com.github.reygnn.kolibri_launcher.data.BackupSerializer
import com.github.reygnn.kolibri_launcher.data.KolibriBackupSchema
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.feature.backup.engine.LegacyConversion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

/**
 * The pre-E5a reader (SPEC_NYX_REWRITE 2a-6): old archive → current `kolibri.backup` section
 * + staged blobs; the old reader's caps (moved here from BackupRepositoryImplIoTest); and the
 * golden set's "blob binding" — each layer references exactly the image of its old archive.
 */
class KolibriLegacyFormatReaderTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val serializer = BackupSerializer()
    private val reader = KolibriLegacyFormatReader(serializer)

    @Test
    fun `an old archive becomes the current section, layers reference their blob by hash`() = runTest(mainDispatcherRule.testDispatcher) {
        val image = ByteArray(700) { (it % 11).toByte() }
        val archive = legacyArchive(
            manifest(
                WallpaperLayerBackup(id = "a", imageUri = "file:///old/a.img", imageFileName = "wallpapers/layer_0.img"),
                WallpaperLayerBackup(id = "b", imageUri = "file:///old/b.img", imageFileName = "wallpapers/layer_1.img"),
                WallpaperLayerBackup(id = "c", imageUri = "content://media/1"),
            ),
            "wallpapers/layer_0.img" to image,
            "wallpapers/layer_1.img" to image.copyOf(), // equal content → one blob
        )
        val staging = tmp.newFolder()

        val conversion = checkNotNull(reader.read({ ByteArrayInputStream(archive) }, staging))

        val hash = sha256(image)
        assertThat(conversion.staged.keys).containsExactly(hash)
        assertThat(conversion.staged.getValue(hash).readBytes()).isEqualTo(image)
        val layers = settingsOf(conversion).wallpaperLayers
        assertThat(layers.map { it.imageFileName }).containsExactly(hash, hash, null).inOrder()
        assertThat(layers[0].imageUri).isNull() // the blob is the source of truth
        assertThat(layers[2].imageUri).isEqualTo("content://media/1") // not embedded, kept
        assertThat(conversion.producer.appId).isEqualTo(KolibriBackupSchema.APP_ID)
    }

    @Test
    fun `an over-sized image fails with too large and leaves nothing staged`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val archive = legacyArchive(manifest(), "wallpapers/ok.img" to byteArrayOf(1), "wallpapers/big.img" to ByteArray(11 * 1024 * 1024))
        val error = assertFailsWith<IOException> { reader.read({ ByteArrayInputStream(archive) }, staging) }
        assertThat(error.message).contains("too large")
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `too many images fail with too many and leave nothing staged`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val many = (0..64).map { "wallpapers/img_$it.img" to byteArrayOf(it.toByte()) }.toTypedArray()
        val error = assertFailsWith<IOException> { reader.read({ ByteArrayInputStream(legacyArchive(manifest(), *many)) }, staging) }
        assertThat(error.message).contains("too many")
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `an archive over the whole-archive cap fails with too large`() = runTest(mainDispatcherRule.testDispatcher) {
        val noise = ByteArray((AppConstants.MAX_BACKUP_SIZE_BYTES + 1024).toInt()).also { java.util.Random(7).nextBytes(it) }
        val archive = legacyArchive(manifest(), "pad.bin" to noise)
        val error = assertFailsWith<IOException> { reader.read({ ByteArrayInputStream(archive) }, tmp.newFolder()) }
        assertThat(error.message).contains("too large")
    }

    @Test
    fun `an unreadable backup_json yields no sections, which the import reports as invalid`() = runTest(mainDispatcherRule.testDispatcher) {
        val conversion = checkNotNull(reader.read({ ByteArrayInputStream(legacyArchive("{ not json")) }, tmp.newFolder()))
        assertThat(conversion.sections).isEmpty()
    }

    @Test
    fun `an archive without backup_json is not this reader's format`() = runTest(mainDispatcherRule.testDispatcher) {
        val staging = tmp.newFolder()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("wallpapers/x.img")); it.write(byteArrayOf(1)); it.closeEntry() }
        }.toByteArray()
        assertThat(reader.read({ ByteArrayInputStream(zip) }, staging)).isNull()
        assertThat(staging.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `golden set - every layer references exactly the image of its old archive`() = runTest(mainDispatcherRule.testDispatcher) {
        var checked = 0
        for (expectedFile in goldenDir().listFiles { f -> f.name.endsWith(".expected.json") }.orEmpty().sortedBy { it.name }) {
            val expected = JSONObject(expectedFile.readText())
            val archive = File(goldenDir(), expected.getString("backup"))
            if (expected.getString("importer") != "kolibri" || !archive.exists()) continue
            val caseName = expected.getString("case")
            val conversion = checkNotNull(reader.read({ archive.inputStream() }, tmp.newFolder())) { caseName }
            val layers = settingsOf(conversion).wallpaperLayers.filter { it.imageFileName != null }
            val wallpaper = expected.getJSONObject("expected").opt("wallpaper")
            if (wallpaper is JSONObject) {
                val want = wallpaper.getJSONArray("layers")
                assertThat(layers.size).isEqualTo(want.length())
                for (i in 0 until want.length()) {
                    val w = want.getJSONObject(i)
                    assertThat(layers[i].id).isEqualTo(w.getString("id"))
                    assertThat(layers[i].imageFileName).isEqualTo(w.getString("blobSha256"))
                    assertThat(conversion.staged).containsKey(w.getString("blobSha256"))
                    assertThat(layers[i].scale.toDouble()).isWithin(1e-5).of(w.getDouble("scale"))
                    assertThat(layers[i].translateX.toDouble()).isWithin(1e-4).of(w.getDouble("translateX"))
                    assertThat(layers[i].translateY.toDouble()).isWithin(1e-4).of(w.getDouble("translateY"))
                }
            }
            conversion.staged.values.forEach { it.delete() }
            checked++
        }
        assertThat(checked).isAtLeast(3) // voll, kurzform, ohne-wallpaper (+ werte-ausserhalb)
    }

    // ---- helpers ----

    private fun manifest(vararg layers: WallpaperLayerBackup): String = serializer.encodeToJsonString(
        BackupData(version = AppConstants.BACKUP_VERSION, timestamp = 1L, settings = LauncherSettings(wallpaperLayers = layers.toList())),
    )

    private fun legacyArchive(manifest: String, vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
        }.toByteArray()

    private fun settingsOf(conversion: LegacyConversion): LauncherSettings =
        checkNotNull(serializer.settingsFromJson(conversion.sections.getValue(KolibriBackupSchema.SECTION_BACKUP).data))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun goldenDir(): File =
        checkNotNull(File(checkNotNull(javaClass.classLoader?.getResource("golden/README.md")) { "golden resources missing" }.toURI()).parentFile)
}
