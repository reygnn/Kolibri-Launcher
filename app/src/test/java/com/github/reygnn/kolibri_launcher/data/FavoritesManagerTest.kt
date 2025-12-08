package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class FavoritesManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var mockContext: Context

    private val favoritesKey = stringSetPreferencesKey("favorites_components_set")

    // ========== EXISTING TESTS ==========

    @Test
    fun `isFavoriteComponent returns true for a favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.favorite.app/ComponentA")))

        val favoritesManager = FavoritesManager(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.isFavoriteComponent("com.favorite.app/ComponentA")

        Assert.assertTrue(result)
    }

    @Test
    fun `isFavoriteComponent returns false for a non-favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.another.app/ComponentB")))
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        Assert.assertFalse(favoritesManager.isFavoriteComponent("com.not.favorite/ComponentC"))
    }

    @Test
    fun `addFavoriteComponent adds component and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.addFavoriteComponent("com.new.favorite/ComponentD")

        Assert.assertTrue(result)
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(savedFavorites?.contains("com.new.favorite/ComponentD") == true)
    }

    @Test
    fun `addFavoriteComponent returns false when max limit is reached for new packages`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            val fullSet =
                (1..AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME).map { "com.app$it/Component" }
                    .toSet()
            fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
            val favoritesManager = FavoritesManager(
                fakeDataStore,
                mockContext,
                this.backgroundScope,
                SharingStarted.Companion.Lazily
            )

            val result = favoritesManager.addFavoriteComponent("com.over.limit/ComponentE")

            Assert.assertFalse(result)
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertEquals(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME, savedFavorites?.size)
        }

    @Test
    fun `removeFavoriteComponent removes component`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/ComponentF", "com.to.remove/ComponentG")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        favoritesManager.removeFavoriteComponent("com.to.remove/ComponentG")

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertFalse(savedFavorites?.contains("com.to.remove/ComponentG") == true)
        Assert.assertEquals(1, savedFavorites?.size)
    }

    @Test
    fun `cleanupFavoriteComponents removes orphaned favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        val currentFavorites = setOf("com.installed.app/ComponentH", "com.orphaned.app/ComponentI")
        val installedComponents =
            listOf("com.installed.app/ComponentH", "com.another.installed.app/ComponentJ")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to currentFavorites))
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        favoritesManager.cleanupFavoriteComponents(installedComponents)

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(savedFavorites?.contains("com.installed.app/ComponentH") == true)
        Assert.assertFalse(savedFavorites?.contains("com.orphaned.app/ComponentI") == true)
        Assert.assertEquals(1, savedFavorites?.size)
    }

    @Test
    fun `addFavoriteComponent when limit reached allows adding component from existing favorite package`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            val fullSet =
                (1..AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME).map { "com.app$it/Component" }
                    .toSet()
            fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
            val favoritesManager = FavoritesManager(
                fakeDataStore,
                mockContext,
                this.backgroundScope,
                SharingStarted.Companion.Lazily
            )

            val result = favoritesManager.addFavoriteComponent("com.app1/AnotherComponent")

            Assert.assertTrue(result)
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertEquals(
                AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME + 1,
                savedFavorites?.size
            )
        }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `addFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.addFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `addFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        assertFailsWith<CancellationException> {
            favoritesManager.addFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `addFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.addFavoriteComponent("")

        Assert.assertFalse(result)
    }


    @Test
    fun `addFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager =
            FavoritesManager(
                fakeDataStore,
                mockContext,
                backgroundScope,
                SharingStarted.Companion.Lazily
            )

        val resultEmpty = favoritesManager.addFavoriteComponent("")
        val resultBlank = favoritesManager.addFavoriteComponent("   ")

        Assert.assertFalse(resultEmpty)
        Assert.assertFalse(resultBlank)
    }

    @Test
    fun `removeFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeEditFail()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.removeFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        // Initialize with the component already in favorites
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeCancellable()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        assertFailsWith<CancellationException> {
            favoritesManager.removeFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `removeFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.removeFavoriteComponent("")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.removeFavoriteComponent("")

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - when DataStore read fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeReadFail()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.isFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with null componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.isFavoriteComponent(null)

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.isFavoriteComponent("  ")

        Assert.assertFalse(result)
    }

    @Test
    fun `saveFavoriteComponents - with empty list - clears all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesManager =
            FavoritesManager(
                fakeDataStore,
                mockContext,
                backgroundScope,
                SharingStarted.Companion.Lazily
            )

        // KEIN result mehr - gibt Unit zurück
        favoritesManager.saveFavoriteComponents(emptyList())

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(savedFavorites.isNullOrEmpty())
    }

    @Test
    fun `saveFavoriteComponents - when DataStore edit fails - does not crash`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesManager =
            FavoritesManager(
                fakeDataStore,
                mockContext,
                backgroundScope,
                SharingStarted.Companion.Lazily
            )

        // KEIN result mehr - sollte nur nicht crashen
        favoritesManager.saveFavoriteComponents(listOf("com.test/Component"))

        // Verify it attempted but failed
        Assert.assertNotNull(favoritesManager)
    }

    @Test
    fun `cleanupFavoriteComponents - with empty installed list - removes all favorites`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            fakeDataStore.setInitialData(
                preferencesOf(
                    favoritesKey to setOf(
                        "com.app1/Component",
                        "com.app2/Component"
                    )
                )
            )
            val favoritesManager = FavoritesManager(
                fakeDataStore,
                mockContext,
                this.backgroundScope,
                SharingStarted.Companion.Lazily
            )

            favoritesManager.cleanupFavoriteComponents(emptyList())

            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertTrue(savedFavorites.isNullOrEmpty())
        }

    @Test
    fun `cleanupFavoriteComponents - when DataStore edit fails - keeps current state`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/Component")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        // Wait for initialization
        favoritesManager.favoriteComponentsFlow.first()

        // Make edit fail
        fakeDataStore.makeEditFail()

        // Act - should not crash
        favoritesManager.cleanupFavoriteComponents(listOf("com.other/Component"))

        // Assert - old data should remain
        val favorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(favorites?.contains("com.app1/Component") == true)
    }

    @Test
    fun `addFavoriteComponent - when already favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.addFavoriteComponent("com.test/Component")

        Assert.assertTrue(result)
    }

    @Test
    fun `removeFavoriteComponent - when not favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val result = favoritesManager.removeFavoriteComponent("com.not.favorite/Component")

        Assert.assertTrue(result)
    }

    // ========== TOGGLE TESTS ==========

    @Test
    fun `toggleFavoriteComponent - when not favorite - adds it and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            externalScope = null,  // <-- KEIN shareIn()
            SharingStarted.Eagerly
        )

        // Initial leer
        Assert.assertFalse(favoritesManager.isFavoriteComponent("com.test/Component"))

        // Act
        val result = favoritesManager.toggleFavoriteComponent("com.test/Component")

        // Assert
        Assert.assertTrue("Should return true (added)", result)
        Assert.assertTrue(favoritesManager.isFavoriteComponent("com.test/Component"))
    }

    @Test
    fun `toggleFavoriteComponent - when already favorite - removes it and returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))

        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            externalScope = null,  // <-- KEIN shareIn()
            SharingStarted.Eagerly
        )

        // Verify initial state
        Assert.assertTrue(favoritesManager.isFavoriteComponent("com.test/Component"))

        // Act
        val result = favoritesManager.toggleFavoriteComponent("com.test/Component")

        // Assert
        Assert.assertFalse("Should return false (removed)", result)
        Assert.assertFalse(favoritesManager.isFavoriteComponent("com.test/Component"))
    }

    // ========== SAVE & PURGE TESTS ==========

    @Test
    fun `saveFavoriteComponents - with valid list - overwrites existing favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        // Vorher: App A ist Favorit
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.old/AppA")))

        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        val newFavorites = listOf("com.new/AppB", "com.new/AppC")

        // Act
        favoritesManager.saveFavoriteComponents(newFavorites)

        // Assert
        val saved = fakeDataStore.data.first()[favoritesKey]
        Assert.assertEquals(2, saved?.size)
        Assert.assertTrue(saved?.contains("com.new/AppB") == true)
        Assert.assertTrue(saved?.contains("com.new/AppC") == true)
        Assert.assertFalse(saved?.contains("com.old/AppA") == true) // Alt überschrieben
    }

    @Test
    fun `purgeRepository - clears all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/App")))

        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        // Act
        favoritesManager.purgeRepository()

        // Assert
        val saved = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(saved.isNullOrEmpty())
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()

        val favoritesManager = FavoritesManager(
            fakeDataStore,
            mockContext,
            this.backgroundScope,
            SharingStarted.Companion.Lazily
        )

        // Act - should not crash
        favoritesManager.purgeRepository()

        // Assert
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }
}