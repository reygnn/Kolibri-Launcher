package com.github.reygnn.kolibri_launcher.ui.layoutcustomization

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.databinding.DialogLayoutCustomizationBinding
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class LayoutCustomizationDialogFragment : DialogFragment() {

    private val viewModel: LauncherViewModel by activityViewModels()
    private var _binding: DialogLayoutCustomizationBinding? = null
    private val binding get() = _binding!!

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
            Timber.e(e, "Failed to inflate LayoutCustomizationDialog")
            null
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            configureDialogWindow()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to configure dialog window")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            setupControls()
            observeViewModel()
            setupDragListener()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to setup LayoutCustomizationDialog")
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
            }

            _binding = null

        } catch (e: Throwable) {
            Timber.e(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }

    // ==================== Window Configuration ====================

    private fun configureDialogWindow() {
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.95).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

            val params = window.attributes
            params.y = 150
            window.attributes = params
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

        // 5. Reset Button
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
     * Die eigentliche Animations-Logik.
     * Nutzt ViewPropertyAnimator für Performance und Interpolator für "Smoothness".
     */
    private fun animateDialogAlpha(targetAlpha: Float) {
        // Sicherstellen, dass Binding existiert
        _binding?.cardRoot?.animate()?.apply {
            alpha(targetAlpha)
            duration = 200
            // Decelerate lässt es "weich" einrasten
            interpolator = android.view.animation.DecelerateInterpolator()
            start()
        }
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

    }

    // ==================== Drag Handling ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        val dragHandle = binding.dragHandle

        var initialY = 0
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            try {
                val currentWindow = dialog?.window ?: return@setOnTouchListener false
                val layoutParams = currentWindow.attributes
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = layoutParams.y
                        initialTouchY = event.rawY

                        // VISUELL: Transparenz
                        animateDialogAlpha(0.5f)

                        // TAKTIL: Kurzes "Tock"
                        dragHandle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                        currentWindow.attributes = layoutParams
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // WICHTIG: Wenn losgelassen wird, wieder voll sichtbar machen
                        animateDialogAlpha(1.0f)
                        true
                    }
                    else -> false
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error in drag handling")
                // Fallback: Sicherstellen, dass Dialog sichtbar ist, falls was schiefgeht
                animateDialogAlpha(1.0f)
                false
            }
        }
    }

    // ==================== Safe Utilities ====================

    private inline fun safeRun(context: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Timber.e(e, "Error in $context")
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
            Timber.e(e, "Error in coroutine: $context")
        }
    }

    private fun dismissSafe() {
        try {
            dismissAllowingStateLoss()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to dismiss dialog")
        }
    }
}