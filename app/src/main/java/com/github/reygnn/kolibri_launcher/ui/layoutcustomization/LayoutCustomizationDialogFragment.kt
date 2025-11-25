package com.github.reygnn.kolibri_launcher.ui.layoutcustomization

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
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
        super.onDestroyView()
        _binding = null
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
        // Slider Listener
        binding.sliderTextSize.addOnChangeListener { _, value, fromUser ->
            safeRun("sliderTextSize.onChange") {
                if (fromUser) viewModel.onSetLayoutScale(value)
            }
        }

        binding.sliderPadding.addOnChangeListener { _, value, fromUser ->
            safeRun("sliderPadding.onChange") {
                if (fromUser) viewModel.onSetVerticalPadding(value)
            }
        }

        // Switch Listener
        binding.switchBoldText.setOnCheckedChangeListener { _, isChecked ->
            safeRun("switchBoldText.onChange") {
                viewModel.onSetFontBold(isChecked)
            }
        }

        // Reset Button Listener
        binding.btnReset.setOnClickListener {
            safeRun("btnReset.onClick") {
                viewModel.onResetLayoutSettings()
            }
        }

        binding.sliderTopMargin.addOnChangeListener { _, value, fromUser ->
            safeRun("sliderTopMargin.onChange") {
                if (fromUser) viewModel.onSetContentTopMargin(value)
            }
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
        val window = dialog?.window ?: return

        var initialY = 0
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            try {
                val layoutParams = window.attributes
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = layoutParams.y
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                        window.attributes = layoutParams
                        true
                    }
                    else -> false
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error in drag handling")
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