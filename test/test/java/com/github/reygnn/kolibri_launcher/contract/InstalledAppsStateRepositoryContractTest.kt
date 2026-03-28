package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für InstalledAppsStateRepository.
 *
 * Dieses Repository ist der zentrale State-Holder mit Fail-Safe-Caching.
 * Wichtigstes Feature: Bei leerem State wird der letzte erfolgreiche State zurückgegeben.
 */
abstract class InstalledAppsStateRepositoryContractTest {

    abstract fun createRepository(): InstalledAppsStateRepository

    // Test-Helfer
    private fun createTestApp(name: String, packageName: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = packageName,
        className = "$packageName.MainActivity"
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `rawAppsFlow - initially empty`() = runTest {
        val repo = createRepository()

        assertTrue(repo.rawAppsFlow.value.isEmpty())
    }

    @Test
    fun `getCurrentApps - initially empty`() = runTest {
        val repo = createRepository()

        assertTrue(repo.getCurrentApps().isEmpty())
    }

    // ===========================================
    // UPDATE APPS
    // ===========================================

    @Test
    fun `updateApps - updates flow`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("App One", "com.app1"),
            createTestApp("App Two", "com.app2")
        )

        repo.updateApps(apps)

        assertEquals(2, repo.rawAppsFlow.value.size)
        assertEquals("App One", repo.rawAppsFlow.value[0].displayName)
    }

    @Test
    fun `updateApps - getCurrentApps returns updated list`() = runTest {
        val repo = createRepository()
        val apps = listOf(createTestApp("MyApp", "com.myapp"))

        repo.updateApps(apps)

        assertEquals(1, repo.getCurrentApps().size)
        assertEquals("MyApp", repo.getCurrentApps()[0].displayName)
    }

    @Test
    fun `updateApps - overwrites previous apps`() = runTest {
        val repo = createRepository()
        repo.updateApps(listOf(createTestApp("First", "com.first")))

        repo.updateApps(listOf(
            createTestApp("Second", "com.second"),
            createTestApp("Third", "com.third")
        ))

        assertEquals(2, repo.rawAppsFlow.value.size)
        assertEquals("Second", repo.rawAppsFlow.value[0].displayName)
    }

    // ===========================================
    // FAIL-SAFE CACHING (Kernfeature!)
    // ===========================================

    @Test
    fun `getCurrentApps - returns cached list when flow is empty`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("Cached", "com.cached"),
            createTestApp("Apps", "com.apps")
        )
        repo.updateApps(apps)

        // Leere Liste updaten (simuliert Fehler)
        repo.updateApps(emptyList())

        // Flow ist leer
        assertTrue(repo.rawAppsFlow.value.isEmpty())
        // Aber getCurrentApps gibt gecachte Liste zurück
        assertEquals(2, repo.getCurrentApps().size)
        assertEquals("Cached", repo.getCurrentApps()[0].displayName)
    }

    @Test
    fun `updateApps - empty list does not overwrite cache`() = runTest {
        val repo = createRepository()
        repo.updateApps(listOf(createTestApp("Original", "com.original")))

        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())

        // Cache bleibt erhalten
        assertEquals(1, repo.getCurrentApps().size)
        assertEquals("Original", repo.getCurrentApps()[0].displayName)
    }

    @Test
    fun `updateApps - non-empty list updates cache`() = runTest {
        val repo = createRepository()
        repo.updateApps(listOf(createTestApp("First", "com.first")))

        repo.updateApps(listOf(createTestApp("Second", "com.second")))
        repo.updateApps(emptyList())

        // Cache sollte "Second" sein, nicht "First"
        assertEquals("Second", repo.getCurrentApps()[0].displayName)
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears flow and cache`() = runTest {
        val repo = createRepository()
        repo.updateApps(listOf(createTestApp("App", "com.app")))

        repo.purgeRepository()

        assertTrue(repo.rawAppsFlow.value.isEmpty())
        assertTrue(repo.getCurrentApps().isEmpty())
    }
}

/**
 * Verifiziert den Fake
 */
class FakeInstalledAppsStateRepositoryContractTest : InstalledAppsStateRepositoryContractTest() {
    override fun createRepository(): InstalledAppsStateRepository = FakeInstalledAppsStateRepository()
}