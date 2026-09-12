package com.github.reygnn.nyx_launcher.home

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.launcher.common.ui.configureLivePreviewWindow
import com.github.reygnn.launcher.common.ui.enableDialogDrag
import com.github.reygnn.launcher.common.ui.fadeTo
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.settings.SettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The live-preview customization sheet (mirrors Kolibri's colour/layout dialogs):
 * a bottom-anchored card, opened by long-pressing empty home space, hosting the
 * quick, visually-previewable settings — wallpaper dim (scrim), monochrome icons,
 * and choose/remove wallpaper — plus a hand-off button to the full Settings.
 *
 * The premium feel: the dialog window is transparent with no dim behind
 * ([configureLivePreviewWindow]); dragging the scrim slider fades the card to
 * alpha 0 ([fadeTo]) so the whole home dims live behind it, then fades back on
 * release. Every change writes straight to the DataStore-backed port/repo, and
 * MainActivity — observing the same flows — re-renders the home in lock-step (no
 * preview buffer, no debounce; a `fromUser` guard prevents write-back loops).
 */
@AndroidEntryPoint
class NyxCustomizationDialog : DialogFragment() {

    @Inject lateinit var wallpaperDisplaySettings: WallpaperDisplaySettings
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var wallpaperImageSetter: NyxWallpaperImageSetter

    private val pickWallpaperImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            // Dismiss after a pick so the sheet gets out of the way (Kolibri parity).
            if (uri != null) {
                lifecycleScope.launch { wallpaperImageSetter.setFromUri(uri) }
                dismiss()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_nyx_customization, container, false)

    override fun onStart() {
        super.onStart()
        // Bottom sheet, no dim behind → the home shows through when the card fades.
        val yOffset = (16 * resources.displayMetrics.density).roundToInt()
        configureLivePreviewWindow(widthFraction = 0.94, yOffset = yOffset)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val card = view.findViewById<View>(R.id.card_root)
        val scrimSlider = view.findViewById<Slider>(R.id.slider_scrim)
        val monochromeSwitch = view.findViewById<MaterialSwitch>(R.id.switch_monochrome)

        // Drag handle moves the whole sheet up/down so it can be shifted off a
        // spot the user wants to see (Kolibri parity).
        enableDialogDrag(view.findViewById(R.id.drag_handle), card)

        // Scrim: write on drag, fade the card away while tracking so the home dims live.
        scrimSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                lifecycleScope.launch { wallpaperDisplaySettings.setWallpaperScrimAlpha(value) }
            }
        }
        scrimSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                card.fadeTo(0f)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                card.fadeTo(1f)
                slider.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        })

        monochromeSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) {
                lifecycleScope.launch { preferences.setMonochromeIcons(isChecked) }
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_choose_wallpaper).setOnClickListener {
            pickWallpaperImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        view.findViewById<MaterialButton>(R.id.btn_clear_wallpaper).setOnClickListener {
            lifecycleScope.launch { wallpaperImageSetter.clear() }
        }
        val host = activity as? MainActivity
        view.findViewById<MaterialButton>(R.id.btn_edit_wallpaper).apply {
            // Editing needs a wallpaper to edit; hide the entry when none is set.
            visibility = if (host?.hasWallpaper() == true) View.VISIBLE else View.GONE
            setOnClickListener {
                host?.enterWallpaperEditMode()
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_all_settings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }

        // Seed controls from current state and keep them in sync with external
        // changes; value-equality guards avoid feeding our own writes back.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    wallpaperDisplaySettings.wallpaperScrimAlphaStateFlow.collect { alpha ->
                        val snapped = snapScrim(alpha)
                        if (scrimSlider.value != snapped) scrimSlider.value = snapped
                    }
                }
                launch {
                    preferences.monochromeIcons().collect { enabled ->
                        if (monochromeSwitch.isChecked != enabled) monochromeSwitch.isChecked = enabled
                    }
                }
            }
        }
    }

    /** Coerce a stored alpha into the slider's [min..max] grid (step 0.05). */
    private fun snapScrim(alpha: Float): Float {
        val min = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
        val max = AppConstants.WALLPAPER_SCRIM_ALPHA_MAX
        val step = AppConstants.WALLPAPER_SCRIM_ALPHA_STEP
        val clamped = alpha.coerceIn(min, max)
        return (min + (((clamped - min) / step).roundToInt() * step)).coerceIn(min, max)
    }

    companion object {
        const val TAG = "NyxCustomizationDialog"
    }
}
