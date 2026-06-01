package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsBinding
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.ui.common.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {

    private val viewModel: SettingsViewModel by viewModel()

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        wireDropdowns()
        wireListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.anyTaskRunning.collect { running ->
                    binding.btnClearCache.isEnabled = !running
                } }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }

    private fun wireDropdowns() {
        val langLabels = listOf(
            getString(R.string.settings_lang_auto),
            getString(R.string.settings_lang_zh),
            getString(R.string.settings_lang_en),
            getString(R.string.settings_lang_ja),
            getString(R.string.settings_lang_ko),
        )
        binding.dropdownLanguage.setSimpleItems(langLabels.toTypedArray())

        val presetLabels = listOf(
            getString(R.string.settings_preset_ultrafast),
            getString(R.string.settings_preset_fast),
            getString(R.string.settings_preset_medium),
            getString(R.string.settings_preset_slow),
        )
        binding.dropdownPreset.setSimpleItems(presetLabels.toTypedArray())
    }

    private fun wireListeners() {
        binding.modelTiny.setOnClickListener { if (!suppressCallbacks) viewModel.setModel(WhisperModel.Tiny) }
        binding.modelBase.setOnClickListener { if (!suppressCallbacks) viewModel.setModel(WhisperModel.Base) }
        binding.modelSmall.setOnClickListener { if (!suppressCallbacks) viewModel.setModel(WhisperModel.Small) }

        binding.dropdownLanguage.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallbacks) return@setOnItemClickListener
            val choice = LanguagePref.entries.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setLanguage(choice)
        }

        binding.dropdownPreset.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallbacks) return@setOnItemClickListener
            val choice = VideoPreset.entries.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setPreset(choice)
        }

        binding.sliderFontSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setFontSize(value.toInt())
        }

        binding.groupColor.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            val color = when (checkedId) {
                R.id.color_white -> SubtitleColor.White
                R.id.color_yellow -> SubtitleColor.Yellow
                R.id.color_green -> SubtitleColor.LimeGreen
                else -> return@addOnButtonCheckedListener
            }
            viewModel.setFontColor(color)
        }

        binding.groupAlignment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            val align = when (checkedId) {
                R.id.align_bottom -> SubtitleAlignment.BottomCenter
                R.id.align_top -> SubtitleAlignment.TopCenter
                else -> return@addOnButtonCheckedListener
            }
            viewModel.setAlignment(align)
        }

        binding.switchOutline.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setOutline(checked)
        }

        binding.switchSoft.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setBurnMode(if (checked) BurnMode.SOFT else BurnMode.HARD)
        }

        binding.btnClearCache.setOnClickListener { confirmClearCache() }
    }

    private fun render(s: AppSettings) {
        suppressCallbacks = true
        try {
            when (s.model) {
                WhisperModel.Tiny -> binding.modelTiny.isChecked = true
                WhisperModel.Base -> binding.modelBase.isChecked = true
                WhisperModel.Small -> binding.modelSmall.isChecked = true
            }
            binding.dropdownLanguage.setText(languageLabel(s.language), false)
            binding.dropdownPreset.setText(presetLabel(s.preset), false)
            binding.sliderFontSize.value = s.fontSize.toFloat()
            val colorButton = when (s.fontColor) {
                SubtitleColor.White -> R.id.color_white
                SubtitleColor.Yellow -> R.id.color_yellow
                SubtitleColor.LimeGreen -> R.id.color_green
            }
            binding.groupColor.check(colorButton)
            binding.groupAlignment.check(
                when (s.alignment) {
                    SubtitleAlignment.TopCenter -> R.id.align_top
                    else -> R.id.align_bottom
                },
            )
            binding.switchOutline.isChecked = s.outline
            binding.switchSoft.isChecked = s.burnMode == BurnMode.SOFT
        } finally {
            suppressCallbacks = false
        }
    }

    private fun languageLabel(pref: LanguagePref): String = getString(
        when (pref) {
            LanguagePref.Auto -> R.string.settings_lang_auto
            LanguagePref.ZhCn -> R.string.settings_lang_zh
            LanguagePref.En -> R.string.settings_lang_en
            LanguagePref.Ja -> R.string.settings_lang_ja
            LanguagePref.Ko -> R.string.settings_lang_ko
        },
    )

    private fun presetLabel(preset: VideoPreset): String = getString(
        when (preset) {
            VideoPreset.Ultrafast -> R.string.settings_preset_ultrafast
            VideoPreset.Fast -> R.string.settings_preset_fast
            VideoPreset.Medium -> R.string.settings_preset_medium
            VideoPreset.Slow -> R.string.settings_preset_slow
        },
    )

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_clear_cache_confirm_title)
            .setMessage(R.string.settings_clear_cache_confirm_message)
            .setNegativeButton(R.string.editor_dialog_cancel, null)
            .setPositiveButton(R.string.settings_clear_cache_confirm_ok) { _, _ -> viewModel.clearCache() }
            .show()
    }

    private fun handleEffect(effect: SettingsViewModel.Effect) {
        val msgRes = when (effect) {
            SettingsViewModel.Effect.CacheCleared -> R.string.settings_clear_cache_done
            SettingsViewModel.Effect.CacheBlocked -> R.string.settings_clear_cache_running
        }
        Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
    }
}
