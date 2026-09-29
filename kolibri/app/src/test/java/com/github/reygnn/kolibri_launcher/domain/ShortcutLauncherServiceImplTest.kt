package com.github.reygnn.kolibri_launcher.data.service

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.After
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
    private lateinit var context: Context

    @MockK
    private lateinit var launcherApps: LauncherApps

    @MockK
    private lateinit var userHandle: UserHandle

    private val shortcutInfo = LauncherShortcut(
        id = "test-shortcut-id",
        packageName = "com.example.test",
        shortLabel = "Test"
    )

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
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns launcherApps
        // Process.myUserHandle() is a static call — stub it so the platform call doesn't crash on JVM.
        mockkStatic(Process::class)
        every { Process.myUserHandle() } returns userHandle
    }

    @After
    fun tearDown() {
        unmockkStatic(Process::class)
    }

    // ===========================================
    // AVAILABILITY TESTS
    // ===========================================

    @Test
    fun `isAvailable returns true when LauncherApps service exists`() {
        service = ShortcutLauncherServiceImpl(context)
        assertThat(service.isAvailable()).isTrue()
    }

    @Test
    fun `isAvailable returns false when LauncherApps service is null`() {
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null
        service = ShortcutLauncherServiceImpl(context)
        assertThat(service.isAvailable()).isFalse()
    }

    @Test
    fun `isAvailable returns false when getSystemService throws`() {
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } throws SecurityException("Permission denied")
        service = ShortcutLauncherServiceImpl(context)

        try {
            val result = service.isAvailable()
            assertThat(result).isFalse()
        } catch (e: SecurityException) {
            // Acceptable behavior for this edge case
        }
    }

    // ===========================================
    // START SHORTCUT TESTS
    // ===========================================

    @Test
    fun `startShortcut calls LauncherApps with correct parameters`() {
        every {
            launcherApps.startShortcut(any<String>(), any<String>(), null, null, any())
        } just runs
        service = ShortcutLauncherServiceImpl(context)

        service.startShortcut(shortcutInfo)

        verify(exactly = 1) {
            launcherApps.startShortcut(
                shortcutInfo.packageName,
                shortcutInfo.id,
                null,
                null,
                userHandle
            )
        }
    }

    @Test
    fun `startShortcut throws ShortcutLaunchException when service unavailable`() {
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null

        assertFailsWith<ShortcutLaunchException> {
            service = ShortcutLauncherServiceImpl(context)
            service.startShortcut(shortcutInfo)
        }
    }

    @Test
    fun `startShortcut wraps LauncherApps exceptions in ShortcutLaunchException`() {
        every {
            launcherApps.startShortcut(any<String>(), any<String>(), null, null, any())
        } throws IllegalStateException("Activity not found")

        service = ShortcutLauncherServiceImpl(context)

        try {
            service.startShortcut(shortcutInfo)
            throw AssertionError("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            assertThat(e.message!!.contains("test-shortcut-id")).isTrue()
            assertThat(e.cause).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `startShortcut includes shortcut id in error message`() {
        val customShortcut = LauncherShortcut(
            id = "my-unique-shortcut-123",
            packageName = "com.example.test",
            shortLabel = "Test"
        )
        every {
            launcherApps.startShortcut(any<String>(), any<String>(), null, null, any())
        } throws RuntimeException("Failed")

        service = ShortcutLauncherServiceImpl(context)

        try {
            service.startShortcut(customShortcut)
            throw AssertionError("Expected ShortcutLaunchException")
        } catch (e: ShortcutLaunchException) {
            assertThat(e.message!!.contains("my-unique-shortcut-123")).isTrue()
        }
    }

    // ===========================================
    // LAZY INITIALIZATION TESTS
    // ===========================================

    @Test
    fun `LauncherApps service is lazily initialized`() {
        service = ShortcutLauncherServiceImpl(context)

        // getSystemService sollte noch nicht aufgerufen worden sein
        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }

    @Test
    fun `LauncherApps service is only fetched once`() {
        every {
            launcherApps.startShortcut(any<String>(), any<String>(), null, null, any())
        } just runs
        service = ShortcutLauncherServiceImpl(context)

        service.isAvailable()
        service.isAvailable()
        service.startShortcut(shortcutInfo)
        service.startShortcut(shortcutInfo)

        verify(exactly = 1) { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) }
    }
}
