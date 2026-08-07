package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `invoke - returns set from repository snapshot`() = runTest {
        // Pins the use case to the authoritative fresh read, not the hot replay flow.
        val expectedSet = setOf("com.example.app/MainActivity", "com.test.app/HomeActivity")
        coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } returns expectedSet

        val result = useCase()

        assertEquals(expectedSet, result)
    }

    @Test
    fun `invoke - propagates exception from repository snapshot`() = runTest {
        val expectedError = RuntimeException("Database load failed")
        coEvery { favoritesRepository.getFavoriteComponentsSnapshot() } throws expectedError

        val exception = assertFailsWith<RuntimeException> { useCase() }
        assertEquals(expectedError.message, exception.message)
    }
}
