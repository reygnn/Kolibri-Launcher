package com.github.reygnn.kolibri_launcher.contract

import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// TIMESTAMP 2026-01-05

/**
 * Contract Tests für WallpaperRepository.
 *
 * Verifiziert das Verhalten der Repository-Implementierungen:
 * - Flow liefert reaktive Updates
 * - Sync-Methode liefert aktuellen State
 * - Save/Clear/Purge modifizieren State korrekt
 *
 * Läuft mit Robolectric für Uri.parse() Support.
 */
@RunWith(RobolectricTestRunner::class)
abstract class WallpaperRepositoryContractTest {

    abstract fun createRepository(): WallpaperRepository

    // Test URIs
    private val testUri1 = Uri.parse("content://media/external/images/media/12345")
    private val testUri2 = Uri.parse("content://media/external/images/media/67890")

    // ===========================================
    // FLOW - INITIAL STATE
    // ===========================================

    @Test
    fun `flow - initially returns NONE state`() = runTest {
        val repo = createRepository()

        val result = repo.wallpaperState.first()

        assertEquals(WallpaperState.NONE, result)
    }

    @Test
    fun `flow - initial state has null imageUri`() = runTest {
        val repo = createRepository()

        val result = repo.wallpaperState.first()

        assertNull(result.imageUri)
    }

    @Test
    fun `flow - initial state has default scale of 1`() = runTest {
        val repo = createRepository()

        val result = repo.wallpaperState.first()

        assertEquals(1.0f, result.scale)
    }

    @Test
    fun `flow - initial state has zero translate`() = runTest {
        val repo = createRepository()

        val result = repo.wallpaperState.first()

        assertEquals(0.0f, result.translateX)
        assertEquals(0.0f, result.translateY)
    }

    // ===========================================
    // GET WALLPAPER STATE SYNC
    // ===========================================

    @Test
    fun `getWallpaperStateSync - initially returns NONE`() = runTest {
        val repo = createRepository()

        val result = repo.getWallpaperStateSync()

        assertEquals(WallpaperState.NONE, result)
    }

    @Test
    fun `getWallpaperStateSync - returns saved state`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(
            imageUri = testUri1,
            scale = 1.5f,
            translateX = 10f,
            translateY = 20f
        )
        repo.saveWallpaperState(state)

        val result = repo.getWallpaperStateSync()

