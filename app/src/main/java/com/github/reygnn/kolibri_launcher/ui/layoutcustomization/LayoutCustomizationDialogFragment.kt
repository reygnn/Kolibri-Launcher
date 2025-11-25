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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LayoutCustomizationDialogFragment : DialogFragment() {

    private val viewModel: LauncherViewModel by activityViewModels()
    private var _binding: DialogLayoutCustomizationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogLayoutCustomizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Dialog unten positionieren und Hintergrund transparent machen
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        observeViewModel()
        setupDragListener()
    }

    private fun setupControls() {
        // Slider Listener
        binding.sliderTextSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onSetLayoutScale(value)
        }
        binding.sliderPadding.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onSetVerticalPadding(value)
        }

        // Reset Button Listener
        binding.btnReset.setOnClickListener {
            viewModel.onResetLayoutSettings()
        }
    }

    private fun observeViewModel() {
        // Updates vom ViewModel empfangen (auch wichtig für Reset!)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.layoutScaleState.collectLatest { binding.sliderTextSize.value = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.verticalPaddingState.collectLatest { binding.sliderPadding.value = it }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        val dragHandle = binding.dragHandle
        val window = dialog?.window ?: return
        var initialY = 0
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}