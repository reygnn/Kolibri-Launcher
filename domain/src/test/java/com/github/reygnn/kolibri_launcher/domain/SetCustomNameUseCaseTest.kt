package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.SetCustomNameUseCase
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
class SetCustomNameUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var repository: CustomNamesRepository

    private lateinit var useCase: SetCustomNameUseCase

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = SetCustomNameUseCase(repository)
    }

    @Test
    fun `invoke - delegates to repository setCustomNameForPackage`() = runTest {
        val packageName = "com.test.app"
        val customName = "My App"
        coEvery { repository.setCustomNameForPackage(packageName, customName) } returns true

        val result = useCase(packageName, customName)

        assertTrue(result)
        coVerify { repository.setCustomNameForPackage(packageName, customName) }
    }
}
