package com.github.reygnn.launcher.feature.backup.container

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

/**
 * The container part of the BackupFormatContract (SPEC_NYX_REWRITE E5a): manifest first,
 * dedup by hash, hash/size verification per blob, unlisted blobs dropped, version gate,
 * legacy detection, caps, and "nothing staged" on every failure.
 */
class ContainerFormatTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val a = ByteArray(1000) { it.toByte() }
    private val b = ByteArray(500) { 7 }
    private val ha = hashOf(a)
    private val hb = hashOf(b)

    private fun writer() = ContainerWriter(mainDispatcherRule.testDispatcher)
    private fun reader(limits: ContainerLimits = ContainerLimits()) = ContainerReader(mainDispatcherRule.testDispatcher, limits)
    private fun staging(): File = File(tmp.root, "staging-${System.nanoTime()}")

    private fun manifest(blobs: List<BlobEntry>, version: String = ContainerFormat.CURRENT_VERSION) =
        ContainerManifestCodec.encode(
            ContainerManifest(
                formatVersion = version,
                producer = ContainerManifest.Producer("test", "1.0", 0L),
                schemaVersion = 1,
                blobs = blobs.map { ContainerManifest.Blob(it.sha256, it.size, it.mediaType) },
                sections = mapOf("probe" to ContainerManifest.Section(1, JsonPrimitive("x"))),
            ),
        )

    private suspend fun read(bytes: ByteArray, limits: ContainerLimits = ContainerLimits(), dir: File = staging()) =
        reader(limits).read(ByteArrayInputStream(bytes), dir, ContainerManifestCodec::header)

    @Test
    fun `round trip — manifest first, equal content once, staged bytes identical`() = runTest(mainDispatcherRule.testDispatcher) {
        val out = ByteArrayOutputStream()
        val table = writer().write(out, listOf(source(a), source(b), source(a.copyOf()))) { manifest(it) }

        assertThat(table.map { it.sha256 }).containsExactly(ha, hb).inOrder()
        assertThat(entryNames(out.toByteArray())).containsExactly("manifest.json", "blobs/$ha", "blobs/$hb").inOrder()

        val result = read(out.toByteArray())
        assertIsOk(result)
        result as ContainerRead.Ok
        assertThat(result.staged.getValue(ha).readBytes()).isEqualTo(a)
        assertThat(result.staged.getValue(hb).readBytes()).isEqualTo(b)
        assertThat(result.rejected).isEmpty()
        assertThat(result.missing).isEmpty()
        assertThat(result.unlisted).isEmpty()
    }

    @Test
    fun `a hash mismatch drops only that blob and is reported`() = runTest(mainDispatcherRule.testDispatcher) {
        val dir = staging()
        val bytes = zipOf(
            "manifest.json" to manifest(listOf(BlobEntry(ha, 1000, "x"), BlobEntry(hb, 500, "x"))),
            "blobs/$ha" to a.copyOf().also { it[5] = 99 },
            "blobs/$hb" to b,
        )
        val result = read(bytes, dir = dir) as ContainerRead.Ok
        assertThat(result.rejected).containsExactly(RejectedBlob(ha, BlobRejection.HASH_MISMATCH))
        assertThat(result.staged.keys).containsExactly(hb)
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(hb)
    }

    @Test
    fun `a size mismatch is rejected`() = runTest(mainDispatcherRule.testDispatcher) {
        val bytes = zipOf("manifest.json" to manifest(listOf(BlobEntry(hb, 400, "x"))), "blobs/$hb" to b)
        val result = read(bytes) as ContainerRead.Ok
        assertThat(result.rejected.single().reason).isEqualTo(BlobRejection.SIZE_MISMATCH)
        assertThat(result.staged).isEmpty()
    }

    @Test
    fun `entries without a table row are dropped, absent listed blobs are reported`() = runTest(mainDispatcherRule.testDispatcher) {
        val bytes = zipOf("manifest.json" to manifest(listOf(BlobEntry(ha, 1000, "x"))), "blobs/$hb" to b, "notes.txt" to b)
        val result = read(bytes) as ContainerRead.Ok
        assertThat(result.unlisted).containsExactly("blobs/$hb", "notes.txt").inOrder()
        assertThat(result.missing).containsExactly(ha)
        assertThat(result.staged).isEmpty()
    }

    @Test
    fun `a newer major is rejected, a newer minor is read`() = runTest(mainDispatcherRule.testDispatcher) {
        val major2 = """{"formatVersion":"2.0","somethingNew":{"x":1}}""".toByteArray()
        assertThat(read(zipOf("manifest.json" to major2))).isEqualTo(ContainerRead.UnsupportedFormat("2.0"))
        val minor = """{"formatVersion":"1.7","producer":{"appId":"t","appVersion":"1","createdAtEpochMillis":0},""" +
            """"schemaVersion":1,"futureField":true}"""
        assertIsOk(read(zipOf("manifest.json" to minor.toByteArray())))
    }

    @Test
    fun `backup_json without a manifest is a legacy backup`() = runTest(mainDispatcherRule.testDispatcher) {
        val legacy = zipOf("backup.json" to "{}".toByteArray(), "wallpapers/layer_0.img" to a)
        assertThat(read(legacy)).isEqualTo(ContainerRead.LegacyFormat)
    }

    @Test
    fun `a repacked archive with the manifest further down is invalid with a clear reason`() = runTest(mainDispatcherRule.testDispatcher) {
        val result = read(zipOf("blobs/$ha" to a, "manifest.json" to manifest(emptyList())))
        assertThat(result).isInstanceOf(ContainerRead.Invalid::class.java)
        assertThat((result as ContainerRead.Invalid).reason).contains("first")
    }

    @Test
    fun `garbage and an unreadable manifest are invalid`() = runTest(mainDispatcherRule.testDispatcher) {
        assertThat(read("hello".toByteArray())).isInstanceOf(ContainerRead.Invalid::class.java)
        assertThat(read(zipOf("manifest.json" to "zz".toByteArray()))).isInstanceOf(ContainerRead.Invalid::class.java)
    }

    @Test
    fun `caps — manifest, blob count, blob size and archive`() = runTest(mainDispatcherRule.testDispatcher) {
        assertThat(read(zipOf("manifest.json" to ByteArray(11)), ContainerLimits(maxManifestBytes = 10)))
            .isEqualTo(ContainerRead.TooLarge("manifest"))
        val two = zipOf("manifest.json" to manifest(listOf(BlobEntry(ha, 1000, "x"), BlobEntry(hb, 500, "x"))))
        assertThat(read(two, ContainerLimits(maxBlobCount = 1))).isEqualTo(ContainerRead.TooLarge("blob count"))
        val big = zipOf("manifest.json" to manifest(listOf(BlobEntry(ha, 1000, "x"))), "blobs/$ha" to a)
        assertThat(read(big, ContainerLimits(maxBlobBytes = 800))).isEqualTo(ContainerRead.TooLarge("blob"))

        val noise = ByteArray(5000).also { java.util.Random(1).nextBytes(it) }
        val archive = zipOf("manifest.json" to manifest(listOf(BlobEntry(hashOf(noise), 5000, "x"))), "blobs/${hashOf(noise)}" to noise)
        val dir = staging()
        assertThat(read(archive, ContainerLimits(maxArchiveBytes = 1000), dir)).isInstanceOf(ContainerRead.TooLarge::class.java)
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a malformed hash cannot name a file outside the staging dir`() = runTest(mainDispatcherRule.testDispatcher) {
        val evil = zipOf("manifest.json" to manifest(listOf(BlobEntry("../../etc", 3, "x"))), "blobs/../../etc" to "abc".toByteArray())
        assertThat(read(evil)).isInstanceOf(ContainerRead.Invalid::class.java)
    }

    @Test
    fun `a blob that changes between the passes aborts the export`() = runTest(mainDispatcherRule.testDispatcher) {
        var calls = 0
        val flaky = BlobSource("x") { calls++; ByteArrayInputStream(if (calls == 1) a else b) }
        assertFailsWith<BlobChangedDuringExportException> {
            writer().write(ByteArrayOutputStream(), listOf(flaky)) { manifest(it) }
        }
    }

    @Test
    fun `the writer leaves the caller's stream open`() = runTest(mainDispatcherRule.testDispatcher) {
        var closed = false
        val sink = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        writer().write(sink, listOf(source(a))) { manifest(it) }
        assertThat(closed).isFalse()
    }

    @Test
    fun `the codec round-trips a manifest and rejects garbage`() {
        val m = ContainerManifest(
            producer = ContainerManifest.Producer("kolibri", "1.2.3", 42L),
            schemaVersion = 7,
            blobs = listOf(ContainerManifest.Blob(ha, 1000, "image/png")),
            sections = mapOf("favorites" to ContainerManifest.Section(2, JsonPrimitive("data"))),
        )
        assertThat(ContainerManifestCodec.decode(ContainerManifestCodec.encode(m))).isEqualTo(m)
        assertThat(ContainerManifestCodec.decode("nope".toByteArray())).isNull()
        assertThat(ContainerManifestCodec.header("""{"formatVersion":"x"}""".toByteArray())).isNull()
    }

    // ---- helpers ----

    private fun assertIsOk(result: ContainerRead) = assertThat(result).isInstanceOf(ContainerRead.Ok::class.java)

    private fun source(bytes: ByteArray) = BlobSource("image/png") { ByteArrayInputStream(bytes) }

    private fun hashOf(bytes: ByteArray) = sha256().also { it.update(bytes) }.hex()

    private fun entryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry?.name }.toList() }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }.toByteArray()
}
