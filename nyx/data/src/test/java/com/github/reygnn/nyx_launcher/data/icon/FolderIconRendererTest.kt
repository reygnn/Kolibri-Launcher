package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
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

/** Robolectric: the folder preview is composed once and then served from cache. */
@RunWith(RobolectricTestRunner::class)
class FolderIconRendererTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class CountingIconLoader : IconLoader {
        var calls = 0
        override suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap {
            calls++
            return Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
        override fun evict(pkg: String) = Unit
        override fun trim(level: Int) = Unit
    }

    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private val members = listOf(ck("pa"), ck("pb"))

    @Test
    fun second_render_of_same_folder_is_cached() = runTest(mainDispatcherRule.dispatcher) {
        val loader = CountingIconLoader()
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher, FakePreferencesRepository())

        renderer.render(members, 96)
        val afterFirst = loader.calls // two members composed
        renderer.render(members, 96)

        assertThat(afterFirst).isEqualTo(2)
        assertThat(loader.calls).isEqualTo(2) // no extra member loads → served from cache
    }

    @Test
    fun clear_forces_a_recompose() = runTest(mainDispatcherRule.dispatcher) {
        val loader = CountingIconLoader()
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher, FakePreferencesRepository())

        renderer.render(members, 96)
        renderer.clear()
        renderer.render(members, 96)

        assertThat(loader.calls).isEqualTo(4) // recomposed after clear
    }
}
