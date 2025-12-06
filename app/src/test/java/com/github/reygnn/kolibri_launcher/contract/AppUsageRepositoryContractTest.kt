package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für AppUsageRepository.
 *
 * HINWEIS: Die Sortierlogik (zeitgewichteter Score) ist implementation-spezifisch.
 * Der Fake implementiert keine echte Sortierung - er gibt die Liste unverändert zurück.
 * Sortier-Tests werden daher nur auf Basis-Verhalten geprüft.
 */
abstract class AppUsageRepositoryContractTest {

    abstract fun createRepository(): AppUsageRepository

    // Test-Helfer
    private fun createTestApp(name: String, packageName: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = packageName,
        className = "$packageName.MainActivity"
    )

    // ===========================================
    // RECORD PACKAGE LAUNCH
    // ===========================================

    @Test
    fun `recordPackageLaunch - records launch`() = runTest {
        val repo = createRepository()

        repo.recordPackageLaunch("com.example")

        assertTrue(repo.hasUsageDataForPackage("com.example"))
    }

    @Test
    fun `recordPackageLaunch - null does nothing`() = runTest {
        val repo = createRepository()

        repo.recordPackageLaunch(null)

        // Sollte nicht crashen und keine Daten speichern
        assertFalse(repo.hasUsageDataForPackage(null))
    }

    @Test
    fun `recordPackageLaunch - blank does nothing`() = runTest {
        val repo = createRepository()

        repo.recordPackageLaunch("   ")

        assertFalse(repo.hasUsageDataForPackage("   "))
    }

    @Test
    fun `recordPackageLaunch - multiple launches for same package`() = runTest {
        val repo = createRepository()

        repo.recordPackageLaunch("com.example")
        repo.recordPackageLaunch("com.example")
        repo.recordPackageLaunch("com.example")

        assertTrue(repo.hasUsageDataForPackage("com.example"))
    }

    // ===========================================
    // HAS USAGE DATA FOR PACKAGE
    // ===========================================

    @Test
    fun `hasUsageDataForPackage - returns false when no data`() = runTest {
        val repo = createRepository()

        assertFalse(repo.hasUsageDataForPackage("com.nonexistent"))
    }

    @Test
    fun `hasUsageDataForPackage - returns true after launch recorded`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch("com.example")

        assertTrue(repo.hasUsageDataForPackage("com.example"))
    }

    @Test
    fun `hasUsageDataForPackage - null returns false`() = runTest {
        val repo = createRepository()

        assertFalse(repo.hasUsageDataForPackage(null))
    }

    @Test
    fun `hasUsageDataForPackage - blank returns false`() = runTest {
        val repo = createRepository()

        assertFalse(repo.hasUsageDataForPackage("   "))
    }

    // ===========================================
    // REMOVE USAGE DATA FOR PACKAGE
    // ===========================================

    @Test
    fun `removeUsageDataForPackage - removes data`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch("com.example")

        repo.removeUsageDataForPackage("com.example")

        assertFalse(repo.hasUsageDataForPackage("com.example"))
    }

    @Test
    fun `removeUsageDataForPackage - null does not throw`() = runTest {
        val repo = createRepository()

        repo.removeUsageDataForPackage(null)

        assertTrue(true) // Kein Crash
    }

    @Test
    fun `removeUsageDataForPackage - nonexistent package does not throw`() = runTest {
        val repo = createRepository()

        repo.removeUsageDataForPackage("com.nonexistent")

        assertTrue(true) // Kein Crash
    }

    @Test
    fun `removeUsageDataForPackage - only removes specified package`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch("com.keep")
        repo.recordPackageLaunch("com.remove")

        repo.removeUsageDataForPackage("com.remove")

        assertTrue(repo.hasUsageDataForPackage("com.keep"))
        assertFalse(repo.hasUsageDataForPackage("com.remove"))
    }

    // ===========================================
    // SORT APPS BY TIME WEIGHTED USAGE
    // ===========================================

    @Test
    fun `sortAppsByTimeWeightedUsage - empty list returns empty`() = runTest {
        val repo = createRepository()

        val result = repo.sortAppsByTimeWeightedUsage(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - returns all apps`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("App One", "com.app1"),
            createTestApp("App Two", "com.app2"),
            createTestApp("App Three", "com.app3")
        )

        val result = repo.sortAppsByTimeWeightedUsage(apps)

        assertEquals(3, result.size)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - contains same apps`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Alpha", "com.alpha"),
            createTestApp("Beta", "com.beta")
        )

        val result = repo.sortAppsByTimeWeightedUsage(apps)

        val packageNames = result.map { it.packageName }.toSet()
        assertTrue(packageNames.contains("com.alpha"))
        assertTrue(packageNames.contains("com.beta"))
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears all usage data`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch("com.app1")
        repo.recordPackageLaunch("com.app2")
        repo.recordPackageLaunch("com.app3")

        repo.purgeRepository()

        assertFalse(repo.hasUsageDataForPackage("com.app1"))
        assertFalse(repo.hasUsageDataForPackage("com.app2"))
        assertFalse(repo.hasUsageDataForPackage("com.app3"))
    }
}

/**
 * Verifiziert den Fake
 */
class FakeAppUsageRepositoryContractTest : AppUsageRepositoryContractTest() {
    override fun createRepository(): AppUsageRepository = FakeAppUsageRepository()
}