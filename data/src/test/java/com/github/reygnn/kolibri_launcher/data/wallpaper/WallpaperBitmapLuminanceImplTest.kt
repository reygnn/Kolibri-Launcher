package com.github.reygnn.kolibri_launcher.data.wallpaper

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Tests for [WallpaperBitmapLuminanceImpl]. Robolectric is required
 * for `android.graphics.Bitmap` + `BitmapFactory.decodeStream` —
 * same reason as `WallpaperRepositoryImplTest`.
 *
 * Strategy: build a Bitmap programmatically with a known colour
 * distribution, compress to PNG, hand the bytes to the impl via a
 * mocked `ContentResolver.openInputStream`, and assert the returned
 * luminance is in the expected band.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperBitmapLuminanceImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk()

    init {
        every { context.contentResolver } returns contentResolver
    }

    private val luminance = WallpaperBitmapLuminanceImpl(context)

    @Test
    fun `pure white image returns luminance ~ 1`() = runTest(mainDispatcherRule.testDispatcher) {
        val bitmap = solidBitmap(Color.WHITE)
        stubContentResolver(bitmap)
        val result = luminance.compute("file:///white.png")
        assertNotNull(result)
        assertEquals(1.0f, result!!, 0.01f)
    }

    @Test
    fun `pure black image returns luminance ~ 0`() = runTest(mainDispatcherRule.testDispatcher) {
        val bitmap = solidBitmap(Color.BLACK)
        stubContentResolver(bitmap)
        val result = luminance.compute("file:///black.png")
        assertNotNull(result)
        assertEquals(0.0f, result!!, 0.01f)
    }

    @Test
    fun `mid-grey image returns luminance below 0_5 — sRGB nonlinear`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // sRGB midpoint #808080 has WCAG luminance ≈ 0.215 — well
            // below 0.5 because the gamma curve weighs the middle of
            // the channel toward dark. Pin the band rather than the
            // exact value to stay robust to PNG re-encoding noise.
            val bitmap = solidBitmap(Color.rgb(0x80, 0x80, 0x80))
            stubContentResolver(bitmap)
            val result = luminance.compute("file:///grey.png")
            assertNotNull(result)
            assertTrue("expected ~0.215, got $result", result!! in 0.18f..0.25f)
        }

    @Test
    fun `fully transparent image returns null — coverage gate`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 0% effectively-opaque pixels → coverage 0.0 < 0.5 → null.
            val bitmap = solidBitmap(Color.TRANSPARENT)
            stubContentResolver(bitmap)
            val result = luminance.compute("file:///fully-transparent.png")
            assertNull(result)
        }

    @Test
    fun `low-coverage opaque content over transparent returns null — coverage gate`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 25% opaque — below the 50% gate. Mirrors the empirical
            // anchor in the impl's KDoc: testPics/transparent.png at
            // 13.8% coverage falls through; this synthetic case at
            // 25% is still below the threshold.
            val bitmap = bitmapWithCoverage(
                opaqueColor = Color.BLACK,
                opaqueFraction = 0.25f,
            )
            stubContentResolver(bitmap)
            val result = luminance.compute("file:///mostly-transparent.png")
            assertNull(result)
        }

    @Test
    fun `coverage above gate uses only opaque pixels for median`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 75% opaque WHITE, 25% transparent. Without alpha
            // awareness the transparent-RGB-zero pixels would drag
            // the median toward black; the alpha-filter means the
            // median is computed over the white pixels only and
            // returns ~1.0.
            val bitmap = bitmapWithCoverage(
                opaqueColor = Color.WHITE,
                opaqueFraction = 0.75f,
            )
            stubContentResolver(bitmap)
            val result = luminance.compute("file:///mostly-white.png")
            assertNotNull(result)
            assertEquals(1.0f, result!!, 0.01f)
        }

    // ============================================================
    // Real-world-fixture pin: empirical anchors for the AMOLED-to-
    // transparent converter use case. The two PNGs in
    // `data/src/test/resources/wallpaper/` are real outputs of
    // chiaroscuro (https://github.com/reygnn/chiaroscuro), the
    // maintainer's AMOLED-black → transparent PNG converter; see
    // the README in that resource directory for provenance and
    // ground-truth pixel statistics. These tests lock in the
    // Python analysis (amoled.png 100% opaque + dark;
    // transparent.png 13.8% opaque, well below the 50% gate). If
    // a future change breaks AMOLED-converted wallpapers — e.g.,
    // raising the coverage threshold past 13.8%, or losing alpha
    // awareness in the median compute — these tests catch it.
    //
    // Bands rather than exact values: Robolectric's PNG decode is
    // faithful but not floating-point-byte-identical to the Android
    // framework, and the impl downscales 704×1504 → 32×32 with
    // bilinear filtering, which can shift the median by a few
    // promille across runs.
    // ============================================================

    @Test
    fun `fixture amoled-png — fully-opaque AMOLED, classifies dark`() =
        runTest(mainDispatcherRule.testDispatcher) {
            stubContentResolverFromResource("/wallpaper/amoled.png")
            val result = luminance.compute("file:///amoled.png")
            assertNotNull("expected non-null for fully-opaque image", result)
            assertTrue(
                "expected near-black luminance for AMOLED original, got $result",
                result!! < 0.05f,
            )
        }

    @Test
    fun `fixture transparent-png — AMOLED-converted, returns null via coverage gate`() =
        runTest(mainDispatcherRule.testDispatcher) {
            stubContentResolverFromResource("/wallpaper/transparent.png")
            val result = luminance.compute("file:///transparent.png")
            assertNull(
                "expected null because 13.8% opaque coverage is below the 50% gate",
                result,
            )
        }

    @Test
    fun `fixture checkerboard-diagonal-png — high-frequency black-white collapses to mid-gray`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The device-sized diagonal black/white checkerboard (1080x2424,
            // the on-device readability stress wallpaper). It is fully opaque,
            // so the coverage gate passes and a classification is ALWAYS
            // produced — the system-wallpaper signal is never reached.
            //
            // The classified SIDE is deliberately not pinned: a 50/50 bimodal
            // pattern sits on the LUMINANCE_THRESHOLD (0.5) knife-edge, so which
            // side it falls is not a stable contract. What IS stable is the
            // median magnitude: the 32x32 bilinear downscale averages each
            // black/white cell to mid-gray, so the WCAG median collapses to
            // ~0.21 (measured) — well below the LIGHT threshold yet far above
            // the near-black AMOLED floor (< 0.05). This is exactly the AUTO
            // limitation the text-outline (0.99.193) exists to cover: neither a
            // single text colour nor a scrim can win here, so readability is
            // solved at the glyph edge instead. Band chosen with margin.
            stubContentResolverFromResource("/wallpaper/checkerboard_diagonal.png")
            val result = luminance.compute("file:///checkerboard_diagonal.png")
            assertNotNull("expected non-null for fully-opaque image", result)
            assertTrue(
                "expected a mid-gray median (~0.21), distinct from near-black " +
                    "AMOLED and below the LIGHT threshold, got $result",
                result!! in 0.05f..0.5f,
            )
        }

    // Note: `BitmapFactory.decodeStream` on malformed bytes is not
    // testable here — Robolectric's shadow returns a stub bitmap
    // for any input, so the JVM-side decode-failure path can't be
    // exercised. The IOException-during-open path below still
    // covers the impl's catch-and-return-null behaviour for one
    // realistic failure mode (file gone / revoked permission).

    @Test
    fun `IOException during open returns null`() = runTest(mainDispatcherRule.testDispatcher) {
        every { contentResolver.openInputStream(any()) } throws IOException("file gone")
        val result = luminance.compute("file:///missing.png")
        assertNull(result)
    }

    @Test
    fun `null input stream returns null`() = runTest(mainDispatcherRule.testDispatcher) {
        every { contentResolver.openInputStream(any()) } returns null
        val result = luminance.compute("file:///opens-but-null.png")
        assertNull(result)
    }

    @Test
    fun `a transient load failure is not cached — a later retry re-decodes and succeeds`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // A load failure must NOT poison the one-entry memo: the file is valid
            // and re-referenced on every AUTO-mode read. First call fails (stream
            // null on both passes), then the file becomes readable and the SAME URI
            // must re-decode rather than serve a cached failure-null.
            val baos = ByteArrayOutputStream()
            solidBitmap(Color.WHITE).compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            var failNext = true
            every { contentResolver.openInputStream(any()) } answers {
                if (failNext) null else ByteArrayInputStream(bytes)
            }

            val first = luminance.compute("file:///flaky.png")
            assertNull("transient load failure yields null", first)

            failNext = false
            val second = luminance.compute("file:///flaky.png")
            assertNotNull("failure must not have been cached — retry must decode", second)
        }

    // ============================================================
    // Memoization (AUDIT-19 F4): the same URI is decoded once, a
    // different URI busts the one-entry cache.
    // ============================================================

    @Test
    fun `same URI computed twice decodes only once — memoized`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val opens = java.util.concurrent.atomic.AtomicInteger(0)
            stubContentResolverCounting(solidBitmap(Color.WHITE), opens)

            val first = luminance.compute("file:///same.png")
            val opensAfterFirst = opens.get()
            val second = luminance.compute("file:///same.png")

            assertEquals(first, second)
            assertTrue("first compute must have actually decoded", opensAfterFirst > 0)
            assertEquals(
                "second compute for the same URI must be a cache hit (no new open/decode)",
                opensAfterFirst,
                opens.get(),
            )
        }

    @Test
    fun `bounded decode makes two passes — bounds then downsampled decode`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // AUDIT-19 F3 wiring guard: loadBitmap opens the stream twice —
            // once for the inJustDecodeBounds pass that sizes the downsample,
            // once for the actual (downsampled) decode. A revert to a single
            // unbounded `decodeStream(input)` drops this to one open. The pure
            // sample-size math is covered separately by LuminanceDownsamplingTest;
            // this pins that the impl actually runs the two-pass path.
            val opens = java.util.concurrent.atomic.AtomicInteger(0)
            stubContentResolverCounting(solidBitmap(Color.WHITE), opens)

            luminance.compute("file:///wp.png")

            assertEquals(2, opens.get())
        }

    @Test
    fun `different URI busts the one-entry cache and re-decodes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val opens = java.util.concurrent.atomic.AtomicInteger(0)
            stubContentResolverCounting(solidBitmap(Color.WHITE), opens)

            luminance.compute("file:///a.png")
            val opensAfterA = opens.get()
            luminance.compute("file:///b.png")

            assertTrue(
                "a different URI must trigger a fresh decode",
                opens.get() > opensAfterA,
            )
        }

    private fun solidBitmap(@androidx.annotation.ColorInt color: Int): Bitmap {
        // 64×64 source — large enough that the impl's 32×32 downscale
        // is a real downscale rather than a no-op.
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    /**
     * Builds a 32×32 ARGB_8888 bitmap with exactly
     * `round(opaqueFraction × 1024)` pixels set to [opaqueColor]
     * and the rest fully transparent. The opaque pixels are placed
     * as a contiguous prefix (top rows first); the spatial pattern
     * doesn't matter for the median, only the count.
     *
     * 32×32 matches the impl's downscale target, so
     * `createScaledBitmap` is a no-op and the pixel counts survive
     * exactly into the impl's filter.
     */
    private fun bitmapWithCoverage(
        @androidx.annotation.ColorInt opaqueColor: Int,
        opaqueFraction: Float,
    ): Bitmap {
        require(opaqueFraction in 0f..1f) { "fraction $opaqueFraction out of [0, 1]" }
        val total = 32 * 32
        val opaqueCount = (total * opaqueFraction).toInt()
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(total) { idx ->
            if (idx < opaqueCount) opaqueColor else Color.TRANSPARENT
        }
        bmp.setPixels(pixels, 0, 32, 0, 0, 32, 32)
        return bmp
    }

    private fun stubContentResolver(bitmap: Bitmap) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        // openInputStream is called once per compute() invocation;
        // return a fresh stream each time so multiple calls in one
        // test don't fail on a closed stream.
        every { contentResolver.openInputStream(any()) } answers { ByteArrayInputStream(bytes) }
    }

    /**
     * Like [stubContentResolver] but increments [opens] on every
     * `openInputStream` call, so a test can assert how many decodes actually
     * happened (a memoized `compute` opens no stream at all). Note one
     * `compute` opens the stream twice — the bounds pass + the downsampled
     * decode — so tests compare deltas, not absolute counts.
     */
    private fun stubContentResolverCounting(
        bitmap: Bitmap,
        opens: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        every { contentResolver.openInputStream(any()) } answers {
            opens.incrementAndGet()
            ByteArrayInputStream(bytes)
        }
    }

    /**
     * Stubs `ContentResolver.openInputStream` to read a real PNG
     * from `src/test/resources/...`. Buffers the resource into a
     * byte array so each `openInputStream` call gets a fresh
     * stream — symmetric to [stubContentResolver] above.
     */
    private fun stubContentResolverFromResource(resourcePath: String) {
        val bytes = javaClass.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("Test resource not found: $resourcePath")
        every { contentResolver.openInputStream(any()) } answers { ByteArrayInputStream(bytes) }
    }
}
