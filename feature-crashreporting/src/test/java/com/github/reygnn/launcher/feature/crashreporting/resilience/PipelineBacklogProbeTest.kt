package com.github.reygnn.launcher.feature.crashreporting.resilience

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Rule
import org.junit.Test

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

        assertThat(backlog.approved).isEqualTo(2)
        assertThat(backlog.unapproved).isEqualTo(1)
        assertThat(backlog.oldestMillis).isEqualTo(1_000L)
    }

    @Test
    fun `an empty backlog reports null oldest`() {
        val probe = PipelineBacklogProbe(approvedFiles = { emptyList() }, unapprovedFiles = { emptyList() })

        val backlog = probe.read()

        assertThat(backlog.approved).isEqualTo(0)
        assertThat(backlog.unapproved).isEqualTo(0)
        assertThat(backlog.oldestMillis).isNull()
    }

    @Test
    fun `a read failure reports an empty backlog and does not throw`() {
        val probe = PipelineBacklogProbe(
            approvedFiles = { throw RuntimeException("locator blew up") },
            unapprovedFiles = { emptyList() },
        )

        val backlog = probe.read()

        assertThat(backlog.approved).isEqualTo(0)
        assertThat(backlog.unapproved).isEqualTo(0)
        assertThat(backlog.oldestMillis).isNull()
    }
}
