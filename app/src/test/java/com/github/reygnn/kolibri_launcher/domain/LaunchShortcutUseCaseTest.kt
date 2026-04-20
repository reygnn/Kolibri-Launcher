package com.github.reygnn.kolibri_launcher.domain.usecase

import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LaunchShortcutUseCaseTest {

    // ===========================================
    // MOCKS
    // ===========================================

    @MockK
    private lateinit var mockLauncherService: ShortcutLauncherService

    @MockK
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
        MockKAnnotations.init(this)
        useCase = LaunchShortcutUseCase(mockLauncherService)
    }

    // ===========================================
    // HAPPY PATH TESTS
    // ===========================================

    @Test
    fun `execute with valid shortcut returns Success`() {
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } just runs

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Success)
        verify(exactly = 1) { mockLauncherService.startShortcut(mockShortcut) }
    }

    @Test
    fun `execute calls service with correct shortcut`() {
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } just runs

        useCase.execute(mockShortcut)

        verify { mockLauncherService.startShortcut(mockShortcut) }
    }

    // ===========================================
    // NULL SHORTCUT TESTS
    // ===========================================

    @Test
    fun `execute with null shortcut returns ShortcutNull error`() {
        val result = useCase.execute(null)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        assertEquals(
            LaunchShortcutUseCase.Error.ShortcutNull,
            (result as LaunchShortcutUseCase.Result.Failure).error
        )
    }

    @Test
    fun `execute with null shortcut does not call service`() {
        useCase.execute(null)

        verify(exactly = 0) { mockLauncherService.startShortcut(any()) }
        verify(exactly = 0) { mockLauncherService.isAvailable() }
    }

    // ===========================================
    // SERVICE UNAVAILABLE TESTS
    // ===========================================

    @Test
    fun `execute with unavailable service returns ServiceUnavailable error`() {
        every { mockLauncherService.isAvailable() } returns false

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        assertEquals(
            LaunchShortcutUseCase.Error.ServiceUnavailable,
            (result as LaunchShortcutUseCase.Result.Failure).error
        )
    }

    @Test
    fun `execute with unavailable service does not attempt launch`() {
        every { mockLauncherService.isAvailable() } returns false

        useCase.execute(mockShortcut)

        verify(exactly = 0) { mockLauncherService.startShortcut(any()) }
    }

    @Test
    fun `execute checks service availability before launching`() {
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } just runs

        useCase.execute(mockShortcut)

        verify { mockLauncherService.isAvailable() }
    }

    // ===========================================
    // LAUNCH FAILURE TESTS
    // ===========================================

    @Test
    fun `execute with ShortcutLaunchException returns LaunchFailed error`() {
        val exception = ShortcutLaunchException("App not installed")
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws exception

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.LaunchFailed)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.LaunchFailed).cause)
    }

    @Test
    fun `execute with nested cause preserves exception chain`() {
        val rootCause = IllegalStateException("Activity not found")
        val exception = ShortcutLaunchException("Launch failed", rootCause)
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws exception

        val result = useCase.execute(mockShortcut)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        val launchError = failure.error as LaunchShortcutUseCase.Error.LaunchFailed
        assertEquals(rootCause, launchError.cause.cause)
    }

    // ===========================================
    // UNKNOWN ERROR TESTS
    // ===========================================

    @Test
    fun `execute with RuntimeException returns Unknown error`() {
        val exception = RuntimeException("Something unexpected")
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws exception

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.Unknown)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.Unknown).cause)
    }

    @Test
    fun `execute with OutOfMemoryError returns Unknown error`() {
        val error = OutOfMemoryError("No memory left")
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws error

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        assertTrue((result as LaunchShortcutUseCase.Result.Failure).error is LaunchShortcutUseCase.Error.Unknown)
    }

    @Test
    fun `execute with SecurityException returns Unknown error`() {
        val exception = SecurityException("Permission denied")
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws exception

        val result = useCase.execute(mockShortcut)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertTrue(failure.error is LaunchShortcutUseCase.Error.Unknown)
        assertEquals(exception, (failure.error as LaunchShortcutUseCase.Error.Unknown).cause)
    }

    // ===========================================
    // EXECUTION ORDER TESTS
    // ===========================================

    @Test
    fun `execute validates shortcut before checking service`() {
        val result = useCase.execute(null)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertEquals(LaunchShortcutUseCase.Error.ShortcutNull, failure.error)
        verify(exactly = 0) { mockLauncherService.isAvailable() }
    }

    @Test
    fun `execute checks service availability before attempting launch`() {
        every { mockLauncherService.isAvailable() } returns false

        useCase.execute(mockShortcut)

        verify(exactly = 1) { mockLauncherService.isAvailable() }
        verify(exactly = 0) { mockLauncherService.startShortcut(any()) }
    }

    // ===========================================
    // RESULT TYPE TESTS
    // ===========================================

    @Test
    fun `Success is a singleton object`() {
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } just runs

        val result1 = useCase.execute(mockShortcut)
        val result2 = useCase.execute(mockShortcut)

        assertTrue(result1 === result2)
    }

    @Test
    fun `Failure wraps different error types correctly`() {
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
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } throws ShortcutLaunchException("Service died")

        val result = useCase.execute(mockShortcut)

        assertTrue(result is LaunchShortcutUseCase.Result.Failure)
        assertTrue((result as LaunchShortcutUseCase.Result.Failure).error is LaunchShortcutUseCase.Error.LaunchFailed)
    }

    @Test
    fun `execute is idempotent for same shortcut`() {
        every { mockLauncherService.isAvailable() } returns true
        every { mockLauncherService.startShortcut(mockShortcut) } just runs

        val result1 = useCase.execute(mockShortcut)
        val result2 = useCase.execute(mockShortcut)
        val result3 = useCase.execute(mockShortcut)

        assertTrue(result1 is LaunchShortcutUseCase.Result.Success)
        assertTrue(result2 is LaunchShortcutUseCase.Result.Success)
        assertTrue(result3 is LaunchShortcutUseCase.Result.Success)
        verify(exactly = 3) { mockLauncherService.startShortcut(mockShortcut) }
    }
}
