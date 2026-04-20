package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class SetSwipeActionUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxUnitFun = true)
    private lateinit var repository: SwipeActionsRepository

    private lateinit var useCase: SetSwipeActionUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetSwipeActionUseCase(repository)
    }

    @Test
    fun `invoke - calls setSwipeAction on repository`() = runTest {
        val slot = SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT
        val component = "com.test/App"

        useCase(slot, component)

        coVerify { repository.setSwipeAction(slot, component) }
    }
}
