package com.github.reygnn.kolibri_launcher.ui.colorcustomization

import android.content.res.ColorStateList
import android.graphics.Color
import com.github.reygnn.kolibri_launcher.ui.util.configureBottomSheetWindow
import com.github.reygnn.kolibri_launcher.ui.util.enableDialogDrag
import com.github.reygnn.kolibri_launcher.ui.util.resolveThemeColor
import com.github.reygnn.kolibri_launcher.ui.util.tintTextViews
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.databinding.DialogColorCustomizationBinding
import com.github.reygnn.kolibri_launcher.databinding.ItemColorSwatchBinding
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground
import com.github.reygnn.kolibri_launcher.ui.util.toSurface
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ColorCustomizationDialogFragment : DialogFragment() {

    private val viewModel: LauncherViewModel by activityViewModels()
    private var _binding: DialogColorCustomizationBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var resolveWallpaperSurfaceUseCase: ResolveWallpaperSurfaceUseCase

    // Zwei separate Listen für die UI-Zustände der Paletten
    private val textSwatchViews = mutableMapOf<Int, MaterialCardView>()
    private val chipBgSwatchViews = mutableMapOf<Int, MaterialCardView>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogColorCustomizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        configureBottomSheetWindow(widthFraction = 0.90, yOffset = 200)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Logik zusammengefasst
        setupShadowSwitch()
        setupScrimSlider()
        setupPalettes()
        observeChanges()
        observeWallpaperSurface()
        enableDialogDrag(binding.dragHandle, binding.root)
    }

    /**
     * Wallpaper-following surface. Mirrors the long-press menu +
     * Layout & Size dialog: same `LuminanceClassification`, same
     * `app_drawer_surface_light/_dark` colour pair, same WCAG-derived
     * foreground. Differs from those two in the tint anchor — this
     * dialog uses a `<shape>` drawable (`bottom_sheet_background`) as
     * its root background, so the rounded corners come from the
     * drawable and we tint it via `backgroundTintList` instead of
     * setting a raw colour (which would wipe the corner radius).
     * Colour swatches are intentionally not tinted — they show real
     * choosable colours.
     */
    private fun observeWallpaperSurface() {
        // collectOnStarted (repeatOnLifecycle STARTED) rather than a raw
        // lifecycleScope.launch, so the flow doesn't keep running while the
        // dialog is only STOPPED — matching the rest of the codebase.
        collectOnStarted(
            flow = resolveWallpaperSurfaceUseCase(),
            errorTag = "wallpaperSurface",
            coroutineContext = Dispatchers.Main,
        ) { classification ->
            if (_binding == null) return@collectOnStarted
            val surface = classification.toSurface(requireContext())
            val color = surface.color
            val fg = surface.foregroundColor()
            binding.root.backgroundTintList = ColorStateList.valueOf(color)
            binding.dragHandle.backgroundTintList = ColorStateList.valueOf(fg)
            // Sweep only the label-bearing siblings of the swatch
            // containers; the swatch containers themselves stay
            // untouched so colour previews keep their real colours.
            applyForegroundColorToLabels(binding.root, fg)
        }
    }

    /**
     * Walks the dialog tree and tints every [TextView] outside the
     * swatch containers. The swatch containers (`color_palette_container`
     * and `chip_bg_palette_container`) hold colour-preview cards whose
     * background colour is the actual swatch — we must not overwrite
     * those, and there is no TextView inside them anyway. Skipping
     * them keeps the sweep cheap and future-proof against item layouts
     * that ever add a text label inside a swatch.
     */
    private fun applyForegroundColorToLabels(root: ViewGroup, color: Int) {
        root.tintTextViews(color) { child ->
            child.id == R.id.color_palette_container ||
                child.id == R.id.chip_bg_palette_container
        }
    }

    private fun setupShadowSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            val isEnabled = viewModel.isTextShadowEnabled()
            binding.switchShadow.isChecked = isEnabled

            binding.switchShadow.setOnCheckedChangeListener { _, isChecked ->
                viewModel.onSetTextShadowEnabled(isChecked)
            }
        }
    }

    /**
     * User-controlled wallpaper scrim (opt-in dim, default 0). Sits next to the
     * shadow toggle because both are text-legibility-over-wallpaper controls: the
     * outline (shadow) is the guarantee, the scrim is the escalation for extreme
     * wallpapers where the outline alone is marginal.
     */
    private fun setupScrimSlider() {
        binding.sliderWallpaperScrim.apply {
            valueFrom = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
            valueTo = AppConstants.WALLPAPER_SCRIM_ALPHA_MAX
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) viewModel.onSetWallpaperScrimAlpha(value)
            }
        }

        collectOnStarted(
            flow = viewModel.wallpaperScrimAlphaState,
            errorTag = "wallpaperScrim",
            coroutineContext = Dispatchers.Main,
        ) { alpha ->
            if (_binding == null) return@collectOnStarted
            // Snap onto the slider's step grid + range before assigning: Material
            // Slider throws IllegalArgumentException for an off-grid / out-of-range
            // value (e.g. a hand-edited backup import). App-written values are
            // always on-grid, so this is purely a robustness guard.
            val snapped = snapScrimToSliderGrid(alpha)
            if (binding.sliderWallpaperScrim.value != snapped) {
                binding.sliderWallpaperScrim.value = snapped
            }
        }
    }

    private fun snapScrimToSliderGrid(alpha: Float): Float {
        val min = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
        val max = AppConstants.WALLPAPER_SCRIM_ALPHA_MAX
        val clamped = alpha.coerceIn(min, max)
        val steps = ((clamped - min) / SCRIM_SLIDER_STEP).roundToInt()
        return (min + steps * SCRIM_SLIDER_STEP).coerceIn(min, max)
    }

    /**
     * Setzt beide Farbpaletten auf (Textfarbe und Chip-Hintergrund).
     */
    private fun setupPalettes() {
        // 1. Palette für Textfarbe füllen
        populatePalette(
            container = binding.colorPaletteContainer,
            swatchMap = textSwatchViews,
            onColorSelected = { color ->
                viewModel.onSetTextColor(color)
            }
        )

        // 2. Palette für Chip-Hintergrund füllen
        populatePalette(
            container = binding.chipBgPaletteContainer,
            swatchMap = chipBgSwatchViews,
            onColorSelected = { color ->
                viewModel.onSetChipBackgroundColor(color)
            }
        )
    }

    /**
     * Hilfsfunktion: Füllt einen beliebigen Container mit den verfügbaren Farbfeldern.
     */
    private fun populatePalette(
        container: ViewGroup,
        swatchMap: MutableMap<Int, MaterialCardView>,
        onColorSelected: (Int) -> Unit
    ) {
        val colors = getAvailableColors()

        colors.distinct().forEach { color ->
            val swatchBinding = ItemColorSwatchBinding.inflate(layoutInflater, container, false)
            val cardView = swatchBinding.colorSwatchCard
            val autoIcon = swatchBinding.autoIcon

            // AUDIT-18 F2: label each swatch for TalkBack — the auto entry reads
            // "Automatic", a colour reads its hex value. Set on the 56dp cell root
            // (below), the node that is actually focused/clicked.
            val cell = swatchBinding.root
            if (color == AppConstants.DEFAULT_TEXT_COLOR) { // "Auto"-Knopf
                autoIcon.isVisible = true
                val backgroundColor = requireContext().getColor(R.color.material_dynamic_neutral90)
                cardView.setCardBackgroundColor(backgroundColor)
                val iconTintColor = ResolvedBackground.SolidColor(backgroundColor).foregroundColor()
                autoIcon.imageTintList = ColorStateList.valueOf(iconTintColor)
                cell.contentDescription = getString(R.string.color_automatic)
            } else { // Echter Farbknopf
                autoIcon.isVisible = false
                cardView.setCardBackgroundColor(color)
                cell.contentDescription = String.format("#%06X", 0xFFFFFF and color)
            }

            // AUDIT-18 F2: click + a11y focus on the 56dp cell, not the 40dp card,
            // so the touch target meets the 48dp minimum and TalkBack focuses one
            // labelled node per swatch.
            cell.setOnClickListener {
                onColorSelected(color)
            }

            container.addView(cell)
            swatchMap[color] = cardView
        }
    }

    /**
     * Startet die Beobachtung beider Farb-Flows.
     */
    private fun observeChanges() {
        collectOnStarted(
            flow = viewModel.uiColorsState,
            errorTag = "colorChanges",
            coroutineContext = Dispatchers.Main,
        ) { colorsState ->
            updateSelectedSwatchUI(colorsState.textColor, textSwatchViews)
            updateSelectedSwatchUI(colorsState.chipBackgroundColor, chipBgSwatchViews)
        }
    }

    /**
     * Hilfsfunktion: Hebt die ausgewählte Farbe in einer beliebigen Palette hervor.
     */
    private fun updateSelectedSwatchUI(selectedColor: Int, swatchMap: Map<Int, MaterialCardView>) {
        val selectedColorValue = ColorStateList.valueOf(requireContext().getColor(R.color.color_primary))
        val deselectedColorValue = ColorStateList.valueOf(Color.TRANSPARENT)

        swatchMap.forEach { (color, cardView) ->
            if (color == selectedColor) {
                cardView.strokeColor = selectedColorValue.defaultColor
            } else {
                cardView.strokeColor = deselectedColorValue.defaultColor
            }
        }
    }

    private fun getAvailableColors(): List<Int> {
        val colors = mutableListOf<Int>()

        // 1. Automatisch (Reset-Button)
        colors.add(AppConstants.DEFAULT_TEXT_COLOR)

        // 2. Material You-Farben aus dem App-Theme
        val themeColors = listOf(
            R.attr.colorPrimary, R.attr.colorPrimaryContainer, R.attr.colorOnPrimary,
            R.attr.colorOnPrimaryContainer, R.attr.colorSecondary, R.attr.colorSecondaryContainer,
            R.attr.colorOnSecondaryContainer, R.attr.colorTertiary, R.attr.colorTertiaryContainer,
            R.attr.colorOnTertiaryContainer, R.attr.colorOnSurface, R.attr.colorOnSurfaceVariant
        )

        themeColors.forEach { colorAttr ->
            colors.add(requireContext().resolveThemeColor(colorAttr, Color.MAGENTA))
        }

        // 3. Standardfarben
        colors.add(Color.WHITE)
        colors.add(Color.BLACK)

        return colors.distinct()
    }

    override fun onDestroyView() {
        // 1. Manuelle Referenzen auf Views löschen
        // Das ist wichtig, weil die Maps Views halten, die wiederum Listener auf 'this' haben.
        textSwatchViews.clear()
        chipBgSwatchViews.clear()

        // 2. Listener vom DragHandle entfernen (optional, aber sauber)
        // Verhindert, dass der Listener noch Events feuert, während der View stirbt.
        if (_binding != null) {
            binding.dragHandle.setOnTouchListener(null)
            binding.sliderWallpaperScrim.clearOnChangeListeners()

            // Container leeren hilft dem GC zusätzlich
            binding.colorPaletteContainer.removeAllViews()
            binding.chipBgPaletteContainer.removeAllViews()
        }

        // 3. Binding nullen
        _binding = null

        super.onDestroyView()
    }

    companion object {
        // Must match the Slider's stepSize in dialog_color_customization.xml.
        private const val SCRIM_SLIDER_STEP = 0.05f
    }
}