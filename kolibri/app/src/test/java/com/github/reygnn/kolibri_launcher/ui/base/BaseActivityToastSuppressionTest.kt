package com.github.reygnn.kolibri_launcher.ui.base

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two-tag design's load-bearing asymmetry (§23): the DEBUG dev-toast is
 * suppressed ONLY for `SILENT_ERROR` (silentError/silentDeath already throw in
 * DEBUG, so a toast is redundant), while `ACRA_REPORT` (reportToAcra crash-infra)
 * and every other/absent tag still surface the dev-toast. If this ever collapses
 * to "suppress everything reported" the two tags would be pointless — so both
 * branches are pinned here.
 */
class BaseActivityToastSuppressionTest {

    @Test
    fun `SILENT_ERROR suppresses the dev-toast`() {
        assertTrue(BaseActivity.isDevToastSuppressed(TimberWrapper.SILENT_LOG_TAG))
    }

    @Test
    fun `ACRA_REPORT does NOT suppress the dev-toast`() {
        assertFalse(BaseActivity.isDevToastSuppressed(TimberWrapper.ACRA_REPORT_TAG))
    }

    @Test
    fun `an unrelated tag does NOT suppress`() {
        assertFalse(BaseActivity.isDevToastSuppressed("SomeOtherClass"))
    }

    @Test
    fun `a null tag does NOT suppress`() {
        assertFalse(BaseActivity.isDevToastSuppressed(null))
    }
}
