package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.OrientationSynchronizer
import com.github.reygnn.kolibri_launcher.ui.home.SyncResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrientationSynchronizerTest {

    @get:Rule
    val timberRule = TimberRule()

    @Test
    fun `check returns CorrectionNeeded when states differ`() {
        // GIVEN
        val synchronizer = OrientationSynchronizer { 2 } // System is Landscape

        // WHEN
        val result = synchronizer.check(1) // Internal is Portrait

        // THEN
        assertTrue(result is SyncResult.CorrectionNeeded)
        assertEquals(2, (result as SyncResult.CorrectionNeeded).newOrientation)
    }

    @Test
    fun `check returns UpToDate when states match`() {
        // GIVEN
        val synchronizer = OrientationSynchronizer { 1 }

        // WHEN
        val result = synchronizer.check(1)

        // THEN
        assertTrue(result is SyncResult.UpToDate)
    }
    }