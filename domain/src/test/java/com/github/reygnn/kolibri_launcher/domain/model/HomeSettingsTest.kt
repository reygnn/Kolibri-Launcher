package com.github.reygnn.kolibri_launcher.domain.model

import com.github.reygnn.kolibri_launcher.core.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSettingsTest {

    /**
     * Regression for AUDIT-3 #6: the bare `HomeSettings()` default must not
     * drift from `AppConstants.DEFAULT_SORT_ORDER`. The default seeds the
     * cold-start StateFlow in AppManagementDelegate; if it disagrees with the
     * real default, every consumer of the derived sortOrder LiveData reports
     * the wrong order until the first settings emission arrives.
     */
    @Test
    fun `default sortOrder matches the project-wide default constant`() {
        assertEquals(AppConstants.DEFAULT_SORT_ORDER, HomeSettings().sortOrder)
    }
}
