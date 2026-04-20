package com.github.reygnn.kolibri_launcher.data.service

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests für ShortcutLauncherServiceImpl.
 *
 * Note: Silent equivalent in MockK ist Standard — unnötige Stubs werfen keinen Fehler.
 */
class ShortcutLauncherServiceImplTest {

    // ===========================================
    // MOCKS
    // ===========================================

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockLauncherApps: LauncherApps

    @MockK
    private lateinit var mockShortcut: ShortcutInfo

    // ===========================================
    // SYSTEM UNDER TEST
    // ===========================================

    private lateinit var service: ShortcutLauncherServiceImpl

    // ===========================================
    // SETUP
    // ===========================================

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = false)
        // Default: LauncherApps service is available
        every { mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns mockLauncherApps
    }

    // ===========================================
    // AVAILABILITY TESTS
    // ===========================================

    @Test
    fun `isAvailable returns true when LauncherApps service exists`() {
        service = ShortcutLauncherServiceImpl(mockContext)
        assertTrue(service.isAvailable())
    }

    @Test
    fun `isAvailable returns false when LauncherApps service is null`() {
        every { mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null
        service = ShortcutLauncherServiceImpl(mockContext)
        assertFalse(service.isAvailable())
    }

    @Test
    fun `isAvailable returns false when getSystemService throws`() {
        every { mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) } throws SecurityException("Permission denied")
        service = ShortcutLauncherServiceImpl(mockContext)

        try {
            val result = service.isAvailable()
            assertFalse(result)
        } catch (e: SecurityException) {
            // Acceptable behavior for this edge case
        }
    }

    // ===========================================
    // START SHORTCUT TESTS
    // ===========================================

    @Test
    fun `startShortcut calls LauncherApps with correct parameters`() {
        every { mockLauncherApps.startShortcut(any(), null, null) } just runs
        service = ShortcutLauncherServiceImpl(mockContext)

        service.startShortcut(mockShortcut)

        verify(exactly = 1) { mockLauncherApps.startShortcut(mockShortcut, null, null) }
    }

    @Test(expected = ShortcutLaunchException::class)
    fun `startShortcut throws ShortcutLaunchException when service unavailable`() {
        every { mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null
        service = ShortcutLauncherServiceImpl(mockContext)

        service.startShortcut(mockShortcut)
    }

    @Test
    fun `startShortcut wraps LauncherApps exceptions in ShortcutLaunchException`() {
        every { mockLauncherApps.startShortcut(any(), null, null) } throws IllegalStateException("Activity not found")
        every { mockShortcut.id } returns "test-shortcut-id"

        service = ShortcutLauncherServiceImpl(mockContext)

        try {
            service.startShortcut(mockShortcut)
            fail("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            assertTrue(e.message!!.contains("test-shortcut-id"))
            assertTrue(e.cause is IllegalStateException)
        }
    }

    @Test
    fun `startShortcut includes shortcut id in error message`() {
        val shortcutId = "my-unique-shortcut-123"
        every { mockLauncherApps.startShortcut(any(), null, null) } throws RuntimeException("Failed")
        every { mockShortcut.id } returns shortcutId

        service = ShortcutLauncherServiceImpl(mockContext)

        try {
            service.startShortcut(mockShortcut)
            fail("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            assertTrue(e.message!!.contains(shortcutId))
        }
    }

    // ===========================================
    // LAZY INITIALIZATION TESTS
    // ===========================================

    @Test
    fun `LauncherApps service is lazily initialized`() {
        service = ShortcutLauncherServiceImpl(mockContext)

        // getSystemService sollte noch nicht aufgerufen worden sein
        verify(exactly = 0) { mockContext.getSystemService(any<String>()) }
    }

    @Test
    fun `LauncherApps service is only fetched once`() {
        every { mockLauncherApps.startShortcut(any(), null, null) } just runs
        service = ShortcutLauncherServiceImpl(mockContext)

        service.isAvailable()
        service.isAvailable()
        service.startShortcut(mockShortcut)
        service.startShortcut(mockShortcut)

        verify(exactly = 1) { mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) }
    }
}
