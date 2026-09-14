package com.github.reygnn.nyx_launcher.data.icon

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric: the caching layer of [IconLoaderImpl] over a fake [IconSource]
 * (no LauncherApps). Verifies the memory cache, key sensitivity, the disk tier
 * and trim/monochrome wiring; concurrency coalescing (ICL-INV-4) and real resolve
 * are androidTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IconLoaderImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeSource : IconSource {
        var calls = 0
        var lastMonochrome: Boolean = false
        override suspend fun load(ref: IconRef, sizePx: Int, monochrome: Boolean): Bitmap {
            calls++
            lastMonochrome = monochrome
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

    @Test
    fun evict_clears_memory_index_and_forces_a_reload() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeSource()
        val loader = IconLoaderImpl(context, mainDispatcherRule.dispatcher, source, FakePreferencesRepository())

        loader.bitmap(ref("com.foo"), 64) // 1st resolve, indexed under com.foo
        loader.evict("com.foo")           // must drop it from memory + index (A1-15)
        loader.bitmap(ref("com.foo"), 64) // re-resolves instead of a memory hit

        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun a_fresh_loader_serves_the_same_key_from_the_disk_cache() = runTest(mainDispatcherRule.dispatcher) {
        // First loader resolves once and writes the composited WEBP to disk.
        val first = FakeSource()
        IconLoaderImpl(context, mainDispatcherRule.dispatcher, first, FakePreferencesRepository())
            .bitmap(ref("com.disk"), 64)
        advanceUntilIdle() // let the best-effort disk write + prune settle
        assertThat(first.calls).isEqualTo(1)

        // A brand-new loader (empty memory, shared cacheDir) must read the disk
        // tier instead of resolving again (ICL disk cache, §4).
        val second = FakeSource()
        IconLoaderImpl(context, mainDispatcherRule.dispatcher, second, FakePreferencesRepository())
            .bitmap(ref("com.disk"), 64)

        assertThat(second.calls).isEqualTo(0)
    }

    @Test
    fun trim_drops_memory_but_the_disk_tier_still_serves_the_reload() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeSource()
            val loader = IconLoaderImpl(context, mainDispatcherRule.dispatcher, source, FakePreferencesRepository())

            val resolved = loader.bitmap(ref("com.trim"), 64)      // memory + disk
            val memoryHit = loader.bitmap(ref("com.trim"), 64)     // same cached instance
            assertThat(memoryHit).isSameInstanceAs(resolved)

            loader.trim(TRIM_MEMORY_COMPLETE)                      // clears memory only (ICL-INV-7)
            advanceUntilIdle()
            val afterTrim = loader.bitmap(ref("com.trim"), 64)     // memory gone ⇒ disk decode

            assertThat(afterTrim).isNotSameInstanceAs(resolved)    // proves memory was dropped
            assertThat(source.calls).isEqualTo(1)                  // proves disk, not source, served it
        }

    @Test
    fun monochrome_preference_makes_the_source_render_themed() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeSource()
            val loader = IconLoaderImpl(
                context,
                mainDispatcherRule.dispatcher,
                source,
                FakePreferencesRepository(monochrome = true),
            )
            advanceUntilIdle() // let the monochrome preference flow land before the request

            loader.bitmap(ref("com.mono"), 64)

            assertThat(source.lastMonochrome).isTrue()
        }

    private companion object {
        // ComponentCallbacks2.TRIM_MEMORY_COMPLETE — the most aggressive level.
        const val TRIM_MEMORY_COMPLETE = 80
    }
}
