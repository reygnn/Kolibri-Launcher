package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für InstalledAppsRepository.
 *
 * HINWEIS: Dieses Repository ist ein Wrapper um den PackageManager.
 * Der echte Manager liest System-Daten, der Fake verwendet internen State.
 * Diese Tests verifizieren nur das Interface-Verhalten.
 */
abstract class InstalledAppsRepositoryContractTest {

    abstract fun createRepository(): InstalledAppsRepository

    // Muss vom Fake überschrieben werden, um Test-Daten zu setzen
    abstract fun setApps(repo: InstalledAppsRepository, apps: List<AppInfo>)

    // Test-Helfer
    private fun createTestApp(name: String, packageName: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = packageName,
        className = "$packageName.MainActivity"
    )

    // ===========================================
    // GET INSTALLED APPS
    // ===========================================

    @Test
    fun `getInstalledApps - returns flow`() = runTest {
        val repo = createRepository()

        val result = repo.getInstalledApps().first()

        // Flow sollte nicht null sein (kann leer sein)
        assertTrue(result is List<AppInfo>)
    }

    @Test
    fun `getInstalledApps - returns set apps`() = runTest {
        val repo = createRepository()
        val apps = listOf(
            createTestApp("App One", "com.app1"),
            createTestApp("App Two", "com.app2")
        )
        setApps(repo, apps)

        val result = repo.getInstalledApps().first()

        assertEquals(2, result.size)
        assertEquals("App One", result[0].displayName)
        assertEquals("App Two", result[1].displayName)
    }

    @Test
    fun `getInstalledApps - updates when apps change`() = runTest {
        val repo = createRepository()
        setApps(repo, listOf(createTestApp("Initial", "com.initial")))

        assertEquals(1, repo.getInstalledApps().first().size)

        setApps(repo, listOf(
            createTestApp("App One", "com.app1"),
            createTestApp("App Two", "com.app2"),
            createTestApp("App Three", "com.app3")
        ))

        assertEquals(3, repo.getInstalledApps().first().size)
    }

    // ===========================================
    // TRIGGER APPS UPDATE
    // ===========================================

    @Test
    fun `triggerAppsUpdate - does not throw`() = runTest {
        val repo = createRepository()

        // Sollte nicht werfen
        repo.triggerAppsUpdate()

        assertTrue(true)
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - does not throw`() = runTest {
        val repo = createRepository()
        setApps(repo, listOf(createTestApp("App", "com.app")))

        // Sollte nicht werfen
        repo.purgeRepository()

        assertTrue(true)
    }
}

/**
 * Verifiziert den Fake
 */
class FakeInstalledAppsRepositoryContractTest : InstalledAppsRepositoryContractTest() {

    override fun createRepository(): InstalledAppsRepository = FakeInstalledAppsRepository()

    override fun setApps(repo: InstalledAppsRepository, apps: List<AppInfo>) {
        (repo as FakeInstalledAppsRepository).installedApps = apps
    }

    // Zusätzliche Fake-spezifische Tests

    @Test
    fun `triggerAppsUpdate - increments call count`() = runTest {
        val repo = FakeInstalledAppsRepository()

        repo.triggerAppsUpdate()
        repo.triggerAppsUpdate()

        assertEquals(2, repo.triggerUpdateCallCount)
    }

    @Test
    fun `purgeRepository - clears apps and resets counter`() = runTest {
        val repo = FakeInstalledAppsRepository()
        repo.installedApps = listOf(
            AppInfo("App", "App", "com.app", "com.app.Main")
        )
        repo.triggerAppsUpdate()

        repo.purgeRepository()

        assertTrue(repo.installedApps.isEmpty())
        assertEquals(0, repo.triggerUpdateCallCount)
    }
}