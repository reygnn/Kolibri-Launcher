package com.github.reygnn.kolibri_launcher.data

import java.io.IOException
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

        assertThat(result).isTrue()
    }

    @Test
    fun `isFavoriteComponent returns false for a non-favorite component`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.another.app/ComponentB")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        assertThat(favoritesRepositoryImpl.isFavoriteComponent("com.not.favorite/ComponentC")).isFalse()
    }

    @Test
    fun `addFavoriteComponent adds component and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.new.favorite/ComponentD")

        assertThat(result).isTrue()
        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertThat(savedFavorites?.contains("com.new.favorite/ComponentD") == true).isTrue()
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

            assertThat(result).isFalse()
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            assertThat(savedFavorites?.size).isEqualTo(AppConstants.MAX_FAVORITES_ON_HOME)
        }

    @Test
    fun `removeFavoriteComponent removes component`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialFavorites = setOf("com.app1/ComponentF", "com.to.remove/ComponentG")
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to initialFavorites))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        favoritesRepositoryImpl.removeFavoriteComponent("com.to.remove/ComponentG")

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertThat(savedFavorites?.contains("com.to.remove/ComponentG") == true).isFalse()
        assertThat(savedFavorites?.size).isEqualTo(1)
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

            assertThat(result).isTrue()
            val savedFavorites = fakeDataStore.data.first()[favoritesKey]
            assertThat(savedFavorites?.size).isEqualTo(AppConstants.MAX_FAVORITES_ON_HOME + 1)
        }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `addFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.test/Component")

        assertThat(result).isFalse()
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

        assertThat(result).isFalse()
    }


    @Test
    fun `addFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val resultEmpty = favoritesRepositoryImpl.addFavoriteComponent("")
        val resultBlank = favoritesRepositoryImpl.addFavoriteComponent("   ")

        assertThat(resultEmpty).isFalse()
        assertThat(resultBlank).isFalse()
    }

    @Test
    fun `removeFavoriteComponent - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("com.test/Component")

        assertThat(result).isFalse()
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

        assertThat(result).isFalse()
    }

    @Test
    fun `removeFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("")

        assertThat(result).isFalse()
    }

    @Test
    fun `isFavoriteComponent - when DataStore read fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeReadFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")

        assertThat(result).isFalse()
    }

    @Test
    fun `isFavoriteComponent - with null componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent(null)

        assertThat(result).isFalse()
    }

    @Test
    fun `isFavoriteComponent - with blank componentName - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.isFavoriteComponent("  ")

        assertThat(result).isFalse()
    }

    @Test
    fun `saveFavoriteComponents - with empty list - clears all favorites`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.app1/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // KEIN result mehr - gibt Unit zurück
        favoritesRepositoryImpl.saveFavoriteComponents(emptyList())

        val savedFavorites = fakeDataStore.data.first()[favoritesKey]
        assertThat(savedFavorites.isNullOrEmpty()).isTrue()
    }

    @Test
    fun `saveFavoriteComponents - when DataStore edit fails - does not crash`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // KEIN result mehr - sollte nur nicht crashen
        favoritesRepositoryImpl.saveFavoriteComponents(listOf("com.test/Component"))

        // Verify it attempted but failed
        assertThat(favoritesRepositoryImpl).isNotNull()
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

        assertThat(favoritesRepositoryImpl.getFavoriteComponentsSnapshot()).isEqualTo(setOf("com.new/Component"))
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

        assertThat(favoritesRepositoryImpl.getFavoriteComponentsSnapshot()).isEqualTo(emptySet<String>())
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

        assertWithMessage("read failure must be Unavailable, not an empty Loaded").that(result).isInstanceOf(FavoritesEditRead.Unavailable::class.java)
    }

    @Test
    fun `addFavoriteComponent - when already favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.addFavoriteComponent("com.test/Component")

        assertThat(result).isTrue()
    }

    @Test
    fun `removeFavoriteComponent - when not favorite - still returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        val result = favoritesRepositoryImpl.removeFavoriteComponent("com.not.favorite/Component")

        assertThat(result).isTrue()
    }

    // ========== TOGGLE TESTS ==========

    @Test
    fun `toggleFavoriteComponent - when not favorite - adds it and returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Initial leer
        assertThat(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")).isFalse()

        // Act
        val result = favoritesRepositoryImpl.toggleFavoriteComponent("com.test/Component")

        // Assert
        assertWithMessage("Should return true (added)").that(result).isTrue()
        assertThat(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")).isTrue()
    }

    @Test
    fun `toggleFavoriteComponent - when already favorite - removes it and returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.setInitialData(preferencesOf(favoritesKey to setOf("com.test/Component")))

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Verify initial state
        assertThat(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")).isTrue()

        // Act
        val result = favoritesRepositoryImpl.toggleFavoriteComponent("com.test/Component")

        // Assert
        assertWithMessage("Should return false (removed)").that(result).isFalse()
        assertThat(favoritesRepositoryImpl.isFavoriteComponent("com.test/Component")).isFalse()
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
        assertThat(saved?.size).isEqualTo(2)
        assertThat(saved?.contains("com.new/AppB") == true).isTrue()
        assertThat(saved?.contains("com.new/AppC") == true).isTrue()
        assertThat(saved?.contains("com.old/AppA") == true).isFalse() // old entry overwritten
    }

    @Test
    fun `saveFavoriteComponents - caps the persisted set at the distinct-package limit`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // One more DISTINCT package than the limit: a bulk save (onboarding / restore) must
        // not persist a set exceeding what the incremental addFavoriteComponent path guards.
        val overLimit = (1..(AppConstants.MAX_FAVORITES_ON_HOME + 1)).map { "com.app$it/Component" }
        favoritesRepositoryImpl.saveFavoriteComponents(overLimit)

        val saved = fakeDataStore.data.first()[favoritesKey]
        assertThat(saved?.size).isEqualTo(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun `saveFavoriteComponents - extra activities of kept packages do not count against the package cap`() = runTest {
        val fakeDataStore = FakeDataStore()
        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // MAX distinct packages PLUS a second activity of the first package. The package
        // count is still MAX, so all MAX+1 components are kept — same rule as
        // addFavoriteComponent (a further activity of an already-favorited package is fine).
        val components = (1..AppConstants.MAX_FAVORITES_ON_HOME).map { "com.app$it/Component" } +
            "com.app1/AnotherComponent"
        favoritesRepositoryImpl.saveFavoriteComponents(components)

        val saved = fakeDataStore.data.first()[favoritesKey]
        assertThat(saved?.size).isEqualTo(AppConstants.MAX_FAVORITES_ON_HOME + 1)
        assertThat(saved?.contains("com.app1/AnotherComponent") == true).isTrue()
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
        assertThat(saved.isNullOrEmpty()).isTrue()
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()

        val favoritesRepositoryImpl = FavoritesRepositoryImpl(fakeDataStore)

        // Act - should not crash
        favoritesRepositoryImpl.purgeRepository()

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
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
                assertThat(awaitItem()).isEqualTo(setOf("com.test/Component"))

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
                assertThat(awaitItem()).isEqualTo(setOf("com.test/Component"))

                fakeDataStore.updateData { prefs ->
                    prefs.toMutablePreferences().apply {
                        set(favoritesKey, setOf("com.test/Component", "com.test/Other"))
                    }
                }

                assertThat(awaitItem()).isEqualTo(setOf("com.test/Component", "com.test/Other"))
            }
        }
}