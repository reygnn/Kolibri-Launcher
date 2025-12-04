package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für CustomNamesRepository.
 *
 * Dieses Repository ist event-basiert (nicht Flow-basiert) -
 * siehe KDoc in CustomNamesManager für die Architektur-Begründung.
 */
abstract class CustomNamesRepositoryContractTest {

    abstract fun createRepository(): CustomNamesRepository

    // ===========================================
    // GET DISPLAY NAME
    // ===========================================

    @Test
    fun `getDisplayName - returns original name when no custom name set`() = runTest {
        val repo = createRepository()

        val result = repo.getDisplayNameForPackage("com.example", "Original")

        assertEquals("Original", result)
    }

    @Test
    fun `getDisplayName - returns custom name when set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        val result = repo.getDisplayNameForPackage("com.example", "Original")

        assertEquals("Custom", result)
    }

    // ===========================================
    // SET CUSTOM NAME
    // ===========================================

    @Test
    fun `setCustomName - returns true on success`() = runTest {
        val repo = createRepository()

        val result = repo.setCustomNameForPackage("com.example", "MyApp")

        assertTrue(result)
    }

    @Test
    fun `setCustomName - name is retrievable after set`() = runTest {
        val repo = createRepository()

        repo.setCustomNameForPackage("com.example", "MyApp")

        assertEquals("MyApp", repo.getDisplayNameForPackage("com.example", "Original"))
        assertTrue(repo.hasCustomNameForPackage("com.example"))
    }

    @Test
    fun `setCustomName - overwrites existing name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "First")

        repo.setCustomNameForPackage("com.example", "Second")

        assertEquals("Second", repo.getDisplayNameForPackage("com.example", "Original"))
    }

    @Test
    fun `setCustomName - trims whitespace`() = runTest {
        val repo = createRepository()

        repo.setCustomNameForPackage("com.example", "  Trimmed  ")

        assertEquals("Trimmed", repo.getDisplayNameForPackage("com.example", "Original"))
    }

    @Test
    fun `setCustomName - blank name removes existing custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        repo.setCustomNameForPackage("com.example", "   ")

        assertFalse(repo.hasCustomNameForPackage("com.example"))
        assertEquals("Original", repo.getDisplayNameForPackage("com.example", "Original"))
    }

    @Test
    fun `setCustomName - empty string removes existing custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        repo.setCustomNameForPackage("com.example", "")  // ← empty

        assertFalse(repo.hasCustomNameForPackage("com.example"))
        assertEquals("Original", repo.getDisplayNameForPackage("com.example", "Original"))
    }

    // ===========================================
    // REMOVE CUSTOM NAME
    // ===========================================

    @Test
    fun `removeCustomName - returns true when name existed`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        val result = repo.removeCustomNameForPackage("com.example")

        assertTrue(result)
    }

    @Test
    fun `removeCustomName - returns false when name did not exist`() = runTest {
        val repo = createRepository()

        val result = repo.removeCustomNameForPackage("com.nonexistent")

        assertFalse(result)
    }

    @Test
    fun `removeCustomName - name no longer retrievable after remove`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        repo.removeCustomNameForPackage("com.example")

        assertEquals("Original", repo.getDisplayNameForPackage("com.example", "Original"))
        assertFalse(repo.hasCustomNameForPackage("com.example"))
    }

    // ===========================================
    // HAS CUSTOM NAME
    // ===========================================

    @Test
    fun `hasCustomName - returns false when not set`() = runTest {
        val repo = createRepository()

        assertFalse(repo.hasCustomNameForPackage("com.example"))
    }

    @Test
    fun `hasCustomName - returns true when set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.example", "Custom")

        assertTrue(repo.hasCustomNameForPackage("com.example"))
    }

    // ===========================================
    // GET ALL CUSTOM NAMES
    // ===========================================

    @Test
    fun `getAllCustomNames - returns empty map initially`() = runTest {
        val repo = createRepository()

        val result = repo.getAllCustomNames()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllCustomNames - returns all set names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.app1", "App One")
        repo.setCustomNameForPackage("com.app2", "App Two")

        val result = repo.getAllCustomNames()

        assertEquals(2, result.size)
        assertEquals("App One", result["com.app1"])
        assertEquals("App Two", result["com.app2"])
    }

    @Test
    fun `getAllCustomNames - returns defensive copy`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.app1", "App One")

        val result = repo.getAllCustomNames()
        repo.setCustomNameForPackage("com.app2", "App Two")

        // Original result should not be affected
        assertEquals(1, result.size)
    }

    // ===========================================
    // BATCH SET
    // ===========================================

    @Test
    fun `setCustomNamesInBatch - returns true for empty map`() = runTest {
        val repo = createRepository()

        val result = repo.setCustomNamesInBatch(emptyMap())

        assertTrue(result)
    }

    @Test
    fun `setCustomNamesInBatch - sets all names`() = runTest {
        val repo = createRepository()
        val names = mapOf(
            "com.app1" to "App One",
            "com.app2" to "App Two",
            "com.app3" to "App Three"
        )

        val result = repo.setCustomNamesInBatch(names)

        assertTrue(result)
        assertEquals("App One", repo.getDisplayNameForPackage("com.app1", "Original"))
        assertEquals("App Two", repo.getDisplayNameForPackage("com.app2", "Original"))
        assertEquals("App Three", repo.getDisplayNameForPackage("com.app3", "Original"))
    }

    @Test
    fun `setCustomNamesInBatch - skips blank names`() = runTest {
        val repo = createRepository()
        val names = mapOf(
            "com.app1" to "App One",
            "com.app2" to "   ",
            "com.app3" to "App Three"
        )

        repo.setCustomNamesInBatch(names)

        assertTrue(repo.hasCustomNameForPackage("com.app1"))
        assertFalse(repo.hasCustomNameForPackage("com.app2"))
        assertTrue(repo.hasCustomNameForPackage("com.app3"))
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears all custom names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage("com.app1", "App One")
        repo.setCustomNameForPackage("com.app2", "App Two")

        repo.purgeRepository()

        assertTrue(repo.getAllCustomNames().isEmpty())
        assertFalse(repo.hasCustomNameForPackage("com.app1"))
        assertFalse(repo.hasCustomNameForPackage("com.app2"))
    }
}

/**
 * Verifiziert den Fake
 */
class FakeCustomNamesRepositoryContractTest : CustomNamesRepositoryContractTest() {
    override fun createRepository(): CustomNamesRepository = FakeCustomNamesRepository()
}