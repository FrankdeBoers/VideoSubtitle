package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsComputeBinding
import com.frank.videosubtitle.domain.engine.ComputeMode
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.ui.common.BaseFragment
import com.whispercpp.whisper.WhisperLib
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * Compute backend picker — Auto / CPU / GPU. The GPU radio is disabled (and a
 * "not supported" subtitle shown) when [WhisperLib.gpuAvailable] returns false.
 * See `docs/GPU_SUPPORT_PLAN.md` §5.
 */
class SettingsComputeFragment :
    BaseFragment<FragmentSettingsComputeBinding>(FragmentSettingsComputeBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private var suppressCallbacks = false

    // Cheap probe — no model load. Cached because the fragment may rebind on
    // config change and the underlying capability doesn't change at runtime.
    private val gpuSupported: Boolean by lazy {
        runCatching { WhisperLib.gpuAvailable() }.getOrDefault(false)
    }
    private val gpuName: String by lazy {
        runCatching { WhisperLib.gpuDeviceName() }.getOrDefault("")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.radioComputeGpu.isEnabled = gpuSupported
        binding.textComputeGpuSubtitle.text = if (gpuSupported) {
            if (gpuName.isNotEmpty()) gpuName else getString(R.string.settings_compute_gpu_desc)
        } else {
            getString(R.string.settings_compute_gpu_unsupported)
        }

        binding.groupCompute.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val choice = when (checkedId) {
                R.id.radio_compute_cpu -> ComputeMode.Cpu
                R.id.radio_compute_gpu -> ComputeMode.Gpu
                else -> ComputeMode.Auto
            }
            viewModel.setComputeMode(choice)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(s: AppSettings) {
        // If a stale persisted value selects GPU on a device that no longer
        // supports it (e.g. user switched ROMs), fall back to Auto in the UI
        // without rewriting the prefs — the engine's runtime resolver already
        // handles the real fallback.
        val effective = if (s.computeMode == ComputeMode.Gpu && !gpuSupported) ComputeMode.Auto else s.computeMode
        val checkedId = when (effective) {
            ComputeMode.Auto -> R.id.radio_compute_auto
            ComputeMode.Cpu -> R.id.radio_compute_cpu
            ComputeMode.Gpu -> R.id.radio_compute_gpu
        }
        suppressCallbacks = true
        try {
            if (binding.groupCompute.checkedRadioButtonId != checkedId) {
                binding.groupCompute.check(checkedId)
            }
        } finally {
            suppressCallbacks = false
        }
    }
}
