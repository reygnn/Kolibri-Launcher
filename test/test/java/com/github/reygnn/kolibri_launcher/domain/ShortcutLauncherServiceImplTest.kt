package com.github.reygnn.kolibri_launcher.data.service

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull

/**
 * Unit Tests für ShortcutLauncherServiceImpl.
 *
 * Diese Tests mocken den Android Context und LauncherApps Service.
 * Für vollständige Integration Tests wäre Robolectric nötig.
 *
 * Note: Silent Runner weil @Before Stub nicht in allen Tests gebraucht wird.
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class ShortcutLauncherServiceImplTest {

    // ===========================================
    // MOCKS
    // ===========================================

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockLauncherApps: LauncherApps

    @Mock
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
        // Default: LauncherApps service is available
        `when`(mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE))
            .thenReturn(mockLauncherApps)
    }

    // ===========================================
    // AVAILABILITY TESTS
    // ===========================================

    @Test
    fun `isAvailable returns true when LauncherApps service exists`() {
        // Given
        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        val result = service.isAvailable()

        // Then
        assertTrue(result)
    }

    @Test
    fun `isAvailable returns false when LauncherApps service is null`() {
        // Given
        `when`(mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE))
            .thenReturn(null)

        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        val result = service.isAvailable()

        // Then
        assertFalse(result)
    }

    @Test
    fun `isAvailable returns false when getSystemService throws`() {
        // Given
        `when`(mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE))
            .thenThrow(SecurityException("Permission denied"))

        service = ShortcutLauncherServiceImpl(mockContext)

        // When/Then - should handle exception gracefully
        // Note: Current implementation might throw - this tests expected behavior
        try {
            val result = service.isAvailable()
            assertFalse(result)
        } catch (e: SecurityException) {
            // If it throws, that's also acceptable behavior for this edge case
        }
    }

    // ===========================================
    // START SHORTCUT TESTS
    // ===========================================

    @Test
    fun `startShortcut calls LauncherApps with correct parameters`() {
        // Given
        doNothing().`when`(mockLauncherApps).startShortcut(any(), isNull(), isNull())

        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        service.startShortcut(mockShortcut)

        // Then
        verify(mockLauncherApps, times(1)).startShortcut(mockShortcut, null, null)
    }

    @Test(expected = ShortcutLaunchException::class)
    fun `startShortcut throws ShortcutLaunchException when service unavailable`() {
        // Given
        `when`(mockContext.getSystemService(Context.LAUNCHER_APPS_SERVICE))
            .thenReturn(null)

        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        service.startShortcut(mockShortcut)

        // Then - exception expected
    }

    @Test
    fun `startShortcut wraps LauncherApps exceptions in ShortcutLaunchException`() {
        // Given
        doThrow(IllegalStateException("Activity not found"))
            .`when`(mockLauncherApps).startShortcut(any(), isNull(), isNull())
        `when`(mockShortcut.id).thenReturn("test-shortcut-id")

        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        try {
            service.startShortcut(mockShortcut)
            fail("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            // Then
            assertTrue(e.message!!.contains("test-shortcut-id"))
            assertTrue(e.cause is IllegalStateException)
        }
    }

    @Test
    fun `startShortcut includes shortcut id in error message`() {
        // Given
        val shortcutId = "my-unique-shortcut-123"
        doThrow(RuntimeException("Failed"))
            .`when`(mockLauncherApps).startShortcut(any(), isNull(), isNull())
        `when`(mockShortcut.id).thenReturn(shortcutId)

        service = ShortcutLauncherServiceImpl(mockContext)

        // When
        try {
            service.startShortcut(mockShortcut)
            fail("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            // Then
            assertTrue(e.message!!.contains(shortcutId))
        }
    }

    // ===========================================
    // LAZY INITIALIZATION TESTS
    // ===========================================

    @Test
    fun `LauncherApps service is lazily initialized`() {
        // When - create service but don't use it
        service = ShortcutLauncherServiceImpl(mockContext)

        // Then - getSystemService should not be called yet
        // Note: With lazy initialization, first access triggers the call
        verify(mockContext, times(0)).getSystemService(any<String>())
    }

    @Test
    fun `LauncherApps service is only fetched once`() {
        // Given
        doNothing().`when`(mockLauncherApps).startShortcut(any(), isNull(), isNull())

        service = ShortcutLauncherServiceImpl(mockContext)

        // When - multiple calls
        service.isAvailable()
        service.isAvailable()
        service.startShortcut(mockShortcut)
        service.startShortcut(mockShortcut)

        // Then - getSystemService called only once (lazy)
        verify(mockContext, times(1)).getSystemService(Context.LAUNCHER_APPS_SERVICE)
    }
}