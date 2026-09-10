package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
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
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `invoke - returns Loaded from repository readFavoritesForEdit`() = runTest {
        // Pins the use case to the distinguishable editor read (Belang C), not the
        // fail-open snapshot or the display flow.
        val expected = FavoritesEditRead.Loaded(
            setOf("com.example.app/MainActivity", "com.test.app/HomeActivity")
        )
        coEvery { favoritesRepository.readFavoritesForEdit() } returns expected

        assertEquals(expected, useCase())
    }

    @Test
    fun `invoke - returns Unavailable when the repository read fails`() = runTest {
        // A read failure must stay distinguishable — the ViewModel save-gate depends
        // on it to block a wipe (DSR-INV-4).
        coEvery { favoritesRepository.readFavoritesForEdit() } returns
            FavoritesEditRead.Unavailable(IOException("read failed"))

        assertTrue(useCase() is FavoritesEditRead.Unavailable)
    }

    @Test
    fun `invoke - propagates a non-IO exception from the repository`() = runTest {
        // readFavoritesForEdit maps IOException to Unavailable but rethrows everything
        // else; the use case propagates a genuine programmer error unchanged.
        val expectedError = RuntimeException("programmer error")
        coEvery { favoritesRepository.readFavoritesForEdit() } throws expectedError

        val exception = assertFailsWith<RuntimeException> { useCase() }
        assertEquals(expectedError.message, exception.message)
    }
}
