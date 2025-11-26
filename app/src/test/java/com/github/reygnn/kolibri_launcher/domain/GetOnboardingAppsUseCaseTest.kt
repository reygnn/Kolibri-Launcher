package com.github.reygnn.kolibri_launcher.domain

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GetOnboardingAppsUseCaseTest {

    private lateinit var installedAppsRepository: FakeInstalledAppsRepository
    private lateinit var useCase: GetOnboardingAppsUseCase

    private val validApps = listOf(
        AppInfo("App1", "App1", "com.app1", "com.app1.Main"),
        AppInfo("App2", "App2", "com.app2", "com.app2.Main"),
        AppInfo("App3", "App3", "com.app3", "com.app3.Main")
    )

    @Before
    fun setup() {
        installedAppsRepository = FakeInstalledAppsRepository()
        useCase = GetOnboardingAppsUseCase(installedAppsRepository)
    }

    // =========================================================================
    // Erfolgsfall
    // =========================================================================

    @Test
    fun `onboardingAppsFlow emits installed apps`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = validApps

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            val result = awaitItem()
            assertThat(result).hasSize(3)
            assertThat(result).containsExactlyElementsIn(validApps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboardingAppsFlow emits empty list when no apps installed`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = emptyList()

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            val result = awaitItem()
            assertThat(result).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Filterung - Leere PackageName
    // =========================================================================

    @Test
    fun `onboardingAppsFlow filters out apps with blank packageName`() = runTest {
        // Arrange
        val appsWithBlankPackage = listOf(
            AppInfo("Valid", "Valid", "com.valid", "com.valid.Main"),
            AppInfo("Invalid", "Invalid", "", "com.invalid.Main"),  // Leer
            AppInfo("AlsoInvalid", "AlsoInvalid", "   ", "com.alsoinvalid.Main")  // Nur Whitespace
        )
        installedAppsRepository.installedApps = appsWithBlankPackage

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].packageName).isEqualTo("com.valid")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Filterung - Leere DisplayName
    // =========================================================================

    @Test
    fun `onboardingAppsFlow filters out apps with blank displayName`() = runTest {
        // Arrange
        val appsWithBlankName = listOf(
            AppInfo("Valid", "Valid", "com.valid", "com.valid.Main"),
            AppInfo("NoName", "", "com.noname", "com.noname.Main"),  // displayName leer
            AppInfo("Whitespace", "   ", "com.whitespace", "com.whitespace.Main")  // displayName nur Whitespace
        )
        installedAppsRepository.installedApps = appsWithBlankName

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].displayName).isEqualTo("Valid")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Reaktivität
    // =========================================================================

    @Test
    fun `onboardingAppsFlow emits new list when repository updates`() = runTest {
        // Arrange
        installedAppsRepository.installedApps = listOf(validApps[0])

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            assertThat(awaitItem()).hasSize(1)

            // Repository Update
            installedAppsRepository.installedApps = validApps

            assertThat(awaitItem()).hasSize(3)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Kombinierte Filterung
    // =========================================================================

    @Test
    fun `onboardingAppsFlow filters multiple invalid apps`() = runTest {
        // Arrange
        val mixedApps = listOf(
            AppInfo("Valid1", "Valid1", "com.valid1", "com.valid1.Main"),
            AppInfo("NoName", "", "com.noname", "com.noname.Main"),  // displayName leer
            AppInfo("NoPackage", "NoPackage", "", "com.nopackage.Main"),
            AppInfo("Valid2", "Valid2", "com.valid2", "com.valid2.Main"),
            AppInfo("AllBlank", "   ", "   ", "com.allblank.Main")  // beide blank
        )
        installedAppsRepository.installedApps = mixedApps

        // Act & Assert
        useCase.onboardingAppsFlow.test {
            val result = awaitItem()
            assertThat(result).hasSize(2)
            assertThat(result.map { it.packageName }).containsExactly("com.valid1", "com.valid2")
            cancelAndIgnoreRemainingEvents()
        }
    }
}