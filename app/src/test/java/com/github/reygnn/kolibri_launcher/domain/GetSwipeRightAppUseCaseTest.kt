package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeRightAppUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class GetSwipeRightAppUseCaseTest {

    @Mock
    private lateinit var repository: SwipeActionsRepository

    private lateinit var useCase: GetSwipeRightAppUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetSwipeRightAppUseCase(repository)
    }

    @Test
    fun `invoke - returns swipeRightAppFlow from repository`() = runTest {
        val expected = "com.right/App"
        Mockito.`when`(repository.swipeRightAppFlow).thenReturn(flowOf(expected))

        useCase().collect {
            assertEquals(expected, it)
        }
        verify(repository).swipeRightAppFlow
    }
}