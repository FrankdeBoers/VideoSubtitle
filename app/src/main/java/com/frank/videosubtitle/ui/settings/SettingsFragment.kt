package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsBinding
import com.frank.videosubtitle.databinding.ViewSettingsRowBinding
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        bindStaticRows()

        val nav = findNavController()
        binding.rowModel.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToModel())
        }
        binding.rowTranslate.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToTranslate())
        }
        binding.rowLanguage.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToLanguage())
        }
        binding.rowStyle.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToStyle())
        }
        binding.rowOutput.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToOutput())
        }
        binding.rowCache.root.setOnClickListener {
            nav.navigate(SettingsFragmentDirections.actionSettingsToCache())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { renderSubtitles(it) }
            }
        }
    }

    private fun bindStaticRows() {
        binding.rowModel.bind(R.drawable.ic_setting_model, R.string.settings_section_model)
        binding.rowTranslate.bind(R.drawable.ic_setting_translate, R.string.settings_section_translate)
        binding.rowLanguage.bind(R.drawable.ic_setting_language, R.string.settings_section_language)
        binding.rowStyle.bind(R.drawable.ic_setting_style, R.string.settings_section_style)
        binding.rowOutput.bind(R.drawable.ic_setting_output, R.string.settings_section_output)
        binding.rowCache.bind(R.drawable.ic_setting_cache, R.string.settings_section_cache)

        binding.rowStyle.subtitle.setText(R.string.settings_summary_style)
        binding.rowCache.subtitle.setText(R.string.settings_summary_cache)
    }

    private fun renderSubtitles(s: AppSettings) {
        val ctx = requireContext()
        binding.rowModel.subtitle.text = getString(modelNameRes(s.model), formatBytes(s.model.sizeBytes))
        binding.rowLanguage.subtitle.text = ctx.languageLabel(s.language)
        binding.rowTranslate.subtitle.text = if (s.translateToChinese) {
            ctx.providerLabel(s.translationProvider)
        } else {
            getString(R.string.settings_value_off)
        }
        val format = getString(
            if (s.burnMode == BurnMode.SOFT) R.string.settings_format_mp4_soft
            else R.string.settings_format_hard,
        )
        binding.rowOutput.subtitle.text = getString(
            R.string.settings_summary_output_format,
            ctx.presetLabel(s.preset),
            format,
        )
    }

    private fun ViewSettingsRowBinding.bind(iconRes: Int, titleRes: Int) {
        icon.setImageResource(iconRes)
        title.setText(titleRes)
    }
}
