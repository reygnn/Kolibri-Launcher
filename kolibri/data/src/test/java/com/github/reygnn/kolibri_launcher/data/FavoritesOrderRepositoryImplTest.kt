package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class FavoritesOrderRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    private val dataStore: DataStore<Preferences> = mockk(relaxed = true)


    // ========== getFavoriteComponentsOrderSnapshot (authoritative read for backup) ==========

    @Test
    fun `getFavoriteComponentsOrderSnapshot reads the latest stored order`() = runTest {
        // Authoritative fresh point-read: getFavoriteComponentsOrderSnapshot reads
        // dataStore.data.first() and runs the SAME parseOrderString as the cold
        // favoriteComponentsOrderFlow (DSR-INV-1), so a save with NO active
        // collector — the backup-screen situation — is reflected immediately.
        // Since the hot-share teardown (DATASTORE_READ_SPEC Belang A) there is no
        // replay cache left to go stale; this pins that both read paths agree on
        // the latest store value and share the JSON parsing.
        val orderKey = stringPreferencesKey("favorites_order_components_list_json")
        val store = FakeDataStore()
        store.setInitialData(
            preferencesOf(orderKey to JSONArray(listOf("com.old/Component")).toString())
        )
        val repo = FavoritesOrderRepositoryImpl(dataStore = store)

        repo.saveOrder(listOf("com.new.a/Component", "com.new.b/Component"))

        Assert.assertEquals(
            listOf("com.new.a/Component", "com.new.b/Component"),
            repo.getFavoriteComponentsOrderSnapshot()
        )
    }

    @Test
    fun `getFavoriteComponentsOrderSnapshot - when the store read fails - returns empty (fail-open)`() = runTest {
        // Fail-OPEN (non-destructive): a transient read IOException yields an empty
        // list, never throws — the backup records empty rather than crashing the
        // user-initiated export.
        val orderKey = stringPreferencesKey("favorites_order_components_list_json")
        val store = FakeDataStore()
        store.setInitialData(
            preferencesOf(orderKey to JSONArray(listOf("com.app1/Component")).toString())
        )
        val repo = FavoritesOrderRepositoryImpl(dataStore = store)
        store.makeReadFail()

        Assert.assertEquals(emptyList<String>(), repo.getFavoriteComponentsOrderSnapshot())
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortAppsWithGivenOrder with saved order sorts apps correctly`() {
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
            val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

        val result = manager.sortAppsWithGivenOrder(emptyList(), listOf("com.a/a"))

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `sortAppsWithGivenOrder - with null saved order - falls back to alphabetical`() {
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

        val apps = listOf(
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )
        val malformedOrder = listOf("invalid_format", "com.a/a", "", "com.b/b")

        val result = manager.sortAppsWithGivenOrder(apps, malformedOrder)

        // Sollte Apps trotzdem sortieren
        Assert.assertEquals(2, result.size)
    }

    @Test
    fun `sortAppsWithGivenOrder - duplicate componentName in appsToSort - dedupes to the first`() {
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

        // componentName ("com.a/a") is supposed to be unique across favorites;
        // this pins the impl's behaviour if it ever isn't. The AUDIT-15 F1
        // map-based rewrite collapses a duplicate componentName to the FIRST
        // instance. The pre-refactor find+remove loop would additionally have
        // emitted the second copy in the alphabetical remainder — the app would
        // appear twice. Deduping the malformed double-entry is the intended,
        // safer result, and this test locks it against a silent regression.
        val first = AppInfo("First", "First", "com.a", "a")
        val second = AppInfo("Second", "Second", "com.a", "a") // same componentName com.a/a
        val apps = listOf(first, second)

        val result = manager.sortAppsWithGivenOrder(apps, listOf("com.a/a"))

        Assert.assertEquals(listOf(first), result)
    }

    /*    @Test
    fun `saveOrder - when successful - returns true`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        val result = manager.saveOrder(listOf("com.a/a", "com.b/b"))

        assertTrue(result)
    }*/

    @Test
    fun `saveOrder - when DataStore edit fails - returns false`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        val result = manager.saveOrder(listOf("com.a/a"))

        Assert.assertFalse(result)
    }

    @Test
    fun `saveOrder - when CancellationException - propagates it`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeCancellable()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        assertFailsWith<CancellationException> {
            manager.saveOrder(listOf("com.a/a"))
        }
    }

    @Test
    fun `saveOrder - with empty list - clears saved order`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        val result = manager.saveOrder(emptyList())

        Assert.assertTrue(result)
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertTrue(savedOrder.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - with empty favorites - returns empty list`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        val result = manager.sortFavoriteComponents(emptyList(), emptyList())

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - when DataStore read fails - falls back to alphabetical`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            fakeDataStore.makeReadFail()
            val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = dataStore)

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
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

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

        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

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
    fun `removeComponentFromOrder - when component not in list - returns true and leaves order unchanged`() =
        runTest {
            val fakeDataStore = FakeDataStore()
            val initialOrder = listOf("com.a/a")
            val jsonArray = JSONArray(initialOrder)

            val prefs = preferencesOf(
                stringPreferencesKey("favorites_order_components_list_json") to jsonArray.toString()
            )
            fakeDataStore.setInitialData(prefs)

            val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

            // Act
            val result = manager.removeComponentFromOrder("com.z/z")

            // Assert: idempotent. Since AUDIT-3 #9 the removal is a single
            // atomic edit{} transaction (read-modify-write inside the same
            // transaction), so one write runs even when nothing matches — but
            // the persisted order is left unchanged.
            Assert.assertTrue(result)
            Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
            Assert.assertEquals(initialOrder, manager.favoriteComponentsOrderFlow.first())
        }

    // ========== MISSING LIMIT & PURGE TESTS ==========

    @Test
    fun `saveOrder - enforces size limit - truncates to MAX_ORDER_LIST_SIZE`() = runTest {
        val fakeDataStore = FakeDataStore()
        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        // MAX_ORDER_LIST_SIZE (impl private const) = MAX_FAVORITES_ON_HOME *
        // AVG_COMPONENTS_PER_PACKAGE(6) + SAFETY_BUFFER(2). The input must EXCEED the
        // cap, or saveOrder's take(MAX_ORDER_LIST_SIZE) is a no-op and the test proves
        // nothing — the previous 300-entry version passed trivially on `300 < 500`,
        // never exercising the cap (3002).
        val maxOrderListSize = AppConstants.MAX_FAVORITES_ON_HOME * 6 + 2
        val overLimit = (1..(maxOrderListSize + 8)).map { "com.app$it/Component" }

        manager.saveOrder(overLimit)
        advanceUntilIdle()

        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertEquals(
            "saved list must be capped at MAX_ORDER_LIST_SIZE",
            maxOrderListSize,
            savedOrder.size
        )
        // take() keeps the head: first item survives, last is the one at the cap
        // boundary, everything past MAX_ORDER_LIST_SIZE is dropped.
        Assert.assertEquals("com.app1/Component", savedOrder.first())
        Assert.assertEquals("com.app$maxOrderListSize/Component", savedOrder.last())
    }

    @Test
    fun `purgeRepository - removes order key`() = runTest {
        val fakeDataStore = FakeDataStore()

        val prefs = androidx.datastore.preferences.core.preferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("favorites_order_components_list_json") to "[\"com.a/a\"]"
        )
        fakeDataStore.setInitialData(prefs)

        val manager = FavoritesOrderRepositoryImpl(dataStore = fakeDataStore)

        advanceUntilIdle()

        // Act
        manager.purgeRepository()
        advanceUntilIdle()

        // Assert
        val savedOrder = manager.favoriteComponentsOrderFlow.first()
        Assert.assertTrue("Order list should be empty after purge", savedOrder.isEmpty())
    }

    // ========== AUDIT-14 V2: distinctUntilChanged regression ==========

    @Test
    fun `favoriteComponentsOrderFlow - unrelated shared-store write does not re-emit identical order`() =
        runTest {
            // The order lives in the shared settingsDataStore, so DataStore.data
            // re-emits on EVERY write. distinctUntilChanged must suppress an unrelated
            // write (e.g. the per-launch usage tick) that leaves the order unchanged.
            val orderKey = stringPreferencesKey("favorites_order_components_list_json")
            val store = FakeDataStore()
            store.setInitialData(
                preferencesOf(orderKey to JSONArray(listOf("com.a/A", "com.b/B")).toString()),
            )
            val repo = FavoritesOrderRepositoryImpl(store)

            repo.favoriteComponentsOrderFlow.test {
                Assert.assertEquals(listOf("com.a/A", "com.b/B"), awaitItem())

                val usageKey = longPreferencesKey("usage_count_com.other/App")
                store.updateData { prefs ->
                    prefs.toMutablePreferences().apply { set(usageKey, 1L) }
                }
                advanceUntilIdle()

                expectNoEvents()
            }
        }

    @Test
    fun `favoriteComponentsOrderFlow - still emits when the order actually changes`() = runTest {
        val orderKey = stringPreferencesKey("favorites_order_components_list_json")
        val store = FakeDataStore()
        store.setInitialData(
            preferencesOf(orderKey to JSONArray(listOf("com.a/A", "com.b/B")).toString()),
        )
        val repo = FavoritesOrderRepositoryImpl(store)

        repo.favoriteComponentsOrderFlow.test {
            Assert.assertEquals(listOf("com.a/A", "com.b/B"), awaitItem())

            store.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(orderKey, JSONArray(listOf("com.b/B", "com.a/A")).toString())
                }
            }

            Assert.assertEquals(listOf("com.b/B", "com.a/A"), awaitItem())
        }
    }
}