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

    private fun solidBitmap(@androidx.annotation.ColorInt color: Int): Bitmap {
        // 64×64 source — large enough that the impl's 32×32 downscale
        // is a real downscale rather than a no-op.
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
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
}
