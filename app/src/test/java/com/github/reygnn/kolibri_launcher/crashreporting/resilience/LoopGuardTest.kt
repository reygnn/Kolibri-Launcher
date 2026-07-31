package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-JVM test for [LoopGuard]. The store is a real temp file and the clock is
 * injected, so the window/count logic and cross-restart persistence are
 * verifiable without a device.
 */
class LoopGuardTest {

    private lateinit var store: File
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        store = File.createTempFile("loopguard-test", ".txt")
    }

    @After
    fun tearDown() {
        store.delete()
    }

    private fun guard(maxKills: Int = 3, windowMs: Long = 60_000L) =
        LoopGuard(store, windowMs = windowMs, maxKills = maxKills, now = { clock })

    @Test
    fun `fresh guard does not suppress`() {
        assertFalse(guard().shouldSuppressKill())
    }

    @Test
    fun `below maxKills within the window does not suppress`() {
        val g = guard(maxKills = 3)
        g.recordKill(); clock += 1_000
        g.recordKill()

        assertFalse(g.shouldSuppressKill())
    }

    @Test
    fun `maxKills within the window suppresses`() {
        val g = guard(maxKills = 3)
        repeat(3) { g.recordKill(); clock += 1_000 }

        assertTrue(g.shouldSuppressKill())
    }

    @Test
    fun `kills older than the window do not suppress`() {
        val g = guard(maxKills = 3, windowMs = 60_000L)
        repeat(3) { g.recordKill() }
        clock += 60_001 // advance past the window

        assertFalse(g.shouldSuppressKill())
    }

    @Test
    fun `cross-restart - a new guard on the same file sees prior kills`() {
        // Each recordKill stands in for a separate process (a fresh guard) that
        // wrote to the shared file, then died.
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill(); clock += 1_000
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill(); clock += 1_000
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill()

        assertTrue(LoopGuard(store, maxKills = 3, now = { clock }).shouldSuppressKill())
    }

    @Test
    fun `only the most recent kills are retained`() {
        val g = guard(maxKills = 3)
        repeat(10) { g.recordKill(); clock += 1_000 }

        // Ring keeps maxKills+1 = 4 lines at most.
        assertTrue(store.readLines().filter { it.isNotBlank() }.size <= 4)
    }
}
