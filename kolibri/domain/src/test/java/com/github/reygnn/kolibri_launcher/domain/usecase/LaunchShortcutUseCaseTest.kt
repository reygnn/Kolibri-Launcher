package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class LaunchShortcutUseCaseTest {

    // ===========================================
    // MOCKS
    // ===========================================

    @MockK
    private lateinit var shortcutLauncherService: ShortcutLauncherService

    private val shortcutInfo = LauncherShortcut(
        id = "test_shortcut",
        packageName = "com.example.test",
        shortLabel = "Test"
    )

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
        useCase = LaunchShortcutUseCase(shortcutLauncherService)
    }

    // ===========================================
    // HAPPY PATH TESTS
    // ===========================================

    @Test
    fun `execute with valid shortcut returns Success`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } just runs

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Success::class.java)
        verify(exactly = 1) { shortcutLauncherService.startShortcut(shortcutInfo) }
    }

    @Test
    fun `execute calls service with correct shortcut`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } just runs

        useCase.execute(shortcutInfo)

        verify { shortcutLauncherService.startShortcut(shortcutInfo) }
    }

    // ===========================================
    // NULL SHORTCUT TESTS
    // ===========================================

    @Test
    fun `execute with null shortcut returns ShortcutNull error`() {
        val result = useCase.execute(null)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        assertThat((result as LaunchShortcutUseCase.Result.Failure).error).isEqualTo(LaunchShortcutUseCase.Error.ShortcutNull)
    }

    @Test
    fun `execute with null shortcut does not call service`() {
        useCase.execute(null)

        verify(exactly = 0) { shortcutLauncherService.startShortcut(any()) }
        verify(exactly = 0) { shortcutLauncherService.isAvailable() }
    }

    // ===========================================
    // SERVICE UNAVAILABLE TESTS
    // ===========================================

    @Test
    fun `execute with unavailable service returns ServiceUnavailable error`() {
        every { shortcutLauncherService.isAvailable() } returns false

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        assertThat((result as LaunchShortcutUseCase.Result.Failure).error).isEqualTo(LaunchShortcutUseCase.Error.ServiceUnavailable)
    }

    @Test
    fun `execute with unavailable service does not attempt launch`() {
        every { shortcutLauncherService.isAvailable() } returns false

        useCase.execute(shortcutInfo)

        verify(exactly = 0) { shortcutLauncherService.startShortcut(any()) }
    }

    @Test
    fun `execute checks service availability before launching`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } just runs

        useCase.execute(shortcutInfo)

        verify { shortcutLauncherService.isAvailable() }
    }

    // ===========================================
    // LAUNCH FAILURE TESTS
    // ===========================================

    @Test
    fun `execute with ShortcutLaunchException returns LaunchFailed error`() {
        val exception = ShortcutLaunchException("App not installed")
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws exception

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertThat(failure.error).isInstanceOf(LaunchShortcutUseCase.Error.LaunchFailed::class.java)
        assertThat((failure.error as LaunchShortcutUseCase.Error.LaunchFailed).cause).isEqualTo(exception)
    }

    @Test
    fun `execute with nested cause preserves exception chain`() {
        val rootCause = IllegalStateException("Activity not found")
        val exception = ShortcutLaunchException("Launch failed", rootCause)
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws exception

        val result = useCase.execute(shortcutInfo)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        val launchError = failure.error as LaunchShortcutUseCase.Error.LaunchFailed
        assertThat(launchError.cause.cause).isEqualTo(rootCause)
    }

    // ===========================================
    // UNKNOWN ERROR TESTS
    // ===========================================

    @Test
    fun `execute with RuntimeException returns Unknown error`() {
        val exception = RuntimeException("Something unexpected")
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws exception

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertThat(failure.error).isInstanceOf(LaunchShortcutUseCase.Error.Unknown::class.java)
        assertThat((failure.error as LaunchShortcutUseCase.Error.Unknown).cause).isEqualTo(exception)
    }

    @Test
    fun `execute with OutOfMemoryError returns Unknown error`() {
        val error = OutOfMemoryError("No memory left")
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws error

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        assertThat((result as LaunchShortcutUseCase.Result.Failure).error).isInstanceOf(LaunchShortcutUseCase.Error.Unknown::class.java)
    }

    @Test
    fun `execute with SecurityException returns Unknown error`() {
        val exception = SecurityException("Permission denied")
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws exception

        val result = useCase.execute(shortcutInfo)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertThat(failure.error).isInstanceOf(LaunchShortcutUseCase.Error.Unknown::class.java)
        assertThat((failure.error as LaunchShortcutUseCase.Error.Unknown).cause).isEqualTo(exception)
    }

    // ===========================================
    // EXECUTION ORDER TESTS
    // ===========================================

    @Test
    fun `execute validates shortcut before checking service`() {
        val result = useCase.execute(null)

        val failure = result as LaunchShortcutUseCase.Result.Failure
        assertThat(failure.error).isEqualTo(LaunchShortcutUseCase.Error.ShortcutNull)
        verify(exactly = 0) { shortcutLauncherService.isAvailable() }
    }

    @Test
    fun `execute checks service availability before attempting launch`() {
        every { shortcutLauncherService.isAvailable() } returns false

        useCase.execute(shortcutInfo)

        verify(exactly = 1) { shortcutLauncherService.isAvailable() }
        verify(exactly = 0) { shortcutLauncherService.startShortcut(any()) }
    }

    // ===========================================
    // RESULT TYPE TESTS
    // ===========================================

    @Test
    fun `Success is a singleton object`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } just runs

        val result1 = useCase.execute(shortcutInfo)
        val result2 = useCase.execute(shortcutInfo)

        assertThat(result1 === result2).isTrue()
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
            assertThat(failure.error).isEqualTo(error)
        }
    }

    // ===========================================
    // EDGE CASE TESTS
    // ===========================================

    @Test
    fun `execute handles service becoming unavailable between checks gracefully`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } throws ShortcutLaunchException("Service died")

        val result = useCase.execute(shortcutInfo)

        assertThat(result).isInstanceOf(LaunchShortcutUseCase.Result.Failure::class.java)
        assertThat((result as LaunchShortcutUseCase.Result.Failure).error).isInstanceOf(LaunchShortcutUseCase.Error.LaunchFailed::class.java)
    }

    @Test
    fun `execute is idempotent for same shortcut`() {
        every { shortcutLauncherService.isAvailable() } returns true
        every { shortcutLauncherService.startShortcut(shortcutInfo) } just runs

        val result1 = useCase.execute(shortcutInfo)
        val result2 = useCase.execute(shortcutInfo)
        val result3 = useCase.execute(shortcutInfo)

        assertThat(result1).isInstanceOf(LaunchShortcutUseCase.Result.Success::class.java)
        assertThat(result2).isInstanceOf(LaunchShortcutUseCase.Result.Success::class.java)
        assertThat(result3).isInstanceOf(LaunchShortcutUseCase.Result.Success::class.java)
        verify(exactly = 3) { shortcutLauncherService.startShortcut(shortcutInfo) }
    }
}
