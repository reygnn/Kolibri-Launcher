package com.github.reygnn.kolibri_launcher

import android.app.WallpaperManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.databinding.DialogColorCustomizationBinding
import com.github.reygnn.kolibri_launcher.databinding.ItemColorSwatchBinding
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ColorCustomizationDialogFragment : DialogFragment() {

    // Wir teilen uns das ViewModel mit der Activity und dem HomeFragment
    private val viewModel: HomeViewModel by activityViewModels()
    private var _binding: DialogColorCustomizationBinding? = null
    private val binding get() = _binding!!

    // Hält eine Referenz auf alle Farbkarten, um den "selektiert"-Zustand zu verwalten
    private val colorSwatchViews = mutableMapOf<Int, MaterialCardView>()

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
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupShadowSwitch()
        setupColorPalette()
        observeColorChanges()
    }

    private fun setupShadowSwitch() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Den Switch auf den aktuell gespeicherten Wert setzen
            val isEnabled = viewModel.settingsManager.textShadowEnabledFlow.first()
            binding.switchShadow.isChecked = isEnabled

            // Bei Änderungen direkt das ViewModel informieren
            binding.switchShadow.setOnCheckedChangeListener { _, isChecked ->
                viewModel.onSetTextShadowEnabled(isChecked)
            }
        }
    }

    private fun setupColorPalette() {
        val colors = getAvailableColors()
        val inflater = LayoutInflater.from(requireContext())

        // Für jede Farbe eine "Swatch"-View erstellen und zur Palette hinzufügen
        colors.distinct().forEach { color ->
            val swatchBinding = ItemColorSwatchBinding.inflate(inflater, binding.colorPaletteContainer, false)
            val cardView = swatchBinding.colorSwatchCard
            // GEÄNDERT: von autoIcon zu autoText
            val autoText = swatchBinding.autoText

            if (color == 0) { // Spezialfall "Automatisch"
                // GEÄNDERT: TextView sichtbar machen
                autoText.isVisible = true
                cardView.setCardBackgroundColor(requireContext().getColor(R.color.material_dynamic_neutral90))
            } else {
                // GEÄNDERT: TextView unsichtbar machen
                autoText.isVisible = false
                cardView.setCardBackgroundColor(color)
            }

            // Klick-Listener, der das ViewModel informiert
            cardView.setOnClickListener {
                viewModel.onSetTextColor(color)
            }

            binding.colorPaletteContainer.addView(swatchBinding.root)
            colorSwatchViews[color] = cardView // Zur Map hinzufügen für spätere Updates
        }
    }

    // Beobachtet die Farbeinstellung und hebt die aktuell gewählte Farbe hervor
    private fun observeColorChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsManager.textColorFlow.collect { selectedColor ->
                updateSelectedColorUI(selectedColor)
            }
        }
    }

    private fun updateSelectedColorUI(selectedColor: Int) {
        val selectedColorValue = ColorStateList.valueOf(requireContext().getColor(R.color.color_primary))
        val deselectedColorValue = ColorStateList.valueOf(Color.TRANSPARENT)

        colorSwatchViews.forEach { (color, cardView) ->
            if (color == selectedColor) {
                cardView.strokeColor = selectedColorValue.defaultColor
            } else {
                cardView.strokeColor = deselectedColorValue.defaultColor
            }
        }
    }

    // Sammelt alle Farben, die wir dem Nutzer anbieten wollen
    private fun getAvailableColors(): List<Int> {
        val wallpaperManager = WallpaperManager.getInstance(requireContext())
        val wallpaperColors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)

        val colors = mutableListOf<Int>()
        // 1. Automatisch (Reset-Button)
        colors.add(0)

        // 2. Material You Farben (falls vorhanden)
        wallpaperColors?.let {
            colors.add(it.primaryColor.toArgb())
            it.secondaryColor?.let { c -> colors.add(c.toArgb()) }
            it.tertiaryColor?.let { c -> colors.add(c.toArgb()) }
        }

        // 3. Standardfarben
        colors.add(Color.WHITE)
        colors.add(Color.BLACK)

        return colors
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        colorSwatchViews.clear()
    }
}