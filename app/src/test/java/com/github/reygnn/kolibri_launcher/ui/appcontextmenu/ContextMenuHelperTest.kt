package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MockK-based JVM tests for [ContextMenuHelper]. The helper sits between
 * Home/AppDrawer Fragments and the FragmentManager — per Rule 10 it was
 * extracted to allow exactly this kind of test (the audit's §3.1 finding).
 *
 * FragmentManager + DialogFragment are mocked directly. No Robolectric:
 * the helper does not touch view inflation or lifecycle, only manager APIs
 * and a Fragment factory call.
 *
 * For show() the [AppContextMenuDialogFragment.Companion.newInstance]
 * factory is stubbed via [mockkObject] so we can assert factory + show
 * ordering without instantiating a real BottomSheetDialogFragment.
 */
class ContextMenuHelperTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fragmentManager: FragmentManager

    private val sampleApp = AppInfo(
        originalName = "Test App",
        displayName = "Test App",
        packageName = "com.example.test",
        className = "com.example.test.MainActivity",
    )

    @Before
    fun setUp() {
        fragmentManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        // Companion gets mocked per-test; defensive unmock here for safety.
        unmockkObject(AppContextMenuDialogFragment.Companion)
    }

    // ------------------------------------------------------------------------
    // dismiss()
    // ------------------------------------------------------------------------

    @Test
    fun `dismiss is a no-op when no fragment is registered for the tag`() {
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns null

        ContextMenuHelper.dismiss(fragmentManager)

        verify { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) }
    }

    @Test
    fun `dismiss calls dismissAllowingStateLoss when found fragment is a DialogFragment`() {
        val dialog = mockk<DialogFragment>(relaxed = true)
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns dialog

        ContextMenuHelper.dismiss(fragmentManager)

        verify { dialog.dismissAllowingStateLoss() }
    }

    @Test
    fun `dismiss does not dismiss when found fragment is not a DialogFragment`() {
        // Defensive against tag collisions: the is-DialogFragment check
        // guards against a non-dialog fragment that happened to use the
        // same tag. Removing the check would risk a ClassCastException
        // (or, with the helper's outer catch, a silently swallowed error).
        val plainFragment = mockk<Fragment>(relaxed = true)
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns plainFragment

        ContextMenuHelper.dismiss(fragmentManager)

        // No interactions on the fragment beyond the type check.
        verify(exactly = 0) { plainFragment.toString() }
    }

    @Test
    fun `dismiss swallows IllegalStateException from dismissAllowingStateLoss`() {
        // dismissAllowingStateLoss can throw IllegalStateException if the
        // FragmentManager is in a saved state. Legitimate catch (Rule 11).
        val dialog = mockk<DialogFragment>(relaxed = true)
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns dialog
        every { dialog.dismissAllowingStateLoss() } throws IllegalStateException("not added")

        // Must not propagate.
        ContextMenuHelper.dismiss(fragmentManager)
    }

    // ------------------------------------------------------------------------
    // show()
    // ------------------------------------------------------------------------

    @Test
    fun `show creates and shows a dialog when none is registered`() {
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns null

        val newDialog = mockk<AppContextMenuDialogFragment>(relaxed = true)
        mockkObject(AppContextMenuDialogFragment.Companion)
        every { AppContextMenuDialogFragment.newInstance(any(), any(), any()) } returns newDialog

        ContextMenuHelper.show(
            fragmentManager,
            sampleApp,
            MenuContext.HOME_SCREEN,
            hasUsage = true,
        )

        verify {
            AppContextMenuDialogFragment.newInstance(sampleApp, MenuContext.HOME_SCREEN, true)
        }
        verify { newDialog.show(fragmentManager, AppContextMenuDialogFragment.TAG) }
    }

    @Test
    fun `show dismisses an existing dialog before creating the new one`() {
        val existing = mockk<DialogFragment>(relaxed = true)
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns existing

        val newDialog = mockk<AppContextMenuDialogFragment>(relaxed = true)
        mockkObject(AppContextMenuDialogFragment.Companion)
        every { AppContextMenuDialogFragment.newInstance(any(), any(), any()) } returns newDialog

        ContextMenuHelper.show(
            fragmentManager,
            sampleApp,
            MenuContext.APP_DRAWER,
            hasUsage = false,
        )

        verifyOrder {
            fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG)
            existing.dismissAllowingStateLoss()
            AppContextMenuDialogFragment.newInstance(sampleApp, MenuContext.APP_DRAWER, false)
            newDialog.show(fragmentManager, AppContextMenuDialogFragment.TAG)
        }
    }

    @Test
    fun `show swallows IllegalStateException from dialog show`() {
        // dialog.show can throw IllegalStateException after onSaveInstanceState
        // (the same FragmentManager-saved-state edge case as dismiss).
        every { fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG) } returns null

        val newDialog = mockk<AppContextMenuDialogFragment>(relaxed = true)
        mockkObject(AppContextMenuDialogFragment.Companion)
        every { AppContextMenuDialogFragment.newInstance(any(), any(), any()) } returns newDialog
        every { newDialog.show(any<FragmentManager>(), any<String>()) } throws
            IllegalStateException("activity has been destroyed")

        // Must not propagate.
        ContextMenuHelper.show(fragmentManager, sampleApp, MenuContext.HOME_SCREEN)
    }
}
