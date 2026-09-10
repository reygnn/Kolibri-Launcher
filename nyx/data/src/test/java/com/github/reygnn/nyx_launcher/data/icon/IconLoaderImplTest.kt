package com.github.reygnn.nyx_launcher.data.icon

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric: the caching layer of [IconLoaderImpl] over a fake [IconSource]
 * (no LauncherApps). Verifies the memory cache and key sensitivity; concurrency
 * coalescing (ICL-INV-4) and real resolve are androidTest.
 */
@RunWith(RobolectricTestRunner::class)
class IconLoaderImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeSource : IconSource {
        var calls = 0
        override suspend fun load(ref: IconRef, sizePx: Int, monochrome: Boolean): Bitmap {
            calls++
            return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        }
    }

    private fun ref(pkg: String) = IconRef.System(ComponentKey(pkg, "$pkg.Main"))

    @Test
    fun second_request_for_same_icon_hits_memory() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeSource()
        val loader = IconLoaderImpl(context, mainDispatcherRule.dispatcher, source, FakePreferencesRepository())

        loader.bitmap(ref("com.foo"), 64)
        loader.bitmap(ref("com.foo"), 64)

        assertThat(source.calls).isEqualTo(1) // second came from memory
    }

    @Test
    fun different_size_is_a_different_key_and_reloads() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeSource()
        val loader = IconLoaderImpl(context, mainDispatcherRule.dispatcher, source, FakePreferencesRepository())

        loader.bitmap(ref("com.foo"), 64)
        loader.bitmap(ref("com.foo"), 128)

        assertThat(source.calls).isEqualTo(2)
    }
}
