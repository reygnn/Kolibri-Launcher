package com.github.reygnn.kolibri_launcher.ui.layoutcustomization

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.DialogLayoutCustomizationBinding
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.ui.util.configureBottomSheetWindow
import com.github.reygnn.kolibri_launcher.ui.util.enableDialogDrag
import com.github.reygnn.kolibri_launcher.ui.util.fadeTo
import com.github.reygnn.kolibri_launcher.ui.util.toSurface
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.github.reygnn.kolibri_launcher.ui.util.tintTextViews
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LayoutCustomizationDialogFragment : DialogFragment() {

    private val viewModel: LauncherViewModel by activityViewModels()
    private var _binding: DialogLayoutCustomizationBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var resolveWallpaperSurfaceUseCase: ResolveWallpaperSurfaceUseCase

    // ==================== Lifecycle ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            _binding = DialogLayoutCustomizationBinding.inflate(inflater, container, false)
            binding.root
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to inflate LayoutCustomizationDialog")
            null
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            configureBottomSheetWindow(widthFraction = 0.95, yOffset = 150)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to configure dialog window")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            setupControls()
            observeViewModel()
            enableDialogDrag(binding.dragHandle, binding.cardRoot)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to setup LayoutCustomizationDialog")
            dismissSafe()
        }
    }

    override fun onDestroyView() {
        try {
            if (_binding != null) {
                // GC-Optimization: Listener entfernen
                // Das durchbricht Referenz-Zyklen sofort
                binding.sliderTextSize.clearOnChangeListeners()
                binding.sliderTextSize.clearOnSliderTouchListeners()

                binding.sliderPadding.clearOnChangeListeners()
                binding.sliderPadding.clearOnSliderTouchListeners()

                binding.sliderTopMargin.clearOnChangeListeners()
                binding.sliderTopMargin.clearOnSliderTouchListeners()

                binding.dragHandle.setOnTouchListener(null)

                // Switch Listener nullen
                binding.switchBoldText.setOnCheckedChangeListener(null)

                // Toggle group listener entfernen
                binding.toggleFavoritesAlignment.clearOnButtonCheckedListeners()
            }

            _binding = null

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }


    // ==================== Controls Setup ====================

    private fun setupControls() {
        // 1. Text Size Slider konfigurieren (SSOT: AppConstants)
        binding.sliderTextSize.apply {
            valueFrom = AppConstants.LAYOUT_SCALE_MIN
            valueTo = AppConstants.LAYOUT_SCALE_MAX

            // Listener für Änderungen
            addOnChangeListener { _, value, fromUser ->
                safeRun("sliderTextSize.onChange") {
                    if (fromUser) viewModel.onSetLayoutScale(value)
                }
            }
        }

        // 2. Padding Slider konfigurieren
        binding.sliderPadding.apply {
            valueFrom = AppConstants.VERTICAL_PADDING_SCALE_MIN
            valueTo = AppConstants.VERTICAL_PADDING_SCALE_MAX

            addOnChangeListener { _, value, fromUser ->
                safeRun("sliderPadding.onChange") {
                    if (fromUser) viewModel.onSetVerticalPadding(value)
                }
            }
        }

        // 3. Top Margin Slider konfigurieren
        binding.sliderTopMargin.apply {
            valueFrom = AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN
            valueTo = AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX

            addOnChangeListener { _, value, fromUser ->
                safeRun("sliderTopMargin.onChange") {
                    if (fromUser) viewModel.onSetContentTopMargin(value)
                }
            }
        }

        // 4. Switch Listener
        binding.switchBoldText.setOnCheckedChangeListener { _, isChecked ->
            safeRun("switchBoldText.onChange") {
                viewModel.onSetFontBold(isChecked)
            }
        }

        // 5. Favorites alignment toggle group. The listener fires on every
        // selection change (not just user-initiated), so we filter via
        // `isChecked` + compare against the current ViewModel value to
        // avoid an emit-loop when programmatic selection from the
        // observer below sets the toggle to match what's already there.
        binding.toggleFavoritesAlignment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            safeRun("toggleFavoritesAlignment.onChange") {
                val alignment = checkedIdToAlignment(checkedId) ?: return@safeRun
                if (alignment != viewModel.favoritesAlignmentState.value) {
                    viewModel.onSetFavoritesAlignment(alignment)
                }
            }
        }

        // 6. Reset Button
        binding.btnReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            safeRun("btnReset.onClick") {
                viewModel.onResetLayoutSettings()
            }
        }

        // UX: Fade-Effekte
        setupFadeOnTouch(binding.sliderTextSize)
        setupFadeOnTouch(binding.sliderPadding)
        setupFadeOnTouch(binding.sliderTopMargin)
    }

