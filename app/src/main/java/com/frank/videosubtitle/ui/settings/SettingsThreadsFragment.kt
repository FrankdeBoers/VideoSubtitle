package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsThreadsBinding
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsThreadsFragment :
    BaseFragment<FragmentSettingsThreadsBinding>(FragmentSettingsThreadsBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private val maxCores: Int by lazy {
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Slider: 0 = Auto, 1..maxCores = explicit thread count.
        binding.sliderThreads.valueFrom = 0f
        binding.sliderThreads.valueTo = maxCores.toFloat()
        binding.sliderThreads.stepSize = 1f

        binding.sliderThreads.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressCallbacks) return@addOnChangeListener
            val choice = value.toInt().coerceIn(0, maxCores)
            viewModel.setThreadCount(choice)
            binding.textThreadValue.text = formatValue(choice)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(s: AppSettings) {
        val v = s.threadCount.coerceIn(0, maxCores)
        suppressCallbacks = true
        try {
            binding.sliderThreads.value = v.toFloat()
        } finally {
            suppressCallbacks = false
        }
        binding.textThreadValue.text = formatValue(v)
    }

    private fun formatValue(v: Int): String =
        if (v == AppSettings.THREAD_COUNT_AUTO) {
            getString(R.string.settings_threads_value_auto_short)
        } else {
            getString(R.string.settings_threads_value_count_short, v)
        }
}
