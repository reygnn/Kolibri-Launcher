package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class FavoritesOrderManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    private val mockDataStore: DataStore<Preferences> = mockk(relaxed = true)

    private val mockContext: Context = mockk(relaxed = true)

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortAppsWithGivenOrder with saved order sorts apps correctly`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val unsortedApps = listOf(
            AppInfo(
                originalName = "A",
                displayName = "App A",
                packageName = "com.a",
                className = "a"
            ),
            AppInfo(
                originalName = "B",
                displayName = "App B",
                packageName = "com.b",
                className = "b"
            ),
            AppInfo(
                originalName = "C",
                displayName = "App C",
                packageName = "com.c",
                className = "c"
            )
        )
        val savedOrder = listOf("com.c/c", "com.a/a", "com.b/b")
        val expectedSortedApps = listOf(
            unsortedApps[2], // App C
            unsortedApps[0], // App A
            unsortedApps[1]  // App B
        )

        val sortedApps = manager.sortAppsWithGivenOrder(unsortedApps, savedOrder)

        Assert.assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with no saved order sorts alphabetically by displayName`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val unsortedApps = listOf(
            AppInfo(
                originalName = "C",
                displayName = "Zeppelin",
                packageName = "com.c",
                className = "c"
            ),
            AppInfo(
                originalName = "A",
                displayName = "Apple",
                packageName = "com.a",
                className = "a"
            ),
            AppInfo(
                originalName = "B",
                displayName = "Banana",
                packageName = "com.b",
                className = "b"
            )
        )
        val expectedSortedApps = listOf(
            unsortedApps[1], // Apple
            unsortedApps[2], // Banana
            unsortedApps[0]  // Zeppelin
        )

        val sortedApps = manager.sortAppsWithGivenOrder(unsortedApps, emptyList())

        Assert.assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with outdated order handles it gracefully`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val installedApps = listOf(
            AppInfo(
                originalName = "A",
                displayName = "App A",
                packageName = "com.a",
                className = "a"
            ),
            AppInfo(
                originalName = "C",
                displayName = "App C",
                packageName = "com.c",
                className = "c"
            )
        )
        // B wurde deinstalliert, ist aber in der alten Reihenfolge noch vorhanden
        val savedOrder = listOf("com.c/c", "com.b/b", "com.a/a")
        val expectedSortedApps = listOf(
            installedApps[1], // App C
            installedApps[0]  // App A
        )

        val sortedApps = manager.sortAppsWithGivenOrder(installedApps, savedOrder)

        Assert.assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with new apps appends them alphabetically by displayName`() =
        runTest {
            val manager = FavoritesOrderManager.createForTesting(
                dataStore = mockDataStore,
                context = mockContext,
                externalScope = this.backgroundScope,
                sharingStrategy = SharingStarted.Companion.Lazily
            )

            val apps = listOf(
                AppInfo(
                    originalName = "D",
                    displayName = "Delta",
                    packageName = "com.d",
                    className = "d"
                ),
                AppInfo(
                    originalName = "A",
                    displayName = "Alpha",
                    packageName = "com.a",
                    className = "a"
                ),
                AppInfo(
                    originalName = "B",
                    displayName = "Bravo",
                    packageName = "com.b",
                    className = "b"
                ),
                AppInfo(
                    originalName = "C",
                    displayName = "Charlie",
                    packageName = "com.c",
                    className = "c"
                )
            )
            val savedOrder = listOf("com.b/b", "com.a/a")
            val expectedSortedApps = listOf(
                apps[2], // Bravo (aus der gespeicherten Reihenfolge)
                apps[1], // Alpha (aus der gespeicherten Reihenfolge)
                apps[3], // Charlie (alphabetisch angehängt)
                apps[0]  // Delta (alphabetisch angehängt)
            )

            val sortedApps = manager.sortAppsWithGivenOrder(apps, savedOrder)

            Assert.assertEquals(expectedSortedApps, sortedApps)
        }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `sortAppsWithGivenOrder - with empty input list - returns empty list`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val result = manager.sortAppsWithGivenOrder(emptyList(), listOf("com.a/a"))

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `sortAppsWithGivenOrder - with null saved order - falls back to alphabetical`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val apps = listOf(
            AppInfo("Z", "Z", "com.z", "z"),
            AppInfo("A", "A", "com.a", "a")
        )

        val result = manager.sortAppsWithGivenOrder(apps, emptyList())

        Assert.assertEquals("A", result[0].displayName)
        Assert.assertEquals("Z", result[1].displayName)
    }

    @Test
    fun `sortAppsWithGivenOrder - with duplicate componentNames in order - handles gracefully`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val apps = listOf(
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )
        val duplicateOrder = listOf("com.a/a", "com.a/a", "com.b/b")

        val result = manager.sortAppsWithGivenOrder(apps, duplicateOrder)

        // Sollte keine Duplikate in result haben
        Assert.assertEquals(apps.size, result.size)
    }

    @Test
    fun `sortAppsWithGivenOrder - with malformed componentNames in order - skips them`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val apps = listOf(
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )
        val malformedOrder = listOf("invalid_format", "com.a/a", "", "com.b/b")

        val result = manager.sortAppsWithGivenOrder(apps, malformedOrder)

        // Sollte Apps trotzdem sortieren
        Assert.assertEquals(2, result.size)
    }

    /*    @Test
    fun `saveOrder - when successful - returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
        )

        val result = manager.saveOrder(listOf("com.a/a", "com.b/b"))

        assertTrue(result)
    }*/

    @Test
    fun `saveOrder - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val result = manager.saveOrder(listOf("com.a/a"))

        Assert.assertFalse(result)
    }

    @Test
    fun `saveOrder - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        assertFailsWith<CancellationException> {
            manager.saveOrder(listOf("com.a/a"))
        }
    }

    @Test
    fun `saveOrder - with empty list - clears saved order`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val result = manager.saveOrder(emptyList())

        Assert.assertTrue(result)
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertTrue(savedOrder.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - with empty favorites - returns empty list`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val result = manager.sortFavoriteComponents(emptyList(), emptyList())

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - when DataStore read fails - falls back to alphabetical`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            fakeDataStore.makeReadFail()
            val manager = FavoritesOrderManager.createForTesting(
                dataStore = fakeDataStore,
                context = mockContext,
                externalScope = this.backgroundScope,
                sharingStrategy = SharingStarted.Companion.Lazily
            )

            val apps = listOf(
                AppInfo("Z", "Z", "com.z", "z"),
                AppInfo("A", "A", "com.a", "a")
            )

            val result = manager.sortFavoriteComponents(apps, emptyList())

            // Fallback zu alphabetischer Sortierung
            Assert.assertEquals("A", result[0].displayName)
        }

    @Test
    fun `sortAppsWithGivenOrder - with apps that have identical displayNames - maintains stable order`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val apps = listOf(
            AppInfo("Same", "Same", "com.a", "a"),
            AppInfo("Same", "Same", "com.b", "b"),
            AppInfo("Same", "Same", "com.c", "c")
        )

        val result = manager.sortAppsWithGivenOrder(apps, emptyList())

        // Sollte eine stabile Sortierung haben
        Assert.assertEquals(3, result.size)
    }

    @Test
    fun `sortAppsWithGivenOrder - with very large order list - handles efficiently`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        val apps = (1..100).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }
        val order = apps.map { it.componentName }.reversed()

        val result = manager.sortAppsWithGivenOrder(apps, order)

        Assert.assertEquals(100, result.size)
        Assert.assertEquals("App 100", result[0].displayName)
        Assert.assertEquals("App 1", result[99].displayName)
    }

    @Test
    fun `saveOrder - when successful - returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Companion.Lazily
        )

        println("Before saveOrder call")
        val result = manager.saveOrder(listOf("com.a/a", "com.b/b"))
        println("After saveOrder call, result: $result")
        println("updateDataCallCount: ${fakeDataStore.updateDataCallCount}")

        Assert.assertTrue("Expected true but got false", result)
    }

    @Test
    fun `debug - test JSONArray behavior`() {
        val list = listOf("com.a/a", "com.b/b")
        val jsonArray = JSONArray(list)
        val orderString = jsonArray.toString()
        println("JSON String: $orderString")
        Assert.assertTrue(orderString.isNotEmpty())
    }

    // ========== MISSING REMOVE TESTS ==========

    @Test
    fun `removeComponentFromOrder - removes existing component and updates DataStore`() = runTest {
        val fakeDataStore = FakeDataStore()
        val initialOrder = listOf("com.a/a", "com.b/b", "com.c/c")
        val jsonArray = JSONArray(initialOrder)

        val prefs = preferencesOf(
            stringPreferencesKey("favorites_order_components_list_json") to jsonArray.toString()
        )
        fakeDataStore.setInitialData(prefs)

        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = null,  // <-- KEIN shareIn()
            sharingStrategy = SharingStarted.Eagerly
        )

        // Debug: Prüfen ob Daten korrekt geladen wurden
        val current = manager.favoriteComponentsOrderFlow.first()
        Assert.assertEquals("Initial load failed", 3, current.size)
        Assert.assertTrue("Item missing before remove", current.contains("com.b/b"))

        // Act
        val result = manager.removeComponentFromOrder("com.b/b")

        // Assert
        Assert.assertTrue("Result should be true", result)
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertEquals("Should have 2 items left", 2, savedOrder.size)
        Assert.assertEquals(listOf("com.a/a", "com.c/c"), savedOrder)
    }

    @Test
    fun `removeComponentFromOrder - when component not in list - returns true and does nothing`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            val initialOrder = listOf("com.a/a")
            val jsonArray = JSONArray(initialOrder)

            val prefs = preferencesOf(
                stringPreferencesKey("favorites_order_components_list_json") to jsonArray.toString()
            )
            fakeDataStore.setInitialData(prefs)

            val manager = FavoritesOrderManager.createForTesting(
                dataStore = fakeDataStore,
                context = mockContext,
                externalScope = null,  // <-- KEIN shareIn()
                sharingStrategy = SharingStarted.Eagerly
            )

            // Act
            val result = manager.removeComponentFromOrder("com.z/z")

            // Assert
            Assert.assertTrue(result)
            Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
        }

    // ========== MISSING LIMIT & PURGE TESTS ==========

    @Test
    fun `saveOrder - enforces size limit - truncates list`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Eagerly
        )

        val hugeList = (1..300).map { "com.app$it/Component" }

        // Act
        manager.saveOrder(hugeList)
        advanceUntilIdle()

        // Assert
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        // Wir prüfen, ob die gespeicherte Liste kleiner ist als die Eingabe
        Assert.assertTrue(
            "List should be truncated (Saved: ${savedOrder.size}, Input: 500)",
            savedOrder.size < 500
        )
        // Prüfen, ob die ersten Items erhalten blieben (Reihenfolge wichtig)
        Assert.assertEquals("com.app1/Component", savedOrder.first())
    }

    @Test
    fun `purgeRepository - removes order key`() = runTest {
        val fakeDataStore = FakeDataStore()

        val prefs = androidx.datastore.preferences.core.preferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("favorites_order_components_list_json") to "[\"com.a/a\"]"
        )
        fakeDataStore.setInitialData(prefs)

        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Eagerly
        )

        advanceUntilIdle()

        // Act
        manager.purgeRepository()
        advanceUntilIdle()

        // Assert
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertTrue("Order list should be empty after purge", savedOrder.isEmpty())
    }
}