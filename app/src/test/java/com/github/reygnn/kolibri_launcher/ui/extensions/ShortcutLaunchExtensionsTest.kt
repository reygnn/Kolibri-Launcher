package com.github.reygnn.kolibri_launcher.ui.extensions

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.github.reygnn.kolibri_launcher.ui.util.LauncherShortcutParcelable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test

/**
 * Pins the UI glue in [handleShortcutLaunch]: the contract that a failed
 * shortcut launch — of ANY kind, including a bundle that can't be parsed —
 * surfaces to the user via [LauncherViewModel.onAppInfoError], while a
 * successful launch stays silent. The [LaunchShortcutUseCase] result routing
 * was untested; a regression that dropped the error toast (or fired it on
 * success) would leave a broken shortcut launch giving no feedback.
 *
 * Pure JVM: the [Fragment] receiver is never touched inside the extension, so a
 * bare mock stands in for it. [TimberRule] neutralises the DEBUG throw in the
 * `silentError` failure branches (Rule 9).
 */
class ShortcutLaunchExtensionsTest {

    @get:Rule
    val timberRule = TimberRule()

    private val fragment = mockk<Fragment>()
    private val viewModel = mockk<LauncherViewModel>(relaxed = true)
    private val useCase = mockk<LaunchShortcutUseCase>()
    private val bundle = mockk<Bundle>()

    private fun bundleHasNoShortcut() {
        every { bundle.getParcelable(any<String>(), LauncherShortcutParcelable::class.java) } returns null
    }

    @Test
    fun `successful launch does not show an error toast`() {
        bundleHasNoShortcut()
        every { useCase.execute(any()) } returns LaunchShortcutUseCase.Result.Success

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify(exactly = 0) { viewModel.onAppInfoError() }
    }

    @Test
    fun `ShortcutNull failure shows an error toast`() {
        bundleHasNoShortcut()
        every { useCase.execute(any()) } returns
            LaunchShortcutUseCase.Result.Failure(LaunchShortcutUseCase.Error.ShortcutNull)

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify(exactly = 1) { viewModel.onAppInfoError() }
    }

    @Test
    fun `ServiceUnavailable failure shows an error toast`() {
        bundleHasNoShortcut()
        every { useCase.execute(any()) } returns
            LaunchShortcutUseCase.Result.Failure(LaunchShortcutUseCase.Error.ServiceUnavailable)

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify(exactly = 1) { viewModel.onAppInfoError() }
    }

    @Test
    fun `LaunchFailed failure shows an error toast`() {
        bundleHasNoShortcut()
        every { useCase.execute(any()) } returns
            LaunchShortcutUseCase.Result.Failure(LaunchShortcutUseCase.Error.LaunchFailed(RuntimeException("boom")))

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify(exactly = 1) { viewModel.onAppInfoError() }
    }

    @Test
    fun `Unknown failure shows an error toast`() {
        bundleHasNoShortcut()
        every { useCase.execute(any()) } returns
            LaunchShortcutUseCase.Result.Failure(LaunchShortcutUseCase.Error.Unknown(RuntimeException("boom")))

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify(exactly = 1) { viewModel.onAppInfoError() }
    }

    @Test
    fun `a bad parcelable in the bundle is swallowed and routed as a failure, never crashes`() {
        // extractShortcutFromBundle catches the throw internally and collapses to
        // a null shortcut, which the use case reports as a ShortcutNull failure.
        every {
            bundle.getParcelable(any<String>(), LauncherShortcutParcelable::class.java)
        } throws RuntimeException("bad parcelable")
        every { useCase.execute(null) } returns
            LaunchShortcutUseCase.Result.Failure(LaunchShortcutUseCase.Error.ShortcutNull)

        fragment.handleShortcutLaunch(bundle, viewModel, useCase)

        verify { useCase.execute(null) }
        verify(exactly = 1) { viewModel.onAppInfoError() }
    }
}
