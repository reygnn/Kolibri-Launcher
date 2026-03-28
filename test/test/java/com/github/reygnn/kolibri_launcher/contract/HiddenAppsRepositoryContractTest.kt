package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TIMESTAMP 2025-12-04 20:07

/**
 * Contract Tests für HiddenAppsRepository.
 *
 * Dieses Repository ist Flow-basiert (nicht Event-basiert) -
 * siehe KDoc in HiddenAppsManager für die Architektur-Begründung.
 */
abstract class HiddenAppsRepositoryContractTest {

    abstract fun createRepository(): HiddenAppsRepository

    // ===========================================
    // FLOW - INITIAL STATE
    // ===========================================

    @Test
    fun `flow - initially empty`() = runTest {
        val repo = createRepository()

        val result = repo.hiddenAppsFlow.first()

        assertTrue(result.isEmpty())
    }

    // ===========================================
    // HIDE COMPONENT
    // ===========================================

    @Test
    fun `hideComponent - returns true on success`() = runTest {
        val repo = createRepository()

        val result = repo.hideComponent("com.example/Main")

        assertTrue(result)
    }

    @Test
    fun `hideComponent - updates flow`() = runTest {
        val repo = createRepository()

        repo.hideComponent("com.example/Main")

        assertTrue(repo.hiddenAppsFlow.first().contains("com.example/Main"))
    }

    @Test
    fun `hideComponent - null returns false and does not update flow`() = runTest {
        val repo = createRepository()

        val result = repo.hideComponent(null)

        assertFalse(result)
        assertTrue(repo.hiddenAppsFlow.first().isEmpty())
    }

    @Test
    fun `hideComponent - blank returns false and does not update flow`() = runTest {
        val repo = createRepository()

        val result = repo.hideComponent("   ")

        assertFalse(result)
        assertTrue(repo.hiddenAppsFlow.first().isEmpty())
    }

    @Test
    fun `hideComponent - already hidden returns true`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.example/Main")

        val result = repo.hideComponent("com.example/Main")

        assertTrue(result)
        assertEquals(1, repo.hiddenAppsFlow.first().size)
    }

    // ===========================================
    // SHOW COMPONENT
    // ===========================================

    @Test
    fun `showComponent - returns true on success`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.example/Main")

        val result = repo.showComponent("com.example/Main")

        assertTrue(result)
    }

    @Test
    fun `showComponent - removes from flow`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.example/Main")

        repo.showComponent("com.example/Main")

        assertFalse(repo.hiddenAppsFlow.first().contains("com.example/Main"))
    }

    @Test
    fun `showComponent - null returns false`() = runTest {
        val repo = createRepository()

        val result = repo.showComponent(null)

        assertFalse(result)
    }

    @Test
    fun `showComponent - already visible returns true`() = runTest {
        val repo = createRepository()

        val result = repo.showComponent("com.nonexistent/Main")

        assertTrue(result)
    }

    // ===========================================
    // IS COMPONENT HIDDEN
    // ===========================================

    @Test
    fun `isComponentHidden - returns false when not hidden`() = runTest {
        val repo = createRepository()

        val result = repo.isComponentHidden("com.example/Main")

        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - returns true when hidden`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.example/Main")

        val result = repo.isComponentHidden("com.example/Main")

        assertTrue(result)
    }

    @Test
    fun `isComponentHidden - null returns false`() = runTest {
        val repo = createRepository()

        val result = repo.isComponentHidden(null)

        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - blank returns false`() = runTest {
        val repo = createRepository()

        val result = repo.isComponentHidden("   ")

        assertFalse(result)
    }

    // ===========================================
    // UPDATE COMPONENT VISIBILITIES (BATCH)
    // ===========================================

    @Test
    fun `updateComponentVisibilities - hides multiple components`() = runTest {
        val repo = createRepository()

        repo.updateComponentVisibilities(
            componentsToHide = setOf("com.app1/Main", "com.app2/Main"),
            componentsToShow = emptySet()
        )

        val hidden = repo.hiddenAppsFlow.first()
        assertTrue(hidden.contains("com.app1/Main"))
        assertTrue(hidden.contains("com.app2/Main"))
    }

    @Test
    fun `updateComponentVisibilities - shows multiple components`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.app1/Main")
        repo.hideComponent("com.app2/Main")

        repo.updateComponentVisibilities(
            componentsToHide = emptySet(),
            componentsToShow = setOf("com.app1/Main", "com.app2/Main")
        )

        assertTrue(repo.hiddenAppsFlow.first().isEmpty())
    }

    @Test
    fun `updateComponentVisibilities - atomic hide and show`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.old/Main")

        repo.updateComponentVisibilities(
            componentsToHide = setOf("com.new/Main"),
            componentsToShow = setOf("com.old/Main")
        )

        val hidden = repo.hiddenAppsFlow.first()
        assertTrue(hidden.contains("com.new/Main"))
        assertFalse(hidden.contains("com.old/Main"))
    }

    @Test
    fun `updateComponentVisibilities - show takes precedence over hide for same component`() = runTest {
        val repo = createRepository()

        repo.updateComponentVisibilities(
            componentsToHide = setOf("com.conflict/Main"),
            componentsToShow = setOf("com.conflict/Main")
        )

        // Show sollte gewinnen (wird nach hide ausgeführt)
        assertFalse(repo.hiddenAppsFlow.first().contains("com.conflict/Main"))
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears all hidden apps`() = runTest {
        val repo = createRepository()
        repo.hideComponent("com.app1/Main")
        repo.hideComponent("com.app2/Main")

        repo.purgeRepository()

        assertTrue(repo.hiddenAppsFlow.first().isEmpty())
    }
}

/**
 * Verifiziert den Fake
 */
class FakeHiddenAppsRepositoryContractTest : HiddenAppsRepositoryContractTest() {
    override fun createRepository(): HiddenAppsRepository = FakeHiddenAppsRepository()
}