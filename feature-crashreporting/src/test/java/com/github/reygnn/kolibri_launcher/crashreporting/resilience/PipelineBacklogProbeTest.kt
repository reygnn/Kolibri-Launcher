package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Pure-JVM test for [PipelineBacklogProbe] (C.4). The `ReportLocator` file
 * sources are injected via the seam constructor, so the count/oldest logic and
 * the fail-safe (empty backlog, no throw) are verifiable without a real ACRA
 * report directory.
 */
class PipelineBacklogProbeTest {

    @get:Rule
    val timberRule = TimberRule()

    private fun file(lastModified: Long): File = mockk {
        every { lastModified() } returns lastModified
    }

    @Test
    fun `counts both folders and reports the oldest lastModified`() {
        val probe = PipelineBacklogProbe(
            approvedFiles = { listOf(file(3_000), file(1_000)) },
            unapprovedFiles = { listOf(file(2_000)) },
        )

        val backlog = probe.read()

        assertEquals(2, backlog.approved)
        assertEquals(1, backlog.unapproved)
        assertEquals(1_000L, backlog.oldestMillis)
    }

    @Test
    fun `an empty backlog reports null oldest`() {
        val probe = PipelineBacklogProbe(approvedFiles = { emptyList() }, unapprovedFiles = { emptyList() })

        val backlog = probe.read()

        assertEquals(0, backlog.approved)
        assertEquals(0, backlog.unapproved)
        assertNull(backlog.oldestMillis)
    }

    @Test
    fun `a read failure reports an empty backlog and does not throw`() {
        val probe = PipelineBacklogProbe(
            approvedFiles = { throw RuntimeException("locator blew up") },
            unapprovedFiles = { emptyList() },
        )

        val backlog = probe.read()

        assertEquals(0, backlog.approved)
        assertEquals(0, backlog.unapproved)
        assertNull(backlog.oldestMillis)
    }
}
