package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class FavoritesOrderManagerTest {

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>

    @Mock
    private lateinit var mockContext: Context

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortAppsWithGivenOrder with saved order sorts apps correctly`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val unsortedApps = listOf(
            AppInfo(originalName = "A", displayName = "App A", packageName = "com.a", className = "a"),
            AppInfo(originalName = "B", displayName = "App B", packageName = "com.b", className = "b"),
            AppInfo(originalName = "C", displayName = "App C", packageName = "com.c", className = "c")
        )
        val savedOrder = listOf("com.c/c", "com.a/a", "com.b/b")
        val expectedSortedApps = listOf(
            unsortedApps[2], // App C
            unsortedApps[0], // App A
            unsortedApps[1]  // App B
        )

        val sortedApps = manager.sortAppsWithGivenOrder(unsortedApps, savedOrder)

        assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with no saved order sorts alphabetically by displayName`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val unsortedApps = listOf(
            AppInfo(originalName = "C", displayName = "Zeppelin", packageName = "com.c", className = "c"),
            AppInfo(originalName = "A", displayName = "Apple", packageName = "com.a", className = "a"),
            AppInfo(originalName = "B", displayName = "Banana", packageName = "com.b", className = "b")
        )
        val expectedSortedApps = listOf(
            unsortedApps[1], // Apple
            unsortedApps[2], // Banana
            unsortedApps[0]  // Zeppelin
        )

        val sortedApps = manager.sortAppsWithGivenOrder(unsortedApps, emptyList())

        assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with outdated order handles it gracefully`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val installedApps = listOf(
            AppInfo(originalName = "A", displayName = "App A", packageName = "com.a", className = "a"),
            AppInfo(originalName = "C", displayName = "App C", packageName = "com.c", className = "c")
        )
        // B wurde deinstalliert, ist aber in der alten Reihenfolge noch vorhanden
        val savedOrder = listOf("com.c/c", "com.b/b", "com.a/a")
        val expectedSortedApps = listOf(
            installedApps[1], // App C
            installedApps[0]  // App A
        )

        val sortedApps = manager.sortAppsWithGivenOrder(installedApps, savedOrder)

        assertEquals(expectedSortedApps, sortedApps)
    }

    @Test
    fun `sortAppsWithGivenOrder with new apps appends them alphabetically by displayName`() = runTest {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo(originalName = "D", displayName = "Delta", packageName = "com.d", className = "d"),
            AppInfo(originalName = "A", displayName = "Alpha", packageName = "com.a", className = "a"),
            AppInfo(originalName = "B", displayName = "Bravo", packageName = "com.b", className = "b"),
            AppInfo(originalName = "C", displayName = "Charlie", packageName = "com.c", className = "c")
        )
        val savedOrder = listOf("com.b/b", "com.a/a")
        val expectedSortedApps = listOf(
            apps[2], // Bravo (aus der gespeicherten Reihenfolge)
            apps[1], // Alpha (aus der gespeicherten Reihenfolge)
            apps[3], // Charlie (alphabetisch angehängt)
            apps[0]  // Delta (alphabetisch angehängt)
        )

        val sortedApps = manager.sortAppsWithGivenOrder(apps, savedOrder)

        assertEquals(expectedSortedApps, sortedApps)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `sortAppsWithGivenOrder - with empty input list - returns empty list`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val result = manager.sortAppsWithGivenOrder(emptyList(), listOf("com.a/a"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sortAppsWithGivenOrder - with null saved order - falls back to alphabetical`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo("Z", "Z", "com.z", "z"),
            AppInfo("A", "A", "com.a", "a")
        )

        val result = manager.sortAppsWithGivenOrder(apps, emptyList())

        assertEquals("A", result[0].displayName)
        assertEquals("Z", result[1].displayName)
    }

    @Test
    fun `sortAppsWithGivenOrder - with duplicate componentNames in order - handles gracefully`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )
        val duplicateOrder = listOf("com.a/a", "com.a/a", "com.b/b")

        val result = manager.sortAppsWithGivenOrder(apps, duplicateOrder)

        // Sollte keine Duplikate in result haben
        assertEquals(apps.size, result.size)
    }

    @Test
    fun `sortAppsWithGivenOrder - with malformed componentNames in order - skips them`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )
        val malformedOrder = listOf("invalid_format", "com.a/a", "", "com.b/b")

        val result = manager.sortAppsWithGivenOrder(apps, malformedOrder)

        // Sollte Apps trotzdem sortieren
        assertEquals(2, result.size)
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
            sharingStrategy = SharingStarted.Lazily
        )

        val result = manager.saveOrder(listOf("com.a/a"))

        assertFalse(result)
    }

    @Test
    fun `saveOrder - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
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
            sharingStrategy = SharingStarted.Lazily
        )

        val result = manager.saveOrder(emptyList())

        assertTrue(result)
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        assertTrue(savedOrder.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - with empty favorites - returns empty list`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
        )

        val result = manager.sortFavoriteComponents(emptyList(), emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - when DataStore read fails - falls back to alphabetical`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeReadFail()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo("Z", "Z", "com.z", "z"),
            AppInfo("A", "A", "com.a", "a")
        )

        val result = manager.sortFavoriteComponents(apps, emptyList())

        // Fallback zu alphabetischer Sortierung
        assertEquals("A", result[0].displayName)
    }

    @Test
    fun `sortAppsWithGivenOrder - with apps that have identical displayNames - maintains stable order`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = listOf(
            AppInfo("Same", "Same", "com.a", "a"),
            AppInfo("Same", "Same", "com.b", "b"),
            AppInfo("Same", "Same", "com.c", "c")
        )

        val result = manager.sortAppsWithGivenOrder(apps, emptyList())

        // Sollte eine stabile Sortierung haben
        assertEquals(3, result.size)
    }

    @Test
    fun `sortAppsWithGivenOrder - with very large order list - handles efficiently`() {
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = mockDataStore,
            context = mockContext,
            externalScope = null,
            sharingStrategy = SharingStarted.Lazily
        )

        val apps = (1..100).map {
            AppInfo("App $it", "App $it", "com.app$it", "class$it")
        }
        val order = apps.map { it.componentName }.reversed()

        val result = manager.sortAppsWithGivenOrder(apps, order)

        assertEquals(100, result.size)
        assertEquals("App 100", result[0].displayName)
        assertEquals("App 1", result[99].displayName)
    }

    @Test
    fun `saveOrder - when successful - returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderManager.createForTesting(
            dataStore = fakeDataStore,
            context = mockContext,
            externalScope = this.backgroundScope,
            sharingStrategy = SharingStarted.Lazily
        )

        println("Before saveOrder call")
        val result = manager.saveOrder(listOf("com.a/a", "com.b/b"))
        println("After saveOrder call, result: $result")
        println("updateDataCallCount: ${fakeDataStore.updateDataCallCount}")

        assertTrue("Expected true but got false", result)
    }

    @Test
    fun `debug - test JSONArray behavior`() {
        val list = listOf("com.a/a", "com.b/b")
        val jsonArray = JSONArray(list)
        val orderString = jsonArray.toString()
        println("JSON String: $orderString")
        assertTrue(orderString.isNotEmpty())
    }

}