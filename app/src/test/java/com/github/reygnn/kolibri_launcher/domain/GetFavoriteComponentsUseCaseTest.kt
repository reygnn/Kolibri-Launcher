package com.github.reygnn.kolibri_launcher.domain
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class GetFavoriteComponentsUseCaseTest {

    @Mock
    private lateinit var favoritesRepository: FavoritesRepository

    private lateinit var useCase: GetFavoriteComponentsUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = GetFavoriteComponentsUseCase(favoritesRepository)
    }

    @Test
    fun `invoke - returns set from repository flow`() = runTest {
        // Arrange
        val expectedSet = setOf("com.example.app/MainActivity", "com.test.app/HomeActivity")
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flowOf(expectedSet))

        // Act
        val result = useCase()

        // Assert
        assertEquals(expectedSet, result)
    }

    @Test
    fun `invoke - propagates exception from repository`() = runTest {
        // Arrange
        val expectedError = RuntimeException("Database load failed")
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flow {
            throw expectedError
        })

        // Act & Assert
        val exception = assertFailsWith<RuntimeException> {
            useCase()
        }
        assertEquals(expectedError.message, exception.message)
    }
}