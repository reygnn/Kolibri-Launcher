package com.github.reygnn.kolibri_launcher

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.*
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.AttrRes
import androidx.core.graphics.drawable.toDrawable
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
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)   // nicht dimmen.
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            val params = window.attributes
            params.y = 100
            window.attributes = params
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupShadowSwitch()
        setupColorPalette()
        observeColorChanges()
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

    private fun setupColorPalette() {
        val colors = getAvailableColors()
        val swatchBinding = ItemColorSwatchBinding.inflate(layoutInflater, binding.colorPaletteContainer, false)

        colors.distinct().forEach { color ->
            val swatchBinding = ItemColorSwatchBinding.inflate(layoutInflater, binding.colorPaletteContainer, false)
            val cardView = swatchBinding.colorSwatchCard
            val autoIcon = swatchBinding.autoIcon

            if (color == 0) { // Spezialfall "Automatisch"
                autoIcon.isVisible = true
                cardView.setCardBackgroundColor(requireContext().getColor(R.color.material_dynamic_neutral90))
            } else {
                autoIcon.isVisible = false
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
/*    private fun getAvailableColors(): List<Int> {
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
    }*/

    private fun getAvailableColors(): List<Int> {
        val colors = mutableListOf<Int>()

        // 1. Automatisch (Reset-Button)
        colors.add(0)

        // 2. NEU: Die wichtigsten Material You-Farben aus dem aktuellen App-Theme auslesen
        val themeColors = listOf(
            // Primäre Farben (Hauptakzent)
            R.attr.colorPrimary,
            R.attr.colorPrimaryContainer,
            R.attr.colorOnPrimary,
            R.attr.colorOnPrimaryContainer,

            // Sekundäre Farben
            R.attr.colorSecondary,
            R.attr.colorSecondaryContainer,
            R.attr.colorOnSecondaryContainer,

            // Tertiäre Farben
            R.attr.colorTertiary,
            R.attr.colorTertiaryContainer,
            R.attr.colorOnTertiaryContainer,

            // Oberflächenfarben (Hintergründe, Text)
            R.attr.colorOnSurface,
            R.attr.colorOnSurfaceVariant
        )

        // Jede Farbe aus der Liste der Attribute auflösen
        themeColors.forEach { colorAttr ->
            colors.add(getThemeColor(requireContext(), colorAttr))
        }

        // 3. Standardfarben bleiben als Fallback
        colors.add(Color.WHITE)
        colors.add(Color.BLACK)

        // Duplikate entfernen und zurückgeben
        return colors.distinct()
    }

    // Hilfsfunktion, um eine Farbe aus einem Theme-Attribut aufzulösen
    private fun getThemeColor(context: Context, @AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        // theme.resolveAttribute gibt 'true' zurück, wenn das Attribut gefunden wurde
        if (context.theme.resolveAttribute(attrRes, typedValue, true)) {
            return typedValue.data
        }
        // Fallback-Farbe, falls das Attribut nicht gefunden wird (sollte nicht passieren)
        return Color.MAGENTA
    }
}