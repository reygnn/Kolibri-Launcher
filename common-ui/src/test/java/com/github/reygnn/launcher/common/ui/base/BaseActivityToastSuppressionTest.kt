package com.github.reygnn.launcher.common.ui.base

import com.github.reygnn.launcher.core.TimberWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the two-tag design's load-bearing asymmetry (§23) for the shared
 * :common-ui [BaseActivity]: the DEBUG dev-toast is suppressed ONLY for
 * `SILENT_ERROR` (silentError/silentDeath already throw in DEBUG, so a toast is
 * redundant), while `ACRA_REPORT` (reportToAcra crash-infra) and every
 * other/absent tag still surface the dev-toast. Mirrors Kolibri's own
 * BaseActivityToastSuppressionTest so both bases stay aligned.
 */
class BaseActivityToastSuppressionTest {

    @Test
    fun `SILENT_ERROR suppresses the dev-toast`() {
        assertThat(BaseActivity.isDevToastSuppressed(TimberWrapper.SILENT_LOG_TAG)).isTrue()
    }

    @Test
    fun `ACRA_REPORT does NOT suppress the dev-toast`() {
        assertThat(BaseActivity.isDevToastSuppressed(TimberWrapper.ACRA_REPORT_TAG)).isFalse()
    }

    @Test
    fun `an unrelated tag does NOT suppress`() {
        assertThat(BaseActivity.isDevToastSuppressed("SomeOtherClass")).isFalse()
    }

    @Test
    fun `a null tag does NOT suppress`() {
        assertThat(BaseActivity.isDevToastSuppressed(null)).isFalse()
    }

    // --- shouldShowDevToast: DEBUG gate + tag suppression + throttle ---

    private val throttle = BaseActivity.TOAST_THROTTLE_MS

    @Test
    fun `shows a normal-tag dev toast in debug after the throttle window`() {
        assertThat(BaseActivity.shouldShowDevToast(isDebugBuild = true, tag = null, nowMs = throttle, lastToastMs = 0L)).isTrue()
    }

    @Test
    fun `never shows in a release build`() {
        assertThat(BaseActivity.shouldShowDevToast(isDebugBuild = false, tag = null, nowMs = 10 * throttle, lastToastMs = 0L)).isFalse()
    }

    @Test
    fun `does not show for a suppressed SILENT_ERROR tag`() {
        assertThat(BaseActivity.shouldShowDevToast(
                isDebugBuild = true,
                tag = TimberWrapper.SILENT_LOG_TAG,
                nowMs = 10 * throttle,
                lastToastMs = 0L,
            )).isFalse()
    }

    @Test
    fun `throttles a second toast within the window`() {
        assertThat(BaseActivity.shouldShowDevToast(isDebugBuild = true, tag = null, nowMs = throttle - 1, lastToastMs = 0L)).isFalse()
    }

    @Test
    fun `allows a toast exactly at the throttle boundary`() {
        assertThat(BaseActivity.shouldShowDevToast(isDebugBuild = true, tag = null, nowMs = throttle, lastToastMs = 0L)).isTrue()
    }
}
