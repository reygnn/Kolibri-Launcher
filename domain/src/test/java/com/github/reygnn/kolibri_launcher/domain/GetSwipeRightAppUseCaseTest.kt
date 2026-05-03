package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeRightAppUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class GetSwipeRightAppUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: SwipeActionsRepository

    private lateinit var useCase: GetSwipeRightAppUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetSwipeRightAppUseCase(repository)
    }

    @Test
    fun `invoke - returns swipeRightAppFlow from repository`() = runTest {
        val expected = "com.right/App"
        every { repository.swipeRightAppFlow } returns flowOf(expected)

        useCase().collect { assertEquals(expected, it) }
        verify { repository.swipeRightAppFlow }
    }
}
