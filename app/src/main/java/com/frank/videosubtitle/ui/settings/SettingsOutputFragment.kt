package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsOutputBinding
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsOutputFragment :
    BaseFragment<FragmentSettingsOutputBinding>(FragmentSettingsOutputBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val labels = listOf(
            getString(R.string.settings_preset_ultrafast),
            getString(R.string.settings_preset_fast),
            getString(R.string.settings_preset_medium),
            getString(R.string.settings_preset_slow),
        )
        binding.dropdownPreset.setSimpleItems(labels.toTypedArray())

        binding.dropdownPreset.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallbacks) return@setOnItemClickListener
            val choice = VideoPreset.entries.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setPreset(choice)
        }

        binding.switchSoft.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setBurnMode(if (checked) BurnMode.SOFT else BurnMode.HARD)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(s: AppSettings) {
        suppressCallbacks = true
        try {
            binding.dropdownPreset.setText(requireContext().presetLabel(s.preset), false)
            binding.switchSoft.isChecked = s.burnMode == BurnMode.SOFT
        } finally {
            suppressCallbacks = false
        }
    }
}
