package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.databinding.FragmentSettingsBinding
import com.frank.videosubtitle.ui.common.BaseFragment

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val nav = findNavController()
        binding.rowModel.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToModel())
        }
        binding.rowTranslate.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToTranslate())
        }
        binding.rowLanguage.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToLanguage())
        }
        binding.rowStyle.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToStyle())
        }
        binding.rowOutput.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToOutput())
        }
        binding.rowCache.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToCache())
        }
    }
}
