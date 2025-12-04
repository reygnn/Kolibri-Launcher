package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für FavoritesOrderRepository.
 *
 * Testet die Reihenfolge-Verwaltung von Favoriten.
 */
abstract class FavoritesOrderRepositoryContractTest {

    abstract fun createRepository(): FavoritesOrderRepository

    // Test-Helfer
    private fun createTestApp(name: String, componentName: String): AppInfo {
        val parts = componentName.split("/")
        val packageName = parts[0]
        val className = parts[1]
        return AppInfo(
            originalName = name,
            displayName = name,
            packageName = packageName,
            className = className
        )
    }

    // ===========================================
    // FLOW & SAVE ORDER
    // ===========================================

    @Test
    fun `flow - initially empty`() = runTest {
        val repo = createRepository()

        val result = repo.favoriteComponentsOrderFlow.first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `saveOrder - updates flow`() = runTest {
        val repo = createRepository()
        val order = listOf("com.app1/Main", "com.app2/Main", "com.app3/Main")

        repo.saveOrder(order)

        assertEquals(order, repo.favoriteComponentsOrderFlow.first())
    }

    @Test
    fun `saveOrder - returns true on success`() = runTest {
        val repo = createRepository()

        val result = repo.saveOrder(listOf("com.app/Main"))

        assertTrue(result)
    }

    @Test
    fun `saveOrder - overwrites previous order`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf("com.first/Main"))

        repo.saveOrder(listOf("com.second/Main", "com.third/Main"))

        val result = repo.favoriteComponentsOrderFlow.first()
        assertEquals(2, result.size)
        assertEquals("com.second/Main", result[0])
    }

    @Test
    fun `saveOrder - empty list clears order`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf("com.app/Main"))

        repo.saveOrder(emptyList())

        assertTrue(repo.favoriteComponentsOrderFlow.first().isEmpty())
    }

    // ===========================================
    // SORT FAVORITE COMPONENTS
    // ===========================================

    @Test
    fun `sortFavoriteComponents - empty apps returns empty list`() = runTest {
        val repo = createRepository()

        val result = repo.sortFavoriteComponents(emptyList(), listOf("com.app/Main"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sortFavoriteComponents - empty order returns alphabetically sorted`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Zebra", "com.zebra/Main"),
            createTestApp("Apple", "com.apple/Main"),
            createTestApp("Mango", "com.mango/Main")
        )

        val result = repo.sortFavoriteComponents(apps, emptyList())

        assertEquals("Apple", result[0].displayName)
        assertEquals("Mango", result[1].displayName)
        assertEquals("Zebra", result[2].displayName)
    }

    @Test
    fun `sortFavoriteComponents - respects given order`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Apple", "com.apple/Main"),
            createTestApp("Banana", "com.banana/Main"),
            createTestApp("Cherry", "com.cherry/Main")
        )
        val order = listOf("com.cherry/Main", "com.apple/Main", "com.banana/Main")

        val result = repo.sortFavoriteComponents(apps, order)

        assertEquals("Cherry", result[0].displayName)
        assertEquals("Apple", result[1].displayName)
        assertEquals("Banana", result[2].displayName)
    }

    @Test
    fun `sortFavoriteComponents - unordered apps appended alphabetically`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Zebra", "com.zebra/Main"),
            createTestApp("Apple", "com.apple/Main"),
            createTestApp("Mango", "com.mango/Main"),
            createTestApp("Banana", "com.banana/Main")
        )
        // Nur Apple ist in der Order
        val order = listOf("com.apple/Main")

        val result = repo.sortFavoriteComponents(apps, order)

        // Apple zuerst (aus Order), dann Rest alphabetisch
        assertEquals("Apple", result[0].displayName)
        assertEquals("Banana", result[1].displayName)
        assertEquals("Mango", result[2].displayName)
        assertEquals("Zebra", result[3].displayName)
    }

    @Test
    fun `sortFavoriteComponents - ignores order entries not in apps`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Apple", "com.apple/Main"),
            createTestApp("Banana", "com.banana/Main")
        )
        // Order enthält App die nicht existiert
        val order = listOf("com.nonexistent/Main", "com.banana/Main", "com.apple/Main")

        val result = repo.sortFavoriteComponents(apps, order)

        assertEquals(2, result.size)
        assertEquals("Banana", result[0].displayName)
        assertEquals("Apple", result[1].displayName)
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears order`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf("com.app1/Main", "com.app2/Main"))

        repo.purgeRepository()

        assertTrue(repo.favoriteComponentsOrderFlow.first().isEmpty())
    }
}

/**
 * Verifiziert den Fake
 */
class FakeFavoritesOrderRepositoryContractTest : FavoritesOrderRepositoryContractTest() {
    override fun createRepository(): FavoritesOrderRepository = FakeFavoritesOrderRepository()
}