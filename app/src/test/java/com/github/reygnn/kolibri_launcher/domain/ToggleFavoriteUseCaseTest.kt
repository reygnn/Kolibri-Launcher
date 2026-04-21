package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToggleFavoriteUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var favoritesRepository: TestToggleFavoritesRepository
    private lateinit var useCase: ToggleFavoriteUseCase

    private val testApp = AppInfo("TestApp", "TestApp", "com.test", "com.test.Main")
    private val testApp2 = AppInfo("TestApp2", "TestApp2", "com.test2", "com.test2.Main")

    @Before
    fun setup() {
        favoritesRepository = TestToggleFavoritesRepository()
        useCase = ToggleFavoriteUseCase(favoritesRepository)
    }

    // =========================================================================
    // Hinzufügen - unter Limit
    // =========================================================================

    @Test
    fun `adding app under limit returns Success with added message`() = runTest {
        // Arrange: Keine Favoriten, Limit = 5
        favoritesRepository.favorites = emptySet()

        // Act
        val result = useCase(testApp, currentMaxFavorites = 5)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Success::class.java)
        assertThat((result as ToggleFavoriteUseCase.Result.Success).messageResId)
            .isEqualTo(R.string.app_added_to_favorites)
    }

    @Test
    fun `adding app under limit actually adds to favorites`() = runTest {
        // Arrange
        favoritesRepository.favorites = emptySet()

        // Act
        useCase(testApp, currentMaxFavorites = 5)

        // Assert
        assertThat(favoritesRepository.favorites).contains(testApp.componentName)
    }

    // =========================================================================
    // Entfernen
    // =========================================================================

    @Test
    fun `removing app returns Success with removed message`() = runTest {
        // Arrange: App ist bereits Favorit
        favoritesRepository.favorites = setOf(testApp.componentName)

        // Act
        val result = useCase(testApp, currentMaxFavorites = 5)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Success::class.java)
        assertThat((result as ToggleFavoriteUseCase.Result.Success).messageResId)
            .isEqualTo(R.string.app_removed_from_favorites)
    }

    @Test
    fun `removing app actually removes from favorites`() = runTest {
        // Arrange
        favoritesRepository.favorites = setOf(testApp.componentName)

        // Act
        useCase(testApp, currentMaxFavorites = 5)

        // Assert
        assertThat(favoritesRepository.favorites).doesNotContain(testApp.componentName)
    }

    // =========================================================================
    // Limit erreicht
    // =========================================================================

    @Test
    fun `adding app at limit returns Error`() = runTest {
        // Arrange: 3 Favoriten, Limit = 3
        favoritesRepository.favorites = setOf(
            "com.app1/com.app1.Main",
            "com.app2/com.app2.Main",
            "com.app3/com.app3.Main"
        )

        // Act: Versuche neue App hinzuzufügen
        val result = useCase(testApp, currentMaxFavorites = 3)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Error::class.java)
        assertThat((result as ToggleFavoriteUseCase.Result.Error).messageResId)
            .isEqualTo(R.string.favorites_limit_reached)
    }

    @Test
    fun `adding app at limit does not modify favorites`() = runTest {
        // Arrange
        val initialFavorites = setOf(
            "com.app1/com.app1.Main",
            "com.app2/com.app2.Main",
            "com.app3/com.app3.Main"
        )
        favoritesRepository.favorites = initialFavorites

        // Act
        useCase(testApp, currentMaxFavorites = 3)

        // Assert: Favoriten unverändert
        assertThat(favoritesRepository.favorites).isEqualTo(initialFavorites)
    }

    @Test
    fun `removing app at limit still works`() = runTest {
        // Arrange: 3 Favoriten (inkl. testApp), Limit = 3
        favoritesRepository.favorites = setOf(
            testApp.componentName,
            "com.app2/com.app2.Main",
            "com.app3/com.app3.Main"
        )

        // Act: Entferne testApp (sollte funktionieren trotz Limit)
        val result = useCase(testApp, currentMaxFavorites = 3)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Success::class.java)
        assertThat((result as ToggleFavoriteUseCase.Result.Success).messageResId)
            .isEqualTo(R.string.app_removed_from_favorites)
        assertThat(favoritesRepository.favorites).doesNotContain(testApp.componentName)
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    fun `adding app exactly at limit minus one works`() = runTest {
        // Arrange: 2 Favoriten, Limit = 3
        favoritesRepository.favorites = setOf(
            "com.app1/com.app1.Main",
            "com.app2/com.app2.Main"
        )

        // Act
        val result = useCase(testApp, currentMaxFavorites = 3)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Success::class.java)
        assertThat(favoritesRepository.favorites).hasSize(3)
    }

    @Test
    fun `limit of zero prevents all additions`() = runTest {
        // Arrange
        favoritesRepository.favorites = emptySet()

        // Act
        val result = useCase(testApp, currentMaxFavorites = 0)

        // Assert
        assertThat(result).isInstanceOf(ToggleFavoriteUseCase.Result.Error::class.java)
    }

    // =========================================================================
    // Test-Hilfsklasse
    // =========================================================================

    /**
     * Fake das die echte Toggle-Logik implementiert.
     */
    private class TestToggleFavoritesRepository : FavoritesRepository, Purgeable {
        private val flow = MutableStateFlow(setOf<String>())

        var favorites: Set<String>
            get() = flow.value
            set(value) { flow.value = value }

        override val favoriteComponentsFlow = flow

        override suspend fun isFavoriteComponent(componentName: String?): Boolean {
            return componentName in favorites
        }

        override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
            return if (componentName in favorites) {
                // Entfernen → return false
                flow.value = favorites - componentName
                false
            } else {
                // Hinzufügen → return true
                flow.value = favorites + componentName
                true
            }
        }

        override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {}
        override suspend fun addFavoriteComponent(componentName: String) = true
        override suspend fun removeFavoriteComponent(componentName: String) = true
        override suspend fun saveFavoriteComponents(componentNames: List<String>) {
            favorites = componentNames.toSet()
        }

        override suspend fun purgeRepository() {
            favorites = emptySet()
        }
    }
}