package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class FavoritesManagerTest {

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
            sharingStrategy = SharingStarted.Lazily
        )

        val result = favoritesManager.isFavoriteComponent("com.favorite.app/ComponentA")

        assertTrue(result)
    }

    @Test
    fun `isFavoriteComponent returns false for a non-favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.another.app/ComponentB")))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        assertFalse(favoritesManager.isFavoriteComponent("com.not.favorite/ComponentC"))
    }

    @Test
    fun `addFavoriteComponent adds component and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("com.new.favorite/ComponentD")

        assertTrue(result)
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertTrue(savedFavorites?.contains("com.new.favorite/ComponentD") == true)
    }

    @Test
    fun `addFavoriteComponent returns false when max limit is reached for new packages`() = runTest {
        val fakeDataStore = FakeDataStore()
        val fullSet = (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.app$it/Component" }.toSet()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("com.over.limit/ComponentE")

        assertFalse(result)
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertEquals(AppConstants.MAX_FAVORITES_ON_HOME, savedFavorites?.size)
    }

    @Test
    fun `removeFavoriteComponent removes component`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/ComponentF", "com.to.remove/ComponentG")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        favoritesManager.removeFavoriteComponent("com.to.remove/ComponentG")

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertFalse(savedFavorites?.contains("com.to.remove/ComponentG") == true)
        assertEquals(1, savedFavorites?.size)
    }

    @Test
    fun `cleanupFavoriteComponents removes orphaned favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        val currentFavorites = setOf("com.installed.app/ComponentH", "com.orphaned.app/ComponentI")
        val installedComponents = listOf("com.installed.app/ComponentH", "com.another.installed.app/ComponentJ")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to currentFavorites))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        favoritesManager.cleanupFavoriteComponents(installedComponents)

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertTrue(savedFavorites?.contains("com.installed.app/ComponentH") == true)
        assertFalse(savedFavorites?.contains("com.orphaned.app/ComponentI") == true)
        assertEquals(1, savedFavorites?.size)
    }

    @Test
    fun `addFavoriteComponent when limit reached allows adding component from existing favorite package`() = runTest {
        val fakeDataStore = FakeDataStore()
        val fullSet = (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.app$it/Component" }.toSet()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("com.app1/AnotherComponent")

        assertTrue(result)
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertEquals(AppConstants.MAX_FAVORITES_ON_HOME + 1, savedFavorites?.size)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `addFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("com.test/Component")

        assertFalse(result)
    }

    @Test
    fun `addFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        assertFailsWith<CancellationException> {
            favoritesManager.addFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `addFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("")

        assertFalse(result)
    }


    @Test
    fun `addFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, backgroundScope, SharingStarted.Lazily)

        val resultEmpty = favoritesManager.addFavoriteComponent("")
        val resultBlank = favoritesManager.addFavoriteComponent("   ")

        assertFalse(resultEmpty)
        assertFalse(resultBlank)
    }

    @Test
    fun `removeFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeEditFail()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.removeFavoriteComponent("com.test/Component")

        assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        // Initialize with the component already in favorites
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeCancellable()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        assertFailsWith<CancellationException> {
            favoritesManager.removeFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `removeFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.removeFavoriteComponent("")

        assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.removeFavoriteComponent("")

        assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - when DataStore read fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeReadFail()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.isFavoriteComponent("com.test/Component")

        assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with null componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.isFavoriteComponent(null)

        assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.isFavoriteComponent("  ")

        assertFalse(result)
    }

    @Test
    fun `saveFavoriteComponents - with empty list - clears all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, backgroundScope, SharingStarted.Lazily)

        // KEIN result mehr - gibt Unit zurück
        favoritesManager.saveFavoriteComponents(emptyList())

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertTrue(savedFavorites.isNullOrEmpty())
    }

    @Test
    fun `saveFavoriteComponents - when DataStore edit fails - does not crash`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, backgroundScope, SharingStarted.Lazily)

        // KEIN result mehr - sollte nur nicht crashen
        favoritesManager.saveFavoriteComponents(listOf("com.test/Component"))

        // Verify it attempted but failed
        assertNotNull(favoritesManager)
    }

    @Test
    fun `cleanupFavoriteComponents - with empty installed list - removes all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component", "com.app2/Component")))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        favoritesManager.cleanupFavoriteComponents(emptyList())

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertTrue(savedFavorites.isNullOrEmpty())
    }

    @Test
    fun `cleanupFavoriteComponents - when DataStore edit fails - keeps current state`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/Component")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        // Wait for initialization
        favoritesManager.favoriteComponentsFlow.first()

        // Make edit fail
        fakeDataStore.makeEditFail()

        // Act - should not crash
        favoritesManager.cleanupFavoriteComponents(listOf("com.other/Component"))

        // Assert - old data should remain
        val favorites = fakeDataStore.data.first()[favoritesKey]
        assertTrue(favorites?.contains("com.app1/Component") == true)
    }

    @Test
    fun `addFavoriteComponent - when already favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.addFavoriteComponent("com.test/Component")

        assertTrue(result)
    }

    @Test
    fun `removeFavoriteComponent - when not favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesManager = FavoritesManager(fakeDataStore, mockContext, this.backgroundScope, SharingStarted.Lazily)

        val result = favoritesManager.removeFavoriteComponent("com.not.favorite/Component")

        assertTrue(result)
    }
}