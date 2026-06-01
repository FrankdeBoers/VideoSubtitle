package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsLanguageBinding
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsLanguageFragment :
    BaseFragment<FragmentSettingsLanguageBinding>(FragmentSettingsLanguageBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val labels = listOf(
            getString(R.string.settings_lang_auto),
            getString(R.string.settings_lang_zh),
            getString(R.string.settings_lang_en),
            getString(R.string.settings_lang_ja),
            getString(R.string.settings_lang_ko),
        )
        binding.dropdownLanguage.setSimpleItems(labels.toTypedArray())

        binding.dropdownLanguage.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallbacks) return@setOnItemClickListener
            val choice = LanguagePref.entries.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setLanguage(choice)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    suppressCallbacks = true
                    try {
                        binding.dropdownLanguage.setText(requireContext().languageLabel(s.language), false)
                    } finally {
                        suppressCallbacks = false
                    }
                }
            }
        }
    }
}