// ==================== UX Animationen ====================

    /**
     * Setzt den Fade-Effekt für Slider auf.
     */
    private fun setupFadeOnTouch(slider: com.google.android.material.slider.Slider) {
        slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // User berührt Slider -> Dialog transparent (20%)
                animateDialogAlpha(0.2f)
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                /// VISUELL: Sichtbar machen
                animateDialogAlpha(1.0f)

                // TAKTIL: Bestätigung, dass der Wert übernommen wurde
                slider.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        })
    }

    /**
     * Fades the card in/out via the shared [fadeTo] helper. The binding guard
     * matters because the slider callbacks can fire during teardown.
     */
    private fun animateDialogAlpha(targetAlpha: Float) {
        _binding?.cardRoot?.fadeTo(targetAlpha)
    }

    // ==================== ViewModel Observation ====================

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launchSafe("observe.layoutScale") {
            viewModel.layoutScaleState.collectLatest { scale ->
                safeRun("apply.layoutScale") {
                    binding.sliderTextSize.value = scale
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launchSafe("observe.verticalPadding") {
            viewModel.verticalPaddingState.collectLatest { padding ->
                safeRun("apply.verticalPadding") {
                    binding.sliderPadding.value = padding
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launchSafe("observe.isFontBold") {
            viewModel.isFontBoldState.collectLatest { isBold ->
                safeRun("apply.isFontBold") {
                    binding.switchBoldText.isChecked = isBold
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launchSafe("observe.contentTopMargin") {
            viewModel.contentTopMarginState.collectLatest { marginScale ->
                safeRun("apply.contentTopMargin") {
                    binding.sliderTopMargin.value = marginScale
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launchSafe("observe.favoritesAlignment") {
            viewModel.favoritesAlignmentState.collectLatest { alignment ->
                safeRun("apply.favoritesAlignment") {
                    val checkedId = alignmentToCheckedId(alignment)
                    if (binding.toggleFavoritesAlignment.checkedButtonId != checkedId) {
                        binding.toggleFavoritesAlignment.check(checkedId)
                    }
                }
            }
        }

        // Wallpaper-following surface. Mirrors the AppContextMenu (long-
        // press) treatment: same LuminanceClassification, same colour
        // pair, same WCAG-derived foreground. The card-root is the
        // dialog's visible Material surface, so we tint it directly
        // instead of going through the bottom-sheet container path the
        // long-press menu uses. TextViews (including MaterialButton +
        // MaterialSwitch, which both subclass TextView) get the
        // foreground colour via a child-sweep so a future label
        // addition doesn't need a separate wiring. The alignment
        // toggle group is treated separately so the selected button's
        // dark fill doesn't end up with dark-on-dark text on a light
        // surface — see [applyToggleGroupSurfaceColors].
        viewLifecycleOwner.lifecycleScope.launchSafe("observe.wallpaperSurface") {
            resolveWallpaperSurfaceUseCase().collectLatest { classification ->
                safeRun("apply.wallpaperSurface") {
                    val inverseClassification = when (classification) {
                        LuminanceClassification.LIGHT -> LuminanceClassification.DARK
                        LuminanceClassification.DARK -> LuminanceClassification.LIGHT
                    }
                    val surface = classification.toSurface(requireContext())
                    val inverseSurface = inverseClassification.toSurface(requireContext())
                    val color = surface.color
                    val inverseColor = inverseSurface.color
                    val fg = surface.foregroundColor()
                    val inverseFg = inverseSurface.foregroundColor()
                    binding.cardRoot.setCardBackgroundColor(color)
                    binding.dragHandle.backgroundTintList = ColorStateList.valueOf(fg)
                    applyForegroundColorRecursive(binding.cardRoot, fg)
                    applyToggleGroupSurfaceColors(
                        binding.toggleFavoritesAlignment,
                        fg = fg,
                        inverseColor = inverseColor,
                        inverseFg = inverseFg,
                    )
                }
            }
        }
    }

    /**
     * Walks the dialog's view tree and tints every [TextView] descendant
     * with [color]. Catches `MaterialButton` and `MaterialSwitch` too
     * (both subclass TextView), so the Reset button and the
     * "Bold text" switch label end up readable on every wallpaper-
     * driven surface colour. Children of a [MaterialButtonToggleGroup]
     * are skipped — their colours are state-aware and applied by
     * [applyToggleGroupSurfaceColors] right after this sweep so the
     * selected button gets the inverted (high-contrast) treatment.
     */
    private fun applyForegroundColorRecursive(root: ViewGroup, color: Int) {
        root.tintTextViews(color) { child -> child is MaterialButtonToggleGroup }
    }

    /**
     * Paints the alignment toggle group with a state-aware colour pair
     * so the selected button reads as "inverted surface" — the
     * opposite-surface fill, with that surface's WCAG foreground for
     * the text — and the unselected buttons stay flush with the
     * dialog's body. Without this, the Material default leaves the
     * selected button's fill anchored to the activity theme
     * (typically dark) while the dialog body follows the wallpaper —
     * dark-on-dark text on a light wallpaper, the bug the user
     * reported.
     */
    private fun applyToggleGroupSurfaceColors(
        group: MaterialButtonToggleGroup,
        fg: Int,
        inverseColor: Int,
        inverseFg: Int,
    ) {
        val textColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(inverseFg, fg),
        )
        val backgroundTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(inverseColor, Color.TRANSPARENT),
        )
        val strokeTint = ColorStateList.valueOf(fg)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is MaterialButton) {
                child.setTextColor(textColors)
                child.backgroundTintList = backgroundTint
                child.strokeColor = strokeTint
            }
        }
    }

    // ==================== Alignment Toggle Mapping ====================

    private fun checkedIdToAlignment(checkedId: Int): FavoritesAlignment? = when (checkedId) {
        R.id.btn_alignment_start -> FavoritesAlignment.START
        R.id.btn_alignment_center -> FavoritesAlignment.CENTER
        R.id.btn_alignment_end -> FavoritesAlignment.END
        else -> null
    }

    private fun alignmentToCheckedId(alignment: FavoritesAlignment): Int = when (alignment) {
        FavoritesAlignment.START -> R.id.btn_alignment_start
        FavoritesAlignment.CENTER -> R.id.btn_alignment_center
        FavoritesAlignment.END -> R.id.btn_alignment_end
    }

    // ==================== Safe Utilities ====================

    private inline fun safeRun(context: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in $context")
        }
    }

    private fun CoroutineScope.launchSafe(
        context: String,
        block: suspend CoroutineScope.() -> Unit
    ) = launch {
        try {
            block()
        } catch (e: CancellationException) {
            Timber.d("Coroutine cancelled: $context")
            throw e  // WICHTIG: Immer rethrowen!
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in coroutine: $context")
        }
    }

    private fun dismissSafe() {
        try {
            dismissAllowingStateLoss()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to dismiss dialog")
        }
    }
}