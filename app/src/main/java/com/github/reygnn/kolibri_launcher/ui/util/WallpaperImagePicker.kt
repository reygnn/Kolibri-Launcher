package com.github.reygnn.kolibri_launcher.ui.util

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * =====================================================================================
 * ARCHITECTURAL NOTE: Single source of truth for image picking across the app
 * =====================================================================================
 *
 * This helper centralizes the Activity Result contract used for picking images —
 * both for setting a single wallpaper (MainActivity) and for adding a layer in
 * multi-layer edit mode (HomeFragment). Previously these two call sites used
 * DIFFERENT contracts, which produced a subtle but annoying UX asymmetry:
 *
 *   - "Wallpaper wählen" used `PickVisualMedia()` (the modern Android 13+ photo
 *     picker). It presents a curated, privacy-friendly grid of system images
 *     from Google Photos / Pictures / DCIM — but doesn't surface local files
 *     from Downloads or third-party storage providers.
 *
 *   - "Add Layer" in edit mode used `GetContent()` (the classic Storage Access
 *     Framework picker). It shows every content source the user has installed,
 *     including Downloads, file managers, cloud drives, etc.
 *
 * Users picking wallpapers couldn't reach their Downloads folder; users adding
 * layers could. That inconsistency was historical drift, not deliberate design.
 *
 * **The Choice: GetContent()**
 * We standardize on [ActivityResultContracts.GetContent] because:
 *   - Layer-add users already relied on being able to pick from non-library
 *     sources (Downloads, file managers). Removing that would be a regression.
 *   - Users who only want the modern photo-picker UX get it automatically on
 *     Android 13+ when Google Photos is set as the default content picker.
 *   - Wallpapers are copied to internal storage immediately via
 *     [com.github.reygnn.kolibri_launcher.data.WallpaperFileManager.copyToInternal],
 *     so the source URI's long-term accessibility doesn't matter — the privacy
 *     advantage of PickVisualMedia (no persistable read permission granted)
 *     does not buy us anything here.
 *
 * **Why This Is a Helper, Not a Reusable Class**
 * Activity Result launchers MUST be registered before `onCreate` / before view
 * creation. That makes them inherently per-host (Activity / Fragment) state.
 * We can't hide them behind an abstraction that owns the launcher itself —
 * the registration has to happen in each host's lifecycle.
 *
 * What we CAN share is (a) the contract instance, and (b) the launch argument.
 * If we ever migrate to a different picker API, we flip it here and both call
 * sites follow.
 *
 * **Usage Pattern**
 * ```
 * private val picker = registerForActivityResult(
 *     WallpaperImagePicker.contract()
 * ) { uri -> uri?.let { viewModel.onPicked(it) } }
 *
 * // later:
 * WallpaperImagePicker.launch(picker)
 * ```
 * =====================================================================================
 */
object WallpaperImagePicker {

    /**
     * The Activity Result contract to register with `registerForActivityResult`.
     * Both wallpaper and layer picking use the same contract to guarantee a
     * unified picker UI.
     */
    fun contract(): ActivityResultContract<String, android.net.Uri?> =
        ActivityResultContracts.GetContent()

    /**
     * Launches the picker filtered to image content. The launcher must have
     * been registered with the contract returned by [contract].
     */
    fun launch(launcher: ActivityResultLauncher<String>) {
        launcher.launch(MIME_TYPE_IMAGE)
    }

    private const val MIME_TYPE_IMAGE = "image/*"
}