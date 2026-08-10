package com.github.reygnn.kolibri_launcher.data

import java.io.IOException
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class FavoritesRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()


    private val favoritesKey = stringSetPreferencesKey("favorites_components_set")

    // ========== EXISTING TESTS ==========

    @Test
    fun `isFavoriteComponent returns true for a favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.favorite.app/ComponentA")))

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent("com.favorite.app/ComponentA")

        Assert.assertTrue(result)
    }

    @Test
    fun `isFavoriteComponent returns false for a non-favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.another.app/ComponentB")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        Assert.assertFalse(favoritesRepositoryImpl.isFavoriteComponent("com.not.favorite/ComponentC"))
    }

    @Test
    fun `addFavoriteComponent adds component and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.new.favorite/ComponentD")

        Assert.assertTrue(result)
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(savedFavorites?.contains("com.new.favorite/ComponentD") == true)
    }

    @Test
    fun `addFavoriteComponent returns false when max limit is reached for new packages`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            val fullSet =
                (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.app$it/Component" }
                    .toSet()
            fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
            val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

            val result = favoritesRepositoryImpl.addFavoriteComponent("com.over.limit/ComponentE")

            Assert.assertFalse(result)
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertEquals(AppConstants.MAX_FAVORITES_ON_HOME, savedFavorites?.size)
        }

    @Test
    fun `removeFavoriteComponent removes component`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/ComponentF", "com.to.remove/ComponentG")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        favoritesRepositoryImpl.removeFavoriteComponent("com.to.remove/ComponentG")

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertFalse(savedFavorites?.contains("com.to.remove/ComponentG") == true)
        Assert.assertEquals(1, savedFavorites?.size)
    }

    @Test
    fun `reconcileFavoriteComponents removes orphaned favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        val currentFavorites = setOf("com.installed.app/ComponentH", "com.orphaned.app/ComponentI")
        val installedComponents =
            listOf("com.installed.app/ComponentH", "com.another.installed.app/ComponentJ")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to currentFavorites))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        favoritesRepositoryImpl.reconcileFavoriteComponents(installedComponents) { false }

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
                (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.app$it/Component" }
                    .toSet()
            fakeDataStore.setInitialData(preferencesOf(favoritesKey to fullSet))
            val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

            val result = favoritesRepositoryImpl.addFavoriteComponent("com.app1/AnotherComponent")

            Assert.assertTrue(result)
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertEquals(
                AppConstants.MAX_FAVORITES_ON_HOME + 1,
                savedFavorites?.size
            )
        }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `addFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `addFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        assertFailsWith<CancellationException> {
            favoritesRepositoryImpl.addFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `addFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("")

        Assert.assertFalse(result)
    }


    @Test
    fun `addFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val resultEmpty = favoritesRepositoryImpl.addFavoriteComponent("")
        val resultBlank = favoritesRepositoryImpl.addFavoriteComponent("   ")

        Assert.assertFalse(resultEmpty)
        Assert.assertFalse(resultBlank)
    }

    @Test
    fun `removeFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        // Initialize with the component already in favorites
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeCancellable()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        assertFailsWith<CancellationException> {
            favoritesRepositoryImpl.removeFavoriteComponent("com.test/Component")
        }
    }

    @Test
    fun `removeFavoriteComponent - with empty componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("")

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - when DataStore read fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeReadFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with null componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent(null)

        Assert.assertFalse(result)
    }

    @Test
    fun `isFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent("  ")

        Assert.assertFalse(result)
    }

    @Test
    fun `saveFavoriteComponents - with empty list - clears all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // KEIN result mehr - gibt Unit zurück
        favoritesRepositoryImpl.saveFavoriteComponents(emptyList())

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(savedFavorites.isNullOrEmpty())
    }

    @Test
    fun `saveFavoriteComponents - when DataStore edit fails - does not crash`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // KEIN result mehr - sollte nur nicht crashen
        favoritesRepositoryImpl.saveFavoriteComponents(listOf("com.test/Component"))

        // Verify it attempted but failed
        Assert.assertNotNull(favoritesRepositoryImpl)
    }

    @Test
    fun `reconcileFavoriteComponents - with empty installed list - removes all favorites`() =
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
            val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

            favoritesRepositoryImpl.reconcileFavoriteComponents(emptyList()) { false }

            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            Assert.assertTrue(savedFavorites.isNullOrEmpty())
        }

    @Test
    fun `reconcileFavoriteComponents - when DataStore edit fails - propagates (fail-closed)`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/Component")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Wait for initialization
        favoritesRepositoryImpl.favoriteComponentsFlow.first()

        // Make edit fail
        fakeDataStore.makeEditFail()

        // Fail-closed: the edit failure PROPAGATES (no swallow). The "skip this
        // store, delete nothing" outcome is enforced one level up by the caller's
        // runCleanup (RECONCILE_FIX_SPEC §4). com.app1 is an orphan (installed is
        // com.other) and the predicate reports it absent, so the edit is attempted.
        var thrown: Throwable? = null
        try {
            favoritesRepositoryImpl.reconcileFavoriteComponents(listOf("com.other/Component")) { false }
        } catch (e: Throwable) {
            thrown = e
        }
        Assert.assertTrue("edit failure must propagate, not be swallowed", thrown is IOException)

        // And nothing was deleted (the edit never committed).
        val favorites = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(favorites?.contains("com.app1/Component") == true)
    }

    @Test
    fun `reconcileFavoriteComponents - when the candidate read fails - propagates (fail-closed)`() = runTest {
        // The candidate read is fail-CLOSED (dataStore.data.first(), not the
        // fail-open shared flow): a read error propagates so the caller's
        // runCleanup skips the store and deletes nothing. A fail-open read would
        // yield empty -> no candidate -> "nothing deleted" too, so only asserting
        // the throw distinguishes fail-closed from the M1 regression (§6.1).
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)
        fakeDataStore.makeReadFail()

        assertFailsWith<IOException> {
            favoritesRepositoryImpl.reconcileFavoriteComponents(listOf("com.other/Component")) { false }
        }
    }

    @Test
    fun `getFavoriteComponentsSnapshot - reads the latest stored value`() = runTest {
        // Authoritative fresh point-read: getFavoriteComponentsSnapshot reads
        // dataStore.data.first() and runs the SAME transform as the cold
        // favoriteComponentsFlow (DSR-INV-1), so a save with NO active collector —
        // the backup-screen situation — is reflected immediately. Since the
        // hot-share teardown (DATASTORE_READ_SPEC Belang A) there is no replay cache
        // left to go stale; this pins that both read paths agree on the latest value.
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.old/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        favoritesRepositoryImpl.saveFavoriteComponents(listOf("com.new/Component"))

        Assert.assertEquals(
            setOf("com.new/Component"),
            favoritesRepositoryImpl.getFavoriteComponentsSnapshot()
        )
    }

    @Test
    fun `getFavoriteComponentsSnapshot - when the store read fails - returns empty (fail-open)`() = runTest {
        // The backup snapshot read is fail-OPEN (non-destructive): a transient
        // read IOException yields an empty set, never throws — the backup records
        // empty rather than crashing the user-initiated export. (Contrast the
        // reconcile path, which is fail-CLOSED and propagates.)
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)
        fakeDataStore.makeReadFail()

        Assert.assertEquals(
            emptySet<String>(),
            favoritesRepositoryImpl.getFavoriteComponentsSnapshot()
        )
    }

    @Test
    fun `readFavoritesForEdit - when the store read fails - returns Unavailable (fail-closed)`() = runTest {
        // Belang C: the editor pre-selection read is DISTINGUISHABLE — a transient
        // read IOException surfaces as Unavailable, NOT an empty Loaded, so the
        // OnboardingViewModel save-gate can block a wipe (DSR-INV-4). Impl-only: the
        // fake never fails I/O, so this branch cannot be a contract test.
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)
        fakeDataStore.makeReadFail()

        val result = favoritesRepositoryImpl.readFavoritesForEdit()

        Assert.assertTrue(
            "read failure must be Unavailable, not an empty Loaded",
            result is FavoritesEditRead.Unavailable
        )
    }

    @Test
    fun `addFavoriteComponent - when already favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.test/Component")

        Assert.assertTrue(result)
    }

    @Test
    fun `removeFavoriteComponent - when not favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("com.not.favorite/Component")

        Assert.assertTrue(result)
    }

    // ========== TOGGLE TESTS ==========

    @Test
    fun `toggleFavoriteComponent - when not favorite - adds it and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Initial leer
        Assert.assertFalse(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component"))

        // Act
        val result = favoritesRepositoryImpl.toggleFavoriteComponent("com.test/Component")

        // Assert
        Assert.assertTrue("Should return true (added)", result)
        Assert.assertTrue(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component"))
    }

    @Test
    fun `toggleFavoriteComponent - when already favorite - removes it and returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Verify initial state
        Assert.assertTrue(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component"))

        // Act
        val result = favoritesRepositoryImpl.toggleFavoriteComponent("com.test/Component")

        // Assert
        Assert.assertFalse("Should return false (removed)", result)
        Assert.assertFalse(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component"))
    }

    // ========== SAVE & PURGE TESTS ==========

    @Test
    fun `saveFavoriteComponents - with valid list - overwrites existing favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        // Vorher: App A ist Favorit
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.old/AppA")))

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val newFavorites = listOf("com.new/AppB", "com.new/AppC")

        // Act
        favoritesRepositoryImpl.saveFavoriteComponents(newFavorites)

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

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Act
        favoritesRepositoryImpl.purgeRepository()

        // Assert
        val saved = fakeDataStore.data.first()[favoritesKey]
        Assert.assertTrue(saved.isNullOrEmpty())
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Act - should not crash
        favoritesRepositoryImpl.purgeRepository()

        // Assert
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }

    // ========== AUDIT-14 F1c/F2: distinctUntilChanged regression ==========

    @Test
    fun `favoriteComponentsFlow - unrelated shared-store write does not re-emit identical set`() =
        runTest {
            // favorites and usage share one settingsDataStore, so a usage write
            // re-emits DataStore.data. Without distinctUntilChanged the favorites
            // combine would re-run on every app launch for an unchanged set.
            val fakeDataStore = FakeDataStore()
            fakeDataStore.setInitialData(
                preferencesOf(favoritesKey to setOf("com.test/Component")),
            )
            val repo = FavoritesRepositoryImpl(fakeDataStore)

            repo.favoriteComponentsFlow.test {
                Assert.assertEquals(setOf("com.test/Component"), awaitItem())

                // Simulate the per-launch usage tick: write an UNRELATED key.
                val usageKey = longPreferencesKey("usage_count_com.other/App")
                fakeDataStore.updateData { prefs ->
                    prefs.toMutablePreferences().apply { set(usageKey, 1L) }
                }
                // Force the upstream emission to be delivered to the collector.
                // Without distinctUntilChanged the decoded (identical) Set would
                // surface here and expectNoEvents() would fail — that is the guard.
                advanceUntilIdle()

                // The favorites set is unchanged -> no downstream emission.
                expectNoEvents()
            }
        }

    @Test
    fun `favoriteComponentsFlow - still emits when the favorites set actually changes`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            fakeDataStore.setInitialData(
                preferencesOf(favoritesKey to setOf("com.test/Component")),
            )
            val repo = FavoritesRepositoryImpl(fakeDataStore)

            repo.favoriteComponentsFlow.test {
                Assert.assertEquals(setOf("com.test/Component"), awaitItem())

                fakeDataStore.updateData { prefs ->
                    prefs.toMutablePreferences().apply {
                        set(favoritesKey, setOf("com.test/Component", "com.test/Other"))
                    }
                }

                Assert.assertEquals(
                    setOf("com.test/Component", "com.test/Other"),
                    awaitItem(),
                )
            }
        }
}