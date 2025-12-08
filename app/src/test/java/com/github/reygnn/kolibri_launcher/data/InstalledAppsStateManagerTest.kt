package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class InstalledAppsStateManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var stateManager: InstalledAppsStateManager

    private val app1 = AppInfo("App A", "App A", "com.a", "classA")
    private val app2 = AppInfo("App B", "App B", "com.b", "classB")

    @Before
    fun setup() {
        stateManager = InstalledAppsStateManager()
    }

    @Test
    fun `initial state - returns empty list`() {
        val currentApps = stateManager.getCurrentApps()
        Assert.assertTrue(currentApps.isEmpty())
        Assert.assertTrue(stateManager.rawAppsFlow.value.isEmpty())
    }

    @Test
    fun `updateApps - with valid list - updates flow and cache`() {
        val apps = listOf(app1, app2)

        stateManager.updateApps(apps)

        // Flow should be updated
        Assert.assertEquals(2, stateManager.rawAppsFlow.value.size)
        Assert.assertEquals(app1, stateManager.rawAppsFlow.value[0])

        // Current apps should return this list
        Assert.assertEquals(2, stateManager.getCurrentApps().size)
    }

    @Test
    fun `fallback logic - when flow is empty - returns cached last successful list`() {
        // 1. Erfolgreiches Update (füllt den Cache)
        val validApps = listOf(app1, app2)
        stateManager.updateApps(validApps)

        // 2. Schlechtes Update (leere Liste, z.B. durch Fehler im Loader)
        stateManager.updateApps(emptyList())

        // Assert: Flow ist zwar leer...
        Assert.assertTrue(stateManager.rawAppsFlow.value.isEmpty())

        // ...ABER getCurrentApps() rettet uns mit dem Cache!
        val result = stateManager.getCurrentApps()
        Assert.assertEquals(2, result.size)
        Assert.assertEquals("App A", result[0].displayName)
    }

    @Test
    fun `updateApps - with empty list - does NOT overwrite cache with empty`() {
        // 1. Cache füllen
        stateManager.updateApps(listOf(app1))

        // 2. Leeres Update senden
        stateManager.updateApps(emptyList())

        // 3. Prüfen, ob der Cache noch da ist (indirekt über getCurrentApps)
        val result = stateManager.getCurrentApps()
        Assert.assertFalse("Should return cached value, not empty", result.isEmpty())
        Assert.assertEquals("App A", result[0].displayName)
    }

    @Test
    fun `purgeRepository - does nothing and keeps state`() = runTest {
        // Arrange
        stateManager.updateApps(listOf(app1))

        // Act
        stateManager.purgeRepository()

        // Assert
        // StateManager darf System-Daten nicht löschen!
        Assert.assertEquals(1, stateManager.rawAppsFlow.value.size)
    }

    @Test
    fun `updateApps - handles consecutive updates correctly`() {
        // 1. Initial
        stateManager.updateApps(listOf(app1))
        Assert.assertEquals(1, stateManager.getCurrentApps().size)

        // 2. Update
        stateManager.updateApps(listOf(app1, app2))
        Assert.assertEquals(2, stateManager.getCurrentApps().size)

        // 3. Fallback Check
        stateManager.updateApps(emptyList())
        // Sollte jetzt die 2 Apps aus Schritt 2 zurückgeben (neuester Cache)
        Assert.assertEquals(2, stateManager.getCurrentApps().size)
    }
}