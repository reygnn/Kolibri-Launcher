package com.github.reygnn.launcher.core.wallpaper

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the pure wallpaper-memory reporter (Rule 10). Covers the
 * total sum, the downsample decision, and locale-pinned MB formatting.
 */
class WallpaperMemoryReportTest {

    private fun row(
        index: Int = 0,
        decodedWidth: Int = 1000,
        decodedHeight: Int = 1000,
        sampleSize: Int = 1,
        originalWidth: Int = 1000,
        originalHeight: Int = 1000,
        bytes: Long = 4_000_000L,
        config: String = "HARDWARE",
    ) = WallpaperMemoryRow(
        index, decodedWidth, decodedHeight, sampleSize, originalWidth, originalHeight, bytes, config,
    )

    @Test
    fun `config name is carried through verbatim`() {
        assertThat(row(config = "ARGB_8888").config).isEqualTo("ARGB_8888")
        assertThat(row(config = "HARDWARE").config).isEqualTo("HARDWARE")
    }

    @Test
    fun `of sums the retained bytes across all rows`() {
        val report = WallpaperMemoryReport.of(
            listOf(row(bytes = 4_000_000L), row(bytes = 1_000_000L), row(bytes = 500_000L)),
        )
        assertThat(report.totalBytes).isEqualTo(5_500_000L)
    }

    @Test
    fun `of on an empty list is zero total`() {
        val report = WallpaperMemoryReport.of(emptyList())
        assertThat(report.totalBytes).isEqualTo(0L)
        assertThat(report.rows.isEmpty()).isTrue()
    }

    @Test
    fun `isDownsampled true when sampleSize gt 1 and source exceeds decoded`() {
        val r = row(
            decodedWidth = 1500, decodedHeight = 2000, sampleSize = 2,
            originalWidth = 3000, originalHeight = 4000,
        )
        assertThat(r.isDownsampled).isTrue()
    }

    @Test
    fun `isDownsampled false at full resolution`() {
        assertThat(row(sampleSize = 1).isDownsampled).isFalse()
    }

    @Test
    fun `isDownsampled false when source dims unknown (zero)`() {
        // sampleSize claims a downsample but the source is unknown (0) — do not
        // advertise a bogus "from 0x0".
        val r = row(
            decodedWidth = 1500, decodedHeight = 2000, sampleSize = 2,
            originalWidth = 0, originalHeight = 0,
        )
        assertThat(r.isDownsampled).isFalse()
    }

    @Test
    fun `formatMegabytes renders one decimal, locale-pinned`() {
        // 1_048_576 bytes = exactly 1 MB.
        assertThat(formatMegabytes(11_950_000L, Locale.US)).isEqualTo("11.4 MB")
        assertThat(formatMegabytes(1_048_576L, Locale.US)).isEqualTo("1.0 MB")
        // German locale uses a comma as the decimal separator.
        assertThat(formatMegabytes(11_950_000L, Locale.GERMANY)).isEqualTo("11,4 MB")
    }
}
