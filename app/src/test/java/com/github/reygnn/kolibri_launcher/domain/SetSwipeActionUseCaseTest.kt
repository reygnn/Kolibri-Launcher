package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class SetSwipeActionUseCaseTest {

    @Mock
    private lateinit var repository: SwipeActionsRepository

    private lateinit var useCase: SetSwipeActionUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = SetSwipeActionUseCase(repository)
    }

    @Test
    fun `invoke - calls setSwipeAction on repository`() = runTest {
        val slot = SwipeSlot.SWIPE_FROM_LEFT
        val component = "com.test/App"

        useCase(slot, component)

        verify(repository).setSwipeAction(slot, component)
    }
}