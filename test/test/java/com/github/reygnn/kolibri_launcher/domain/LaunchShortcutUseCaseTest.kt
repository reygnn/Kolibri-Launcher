package com.github.reygnn.kolibri_launcher.domain.usecase

import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any

/**
 * Unit Tests für LaunchShortcutUseCase.
 *
 * Testet alle Pfade durch die Shortcut-Launch-Logik:
 * - Happy Path (erfolgreicher Launch)
 * - Null Shortcut
 * - Service nicht verfügbar
 * - Launch-Fehler
 * - Unerwartete Exceptions
 */
@RunWith(MockitoJUnitRunner::class)
class LaunchShortcutUseCaseTest {

    // ===========================================
    // MOCKS
    // ===========================================

    @Mock
    private lateinit var mockLauncherService: ShortcutLauncherService

    @Mock
    private lateinit var mockShortcut: ShortcutInfo

    // ===========================================
    // SYSTEM UNDER TEST
    // ===========================================

    private lateinit var useCase: LaunchShortcutUseCase

    // ===========================================
    // SETUP
    // ===========================================

    @Before
    fun setUp() {
        useCase = LaunchShortcutUseCase(mockLauncherService)
    }

    // ===========================================
    // HAPPY PATH TESTS
    // ===========================================

    @Test
    fun `execute with valid shortcut returns Success`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doNothing().`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Success)
        verify(mockLauncherService, times(1)).startShortcut(mockShortcut)
    }

    @Test
    fun `execute calls service with correct shortcut`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doNothing().`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        useCase.execute(mockShortcut)

        // Then
        verify(mockLauncherService).startShortcut(mockShortcut)
    }

    // ===========================================
    // NULL SHORTCUT TESTS
    // ===========================================

    @Test
    fun `execute with null shortcut returns ShortcutNull error`() {
        // When
        val result = useCase.execute(null)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertEquals(LaunchShortcutUseCase.Error.ShortcutNull, failure.error)
    }

    @Test
    fun `execute with null shortcut does not call service`() {
        // When
        useCase.execute(null)

        // Then
        verify(mockLauncherService, never()).startShortcut(any())
        verify(mockLauncherService, never()).isAvailable()
    }

    // ===========================================
    // SERVICE UNAVAILABLE TESTS
    // ===========================================

    @Test
    fun `execute with unavailable service returns ServiceUnavailable error`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(false)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertEquals(LaunchShortcutUseCase.Error.ServiceUnavailable, failure.error)
    }

    @Test
    fun `execute with unavailable service does not attempt launch`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(false)

        // When
        useCase.execute(mockShortcut)

        // Then
        verify(mockLauncherService, never()).startShortcut(any())
    }

    @Test
    fun `execute checks service availability before launching`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doNothing().`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        useCase.execute(mockShortcut)

        // Then
        verify(mockLauncherService).isAvailable()
    }

    // ===========================================
    // LAUNCH FAILURE TESTS
    // ===========================================

    @Test
    fun `execute with ShortcutLaunchException returns LaunchFailed error`() {
        // Given
        val exception = ShortcutLaunchException("App not installed")
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(exception).`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.LaunchFailed)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.LaunchFailed).cause)
    }

    @Test
    fun `execute with nested cause preserves exception chain`() {
        // Given
        val rootCause = IllegalStateException("Activity not found")
        val exception = ShortcutLaunchException("Launch failed", rootCause)
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(exception).`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        val failure = result as LaunchShortcutUseCase.Result.Failure
        val launchError = failure.error as LaunchShortcutUseCase.Error.LaunchFailed
        assertEquals(rootCause, launchError.cause.cause)
    }

    // ===========================================
    // UNKNOWN ERROR TESTS
    // ===========================================

    @Test
    fun `execute with RuntimeException returns Unknown error`() {
        // Given
        val exception = RuntimeException("Something unexpected")
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(exception).`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.Unknown)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.Unknown).cause)
    }

    @Test
    fun `execute with OutOfMemoryError returns Unknown error`() {
        // Given
        val error = OutOfMemoryError("No memory left")
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(error).`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.Unknown)
    }

    @Test
    fun `execute with SecurityException returns Unknown error`() {
        // Given
        val exception = SecurityException("Permission denied")
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(exception).`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.Unknown)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.Unknown).cause)
    }

    // ===========================================
    // EXECUTION ORDER TESTS
    // ===========================================

    @Test
    fun `execute validates shortcut before checking service`() {
        // Given: Service check would fail, but shortcut is null

        // When
        val result = useCase.execute(null)

        // Then: Should return ShortcutNull, not ServiceUnavailable
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertEquals(LaunchShortcutUseCase.Error.ShortcutNull, failure.error)

        // Service should never be queried
        verify(mockLauncherService, never()).isAvailable()
    }

    @Test
    fun `execute checks service availability before attempting launch`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(false)

        // When
        useCase.execute(mockShortcut)

        // Then: Should check availability but not attempt launch
        verify(mockLauncherService, times(1)).isAvailable()
        verify(mockLauncherService, never()).startShortcut(any())
    }

    // ===========================================
    // RESULT TYPE TESTS
    // ===========================================

    @Test
    fun `Success is a singleton object`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doNothing().`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result1 = useCase.execute(mockShortcut)
        val result2 = useCase.execute(mockShortcut)

        // Then
        assertTrue(result1 === result2) // Same instance
    }

    @Test
    fun `Failure wraps different error types correctly`() {
        // Test all error types are properly distinguishable
        val errors = listOf(
            LaunchShortcutUseCase.Error.ShortcutNull,
            LaunchShortcutUseCase.Error.ServiceUnavailable,
            LaunchShortcutUseCase.Error.LaunchFailed(RuntimeException()),
            LaunchShortcutUseCase.Error.Unknown(RuntimeException())
        )

        errors.forEach { error ->
            val failure = LaunchShortcutUseCase.Result.Failure(error)
            assertEquals(error, failure.error)
        }
    }

    // ===========================================
    // EDGE CASE TESTS
    // ===========================================

    @Test
    fun `execute handles service becoming unavailable between checks gracefully`() {
        // Given: Service available on check, throws on use
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doThrow(ShortcutLaunchException("Service died"))
            .`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result = useCase.execute(mockShortcut)

        // Then
        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.LaunchFailed)
    }

    @Test
    fun `execute is idempotent for same shortcut`() {
        // Given
        `when`(mockLauncherService.isAvailable()).thenReturn(true)
        doNothing().`when`(mockLauncherService).startShortcut(mockShortcut)

        // When
        val result1 = useCase.execute(mockShortcut)
        val result2 = useCase.execute(mockShortcut)
        val result3 = useCase.execute(mockShortcut)

        // Then
        assertTrue(result1 is LaunchShortcutUseCase.Result.Success)
        assertTrue(result2 is LaunchShortcutUseCase.Result.Success)
        assertTrue(result3 is LaunchShortcutUseCase.Result.Success)
        verify(mockLauncherService, times(3)).startShortcut(mockShortcut)
    }
}