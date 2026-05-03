package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class GetFavoriteComponentsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var favoritesRepository: FavoritesRepository

    private lateinit var useCase: GetFavoriteComponentsUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = GetFavoriteComponentsUseCase(favoritesRepository)
    }

    @Test
    fun `invoke - returns set from repository flow`() = runTest {
        val expectedSet = setOf("com.example.app/MainActivity", "com.test.app/HomeActivity")
        every { favoritesRepository.favoriteComponentsFlow } returns flowOf(expectedSet)

        val result = useCase()

        assertEquals(expectedSet, result)
    }

    @Test
    fun `invoke - propagates exception from repository`() = runTest {
        val expectedError = RuntimeException("Database load failed")
        every { favoritesRepository.favoriteComponentsFlow } returns flow { throw expectedError }

        val exception = assertFailsWith<RuntimeException> { useCase() }
        assertEquals(expectedError.message, exception.message)
    }
}
