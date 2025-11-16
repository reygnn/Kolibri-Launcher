package com.github.reygnn.kolibri_launcher.ui.colorcustomization

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.AttrRes
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.databinding.DialogColorCustomizationBinding
import com.github.reygnn.kolibri_launcher.databinding.ItemColorSwatchBinding
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ColorCustomizationDialogFragment : DialogFragment() {

    private val viewModel: LauncherViewModel by activityViewModels()
    private var _binding: DialogColorCustomizationBinding? = null
    private val binding get() = _binding!!

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
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            val params = window.attributes
            params.y = 200
            window.attributes = params
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Logik zusammengefasst
        setupShadowSwitch()
        setupPalettes()
        observeChanges()
        setupDragListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        val dragHandle = binding.dragHandle
        val window = dialog?.window ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { v, event ->
            val layoutParams = window.attributes
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    window.attributes = layoutParams
                    true
                }
                else -> false
            }
        }
    }

    private fun setupShadowSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            val isEnabled = viewModel.settingsManager.textShadowEnabledFlow.first()
            binding.switchShadow.isChecked = isEnabled

            binding.switchShadow.setOnCheckedChangeListener { _, isChecked ->
                viewModel.onSetTextShadowEnabled(isChecked)
            }
        }
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

            if (color == 0) { // "Auto"-Knopf
                autoIcon.isVisible = true
                val backgroundColor = requireContext().getColor(R.color.material_dynamic_neutral90)
                cardView.setCardBackgroundColor(backgroundColor)
                val iconTintColor = getContrastingColor(backgroundColor)
                autoIcon.imageTintList = ColorStateList.valueOf(iconTintColor)
            } else { // Echter Farbknopf
                autoIcon.isVisible = false
                cardView.setCardBackgroundColor(color)
            }

            cardView.setOnClickListener {
                onColorSelected(color)
            }

            container.addView(swatchBinding.root)
            swatchMap[color] = cardView
        }
    }

    /**
     * Startet die Beobachtung beider Farb-Flows.
     */
    private fun observeChanges() {
        // 1. Textfarbe beobachten
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsManager.textColorFlow.collect { selectedColor ->
                updateSelectedSwatchUI(selectedColor, textSwatchViews)
            }
        }

        // 2. Chip-Hintergrundfarbe beobachten
        viewLifecycleOwner.lifecycleScope.launch {
            // (Setzt voraus, dass chipBackgroundColorFlow im SettingsManager existiert)
            viewModel.settingsManager.chipBackgroundColorFlow.collect { selectedColor ->
                updateSelectedSwatchUI(selectedColor, chipBgSwatchViews)
            }
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

    private fun getContrastingColor(backgroundColor: Int): Int {
        val luminance = Color.luminance(backgroundColor)
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }

    private fun getAvailableColors(): List<Int> {
        val colors = mutableListOf<Int>()

        // 1. Automatisch (Reset-Button)
        colors.add(0)

        // 2. Material You-Farben aus dem App-Theme
        val themeColors = listOf(
            R.attr.colorPrimary, R.attr.colorPrimaryContainer, R.attr.colorOnPrimary,
            R.attr.colorOnPrimaryContainer, R.attr.colorSecondary, R.attr.colorSecondaryContainer,
            R.attr.colorOnSecondaryContainer, R.attr.colorTertiary, R.attr.colorTertiaryContainer,
            R.attr.colorOnTertiaryContainer, R.attr.colorOnSurface, R.attr.colorOnSurfaceVariant
        )

        themeColors.forEach { colorAttr ->
            colors.add(getThemeColor(requireContext(), colorAttr))
        }

        // 3. Standardfarben
        colors.add(Color.WHITE)
        colors.add(Color.BLACK)

        return colors.distinct()
    }

    private fun getThemeColor(context: Context, @AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(attrRes, typedValue, true)) {
            return typedValue.data
        }
        return Color.MAGENTA // Fallback
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        textSwatchViews.clear()
        chipBgSwatchViews.clear()
    }
}