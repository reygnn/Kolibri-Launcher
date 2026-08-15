package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    ) = WallpaperMemoryRow(
        index, decodedWidth, decodedHeight, sampleSize, originalWidth, originalHeight, bytes,
    )

    @Test
    fun `of sums the retained bytes across all rows`() {
        val report = WallpaperMemoryReport.of(
            listOf(row(bytes = 4_000_000L), row(bytes = 1_000_000L), row(bytes = 500_000L)),
        )
        assertEquals(5_500_000L, report.totalBytes)
    }

    @Test
    fun `of on an empty list is zero total`() {
        val report = WallpaperMemoryReport.of(emptyList())
        assertEquals(0L, report.totalBytes)
        assertTrue(report.rows.isEmpty())
    }

    @Test
    fun `isDownsampled true when sampleSize gt 1 and source exceeds decoded`() {
        val r = row(
            decodedWidth = 1500, decodedHeight = 2000, sampleSize = 2,
            originalWidth = 3000, originalHeight = 4000,
        )
        assertTrue(r.isDownsampled)
    }

    @Test
    fun `isDownsampled false at full resolution`() {
        assertFalse(row(sampleSize = 1).isDownsampled)
    }

    @Test
    fun `isDownsampled false when source dims unknown (zero)`() {
        // sampleSize claims a downsample but the source is unknown (0) — do not
        // advertise a bogus "from 0x0".
        val r = row(
            decodedWidth = 1500, decodedHeight = 2000, sampleSize = 2,
            originalWidth = 0, originalHeight = 0,
        )
        assertFalse(r.isDownsampled)
    }

    @Test
    fun `formatMegabytes renders one decimal, locale-pinned`() {
        // 1_048_576 bytes = exactly 1 MB.
        assertEquals("11.4 MB", formatMegabytes(11_950_000L, Locale.US))
        assertEquals("1.0 MB", formatMegabytes(1_048_576L, Locale.US))
        // German locale uses a comma as the decimal separator.
        assertEquals("11,4 MB", formatMegabytes(11_950_000L, Locale.GERMANY))
    }
}
