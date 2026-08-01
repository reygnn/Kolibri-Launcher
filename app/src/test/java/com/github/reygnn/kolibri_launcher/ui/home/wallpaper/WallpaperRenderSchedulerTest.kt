package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [WallpaperRenderScheduler]. Pins the latest-wins
 * invariant that used to be two untested inline lines in
 * `HomeFragment.updateWallpaper`: a newer render must cancel the in-flight
 * render of the previous state, so a slower decode of an older wallpaper can
 * never land on top of a newer one.
 *
 * Coroutines run on the test's [runTest]-owned scope (a StandardTest
 * dispatcher that does not auto-advance), so [runCurrent] / [advanceUntilIdle]
 * drive execution deterministically — the render is started, suspended, and
 * cancelled at exactly the points the test dictates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperRenderSchedulerTest {

    @Test
    fun `render runs the block`() = runTest {
        val scheduler = WallpaperRenderScheduler()
        val landed = mutableListOf<String>()

        scheduler.render(this) { landed += "done" }
        advanceUntilIdle()

        assertEquals(listOf("done"), landed)
    }

    @Test
    fun `a newer render cancels the in-flight older one so only the newer result lands`() = runTest {
        val scheduler = WallpaperRenderScheduler()
        val landed = mutableListOf<String>()
        var oldStarted = false

        // Old render starts and suspends at its (slow) decode.
        scheduler.render(this) {
            oldStarted = true
            delay(1_000) // simulates a slow decode of the previous wallpaper
            landed += "old"
        }
        runCurrent()
        assertTrue("old render must actually be in flight before it is superseded", oldStarted)

        // Newer state arrives while the old render is still suspended.
        scheduler.render(this) {
            delay(10)
            landed += "new"
        }
        advanceUntilIdle()

        // The stale old decode was cancelled mid-flight; only the newer one landed.
        assertEquals(listOf("new"), landed)
    }

    @Test
    fun `cancel stops the in-flight render`() = runTest {
        val scheduler = WallpaperRenderScheduler()
        val landed = mutableListOf<String>()

        scheduler.render(this) {
            delay(1_000)
            landed += "x"
        }
        runCurrent() // let it start and suspend
        scheduler.cancel()
        advanceUntilIdle()

        assertTrue("cancelled render must not land its result", landed.isEmpty())
    }

    @Test
    fun `render after cancel still works`() = runTest {
        val scheduler = WallpaperRenderScheduler()
        val landed = mutableListOf<String>()

        scheduler.render(this) { delay(1_000); landed += "gone" }
        runCurrent()
        scheduler.cancel()

        scheduler.render(this) { landed += "fresh" }
        advanceUntilIdle()

        assertEquals(listOf("fresh"), landed)
    }
}
