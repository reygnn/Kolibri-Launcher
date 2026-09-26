package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        // Members whose load should fail this call (a transient icon-load failure). Mutable so a
        // test can clear it to model the failure clearing up on retry.
        var failFor: Set<ComponentKey> = emptySet()
        val styleFlow = MutableStateFlow(IconStyle.COLOR)
        override val currentStyle: StateFlow<IconStyle> = styleFlow
        override suspend fun bitmap(ref: IconRef, sizePx: Int): Bitmap {
            calls++
            val key = (ref as? IconRef.System)?.key
            // A transient load failure surfaces as an exception (compose() runCatching's it).
            if (key != null && key in failFor) error("transient load failure for $key")
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
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher)

        renderer.render(members, 96)
        val afterFirst = loader.calls // two members composed
        renderer.render(members, 96)

        assertThat(afterFirst).isEqualTo(2)
        assertThat(loader.calls).isEqualTo(2) // no extra member loads → served from cache
    }

    @Test
    fun clear_forces_a_recompose() = runTest(mainDispatcherRule.dispatcher) {
        val loader = CountingIconLoader()
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher)

        renderer.render(members, 96)
        renderer.clear()
        renderer.render(members, 96)

        assertThat(loader.calls).isEqualTo(4) // recomposed after clear
    }

    @Test
    fun an_incomplete_composite_is_not_cached_and_the_next_bind_retries() = runTest(mainDispatcherRule.dispatcher) {
        // F11: one member fails to load transiently → the composite is incomplete and must NOT
        // be cached (else a blank quadrant sticks until an unrelated invalidation).
        val loader = CountingIconLoader().apply { failFor = setOf(ck("pb")) }
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher)

        renderer.render(members, 96)
        val afterIncomplete = loader.calls // pa ok + pb failed = 2

        loader.failFor = emptySet() // the failure clears up
        renderer.render(members, 96) // not cached → recompose, now complete
        assertThat(loader.calls).isEqualTo(afterIncomplete + 2)

        renderer.render(members, 96) // complete composite is now cached
        assertThat(loader.calls).isEqualTo(afterIncomplete + 2) // no extra member loads
    }

    @Test
    fun a_style_change_recomposes_under_the_new_key() = runTest(mainDispatcherRule.dispatcher) {
        // F12: the composite cache is keyed by IconLoader.currentStyle, so flipping the style
        // authority is a cache miss (a mixed-style composite can't be served).
        val loader = CountingIconLoader()
        val renderer = FolderIconRenderer(loader, mainDispatcherRule.dispatcher)

        renderer.render(members, 96)
        val afterFirst = loader.calls // 2
        renderer.render(members, 96)
        assertThat(loader.calls).isEqualTo(afterFirst) // served from cache under COLOR

        loader.styleFlow.value = IconStyle.MONOCHROME
        renderer.render(members, 96) // new key → miss → recompose
        assertThat(loader.calls).isEqualTo(afterFirst + 2)
    }
}
