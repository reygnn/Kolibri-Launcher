package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeActionComponentUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class GetSwipeActionComponentUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private val repository: SwipeActionsRepository = mockk()
    private val useCase = GetSwipeActionComponentUseCase(repository)

    @Test
    fun `invoke delegates to the repository authoritative read for the given slot`() = runTest {
        val component = "com.example.app/.MainActivity"
        coEvery { repository.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns component

        val result = useCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        assertEquals(component, result)
        // Pins that the use case goes through getSwipeActionComponent (the fresh
        // store read), NOT the hot swipe flow — the whole point of the chip fix.
        coVerify { repository.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) }
    }

    @Test
    fun `invoke returns null when the slot is unassigned`() = runTest {
        coEvery { repository.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns null

        assertNull(useCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT))
    }
}
