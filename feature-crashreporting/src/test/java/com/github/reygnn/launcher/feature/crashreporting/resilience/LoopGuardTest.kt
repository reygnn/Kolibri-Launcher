package com.github.reygnn.launcher.feature.crashreporting.resilience

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test

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
        assertThat(guard().shouldSuppressKill()).isFalse()
    }

    @Test
    fun `below maxKills within the window does not suppress`() {
        val g = guard(maxKills = 3)
        g.recordKill(); clock += 1_000
        g.recordKill()

        assertThat(g.shouldSuppressKill()).isFalse()
    }

    @Test
    fun `maxKills within the window suppresses`() {
        val g = guard(maxKills = 3)
        repeat(3) { g.recordKill(); clock += 1_000 }

        assertThat(g.shouldSuppressKill()).isTrue()
    }

    @Test
    fun `kills older than the window do not suppress`() {
        val g = guard(maxKills = 3, windowMs = 60_000L)
        repeat(3) { g.recordKill() }
        clock += 60_001 // advance past the window

        assertThat(g.shouldSuppressKill()).isFalse()
    }

    @Test
    fun `cross-restart - a new guard on the same file sees prior kills`() {
        // Each recordKill stands in for a separate process (a fresh guard) that
        // wrote to the shared file, then died.
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill(); clock += 1_000
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill(); clock += 1_000
        LoopGuard(store, maxKills = 3, now = { clock }).recordKill()

        assertThat(LoopGuard(store, maxKills = 3, now = { clock }).shouldSuppressKill()).isTrue()
    }

    @Test
    fun `only the most recent kills are retained`() {
        val g = guard(maxKills = 3)
        repeat(10) { g.recordKill(); clock += 1_000 }

        // Ring keeps maxKills+1 = 4 lines at most.
        assertThat(store.readLines().filter { it.isNotBlank() }.size <= 4).isTrue()
    }

    // ---------- swallow on the kill path (load-bearing: a rethrow kills the
    // watchdog daemon thread). A directory-as-store makes readLines/writeText
    // throw; removing the catch turns these GREEN tests red. ----------

    @Test
    fun `unreadable store reads as no kills so recovery still fires`() {
        val dir = unreadableStore()
        try {
            // read fails -> swallow -> empty -> not suppressed (kill fires), per
            // the guard's "read failure reads as no recent kills" contract.
            assertThat(LoopGuard(dir, maxKills = 1, now = { clock }).shouldSuppressKill()).isFalse()
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `unwritable store does not crash recordKill`() {
        val dir = unreadableStore()
        try {
            // write fails -> swallow -> no throw out of the (would-be daemon) call.
            LoopGuard(dir, maxKills = 3, now = { clock }).recordKill()
        } finally {
            dir.delete()
        }
    }

    /** A directory in place of the store file: java.io read/write both throw on it. */
    private fun unreadableStore(): File =
        File.createTempFile("loopguard-dir", "").apply { delete(); mkdir() }
}