        assertEquals(state.imageUri, result.imageUri)
        assertEquals(state.scale, result.scale)
        assertEquals(state.translateX, result.translateX)
        assertEquals(state.translateY, result.translateY)
    }

    @Test
    fun `getWallpaperStateSync - matches flow value`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(imageUri = testUri1, scale = 2.0f)
        repo.saveWallpaperState(state)

        val syncResult = repo.getWallpaperStateSync()
        val flowResult = repo.wallpaperState.first()

        assertEquals(syncResult, flowResult)
    }

    // ===========================================
    // SAVE WALLPAPER STATE
    // ===========================================

    @Test
    fun `saveWallpaperState - updates flow`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(
            imageUri = testUri1,
            scale = 1.75f,
            translateX = 100f,
            translateY = -50f
        )

        repo.saveWallpaperState(state)

        val result = repo.wallpaperState.first()
        assertEquals(testUri1, result.imageUri)
        assertEquals(1.75f, result.scale)
        assertEquals(100f, result.translateX)
        assertEquals(-50f, result.translateY)
    }

    @Test
    fun `saveWallpaperState - overwrites previous state`() = runTest {
        val repo = createRepository()
        val state1 = WallpaperState(imageUri = testUri1, scale = 1.0f)
        val state2 = WallpaperState(imageUri = testUri2, scale = 2.0f)

        repo.saveWallpaperState(state1)
        repo.saveWallpaperState(state2)

        val result = repo.wallpaperState.first()
        assertEquals(testUri2, result.imageUri)
        assertEquals(2.0f, result.scale)
    }

    @Test
    fun `saveWallpaperState - with null URI clears wallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri1, scale = 1.5f))

        repo.saveWallpaperState(WallpaperState.NONE)

        val result = repo.wallpaperState.first()
        assertNull(result.imageUri)
    }

    @Test
    fun `saveWallpaperState - preserves all fields`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(
            imageUri = testUri1,
            scale = 2.5f,
            translateX = 123.45f,
            translateY = -67.89f
        )

        repo.saveWallpaperState(state)

        val result = repo.wallpaperState.first()
        assertEquals(state.imageUri, result.imageUri)
        assertEquals(state.scale, result.scale, 0.001f)
        assertEquals(state.translateX, result.translateX, 0.001f)
        assertEquals(state.translateY, result.translateY, 0.001f)
    }

    @Test
    fun `saveWallpaperState - handles extreme scale values`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(imageUri = testUri1, scale = 0.1f)

        repo.saveWallpaperState(state)

        assertEquals(0.1f, repo.wallpaperState.first().scale)
    }

    @Test
    fun `saveWallpaperState - handles negative translate values`() = runTest {
        val repo = createRepository()
        val state = WallpaperState(
            imageUri = testUri1,
            translateX = -999f,
            translateY = -999f
        )

        repo.saveWallpaperState(state)

        val result = repo.wallpaperState.first()
        assertEquals(-999f, result.translateX)
        assertEquals(-999f, result.translateY)
    }

    // ===========================================
    // CLEAR WALLPAPER
    // ===========================================

    @Test
    fun `clearWallpaper - resets to NONE state`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri1, scale = 2.0f))

        repo.clearWallpaper()

        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `clearWallpaper - sets imageUri to null`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri1))

        repo.clearWallpaper()

        assertNull(repo.wallpaperState.first().imageUri)
    }

    @Test
    fun `clearWallpaper - resets scale to default`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri1, scale = 3.0f))

        repo.clearWallpaper()

        assertEquals(1.0f, repo.wallpaperState.first().scale)
    }

    @Test
    fun `clearWallpaper - resets translate to zero`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(
            WallpaperState(imageUri = testUri1, translateX = 100f, translateY = 200f)
        )

        repo.clearWallpaper()

        val result = repo.wallpaperState.first()
        assertEquals(0.0f, result.translateX)
        assertEquals(0.0f, result.translateY)
    }

    @Test
    fun `clearWallpaper - on already empty state is idempotent`() = runTest {
        val repo = createRepository()

        repo.clearWallpaper()

        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears wallpaper state`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(
            WallpaperState(imageUri = testUri1, scale = 2.0f, translateX = 50f, translateY = 50f)
        )

        repo.purgeRepository()

        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `purgeRepository - same effect as clearWallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState(imageUri = testUri1, scale = 1.5f))

        repo.purgeRepository()
        val purgeResult = repo.wallpaperState.first()

        // Create fresh repo and use clearWallpaper
        val repo2 = createRepository()
        repo2.saveWallpaperState(WallpaperState(imageUri = testUri1, scale = 1.5f))
        repo2.clearWallpaper()
        val clearResult = repo2.wallpaperState.first()

        assertEquals(clearResult, purgeResult)
    }

    @Test
    fun `purgeRepository - on empty state is safe`() = runTest {
        val repo = createRepository()

        repo.purgeRepository()

        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    // ===========================================
    // CONSISTENCY TESTS
    // ===========================================

    @Test
    fun `consistency - flow and sync always match after save`() = runTest {
        val repo = createRepository()
        val states = listOf(
            WallpaperState(imageUri = testUri1, scale = 1.0f),
            WallpaperState(imageUri = testUri2, scale = 2.0f, translateX = 10f),
            WallpaperState.NONE
        )

        for (state in states) {
            repo.saveWallpaperState(state)

            val flowValue = repo.wallpaperState.first()
            val syncValue = repo.getWallpaperStateSync()

            assertEquals(flowValue, syncValue)
        }
    }

    @Test
    fun `consistency - multiple saves preserve last state only`() = runTest {
        val repo = createRepository()

        repeat(10) { i ->
            repo.saveWallpaperState(
                WallpaperState(imageUri = testUri1, scale = i.toFloat())
            )
        }

        assertEquals(9.0f, repo.wallpaperState.first().scale)
    }
}

/**
 * Verifiziert das FakeWallpaperRepository
 */
class FakeWallpaperRepositoryContractTest : WallpaperRepositoryContractTest() {
    override fun createRepository(): WallpaperRepository = FakeWallpaperRepository()
}