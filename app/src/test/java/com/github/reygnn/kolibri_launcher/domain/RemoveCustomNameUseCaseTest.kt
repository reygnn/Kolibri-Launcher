package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.RemoveCustomNameUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class RemoveCustomNameUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CustomNamesRepository

    private lateinit var useCase: RemoveCustomNameUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = RemoveCustomNameUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository removeCustomNameForPackage`() = runTest {
        val packageName = "com.test.app"
        coEvery { repository.removeCustomNameForPackage(packageName) } returns true

        val result = useCase(packageName)

        assertTrue(result)
        coVerify { repository.removeCustomNameForPackage(packageName) }
    }
}
