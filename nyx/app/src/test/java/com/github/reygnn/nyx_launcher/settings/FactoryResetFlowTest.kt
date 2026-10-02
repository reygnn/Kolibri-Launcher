package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.repository.FakeResetRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The settings' factory reset (2b-4c, step 3): S3 message, R2 seeding after every reset. */
class FactoryResetFlowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun a_complete_reset_seeds_after_resetting_and_says_complete() = runTest(mainDispatcherRule.testDispatcher) {
        val steps = mutableListOf<String>()
        val repository = FakeResetRepository(complete = true)

        val message = performFactoryReset(
            reset = { repository.factoryReset().also { steps += "reset" } },
            seedDefaults = { steps += "seed" },
        )

        assertThat(steps).containsExactly("reset", "seed").inOrder()
        assertThat(message).isEqualTo(R.string.factory_reset_done)
    }

    @Test
    fun an_incomplete_reset_still_seeds_and_says_incomplete() = runTest(mainDispatcherRule.testDispatcher) {
        // R2 (amended): seeding after an incomplete reset refills what was emptied; the seed flags
        // keep it away from stores whose purge failed (ResetRepositoryImplTest pins that half).
        var seeded = false

        val message = performFactoryReset(
            reset = { FakeResetRepository(complete = false).factoryReset() },
            seedDefaults = { seeded = true },
        )

        assertThat(seeded).isTrue()
        assertThat(message).isEqualTo(R.string.factory_reset_incomplete)
    }
}
