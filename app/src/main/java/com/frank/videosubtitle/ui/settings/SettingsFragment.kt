package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.data.source.local.BaiduCreds
import com.frank.videosubtitle.data.source.local.CredentialsSnapshot
import com.frank.videosubtitle.data.source.local.MicrosoftCreds
import com.frank.videosubtitle.data.source.local.TencentCreds
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.data.source.local.YoudaoCreds
import com.frank.videosubtitle.databinding.FragmentSettingsBinding
import com.frank.videosubtitle.databinding.ItemModelCardBinding
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.ui.common.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {

    private val viewModel: SettingsViewModel by viewModel()

    private var suppressCallbacks = false

    private val modelCards: List<Pair<WhisperModel, ItemModelCardBinding>> by lazy {
        listOf(
            WhisperModel.Tiny to binding.cardTiny,
            WhisperModel.Base to binding.cardBase,
            WhisperModel.Small to binding.cardSmall,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        wireDropdowns()
        wireListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.modelStatus.collect { renderModelStatus(it) } }
                launch { viewModel.credentials.collect { renderCredentials(it) } }
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

        val providerLabels = TranslationProvider.entries.map { providerLabel(it) }.toTypedArray()
        binding.dropdownProvider.setSimpleItems(providerLabels)
    }

    private fun wireListeners() {
        modelCards.forEach { (model, card) ->
            card.root.setOnClickListener {
                if (!suppressCallbacks) viewModel.setModel(model)
            }
            card.btnAction.setOnClickListener {
                when (viewModel.modelStatus.value[model]) {
                    is ModelCardStatus.Downloading -> viewModel.cancelDownload(model)
                    is ModelCardStatus.Missing,
                    is ModelCardStatus.Failed,
                    null -> viewModel.startDownload(model)
                    ModelCardStatus.Ready -> Unit
                }
            }
            // Static per-model copy that doesn't depend on download state.
            card.textName.text = getString(modelNameRes(model), formatBytes(model.sizeBytes))
            card.textDesc.text = getString(modelDescRes(model))
            card.textSpeed.text = getString(R.string.settings_model_speed, dots(model.speedTier, "⚡", "·"))
            card.textQuality.text = getString(R.string.settings_model_quality, dots(model.qualityTier, "★", "☆"))
        }

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

        binding.switchTranslateZh.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setTranslateToChinese(checked)
        }

        binding.dropdownProvider.setOnItemClickListener { _, _, position, _ ->
            if (suppressCallbacks) return@setOnItemClickListener
            val choice = TranslationProvider.entries.getOrNull(position) ?: return@setOnItemClickListener
            viewModel.setTranslationProvider(choice)
        }

        wireCredentialFields()

        binding.btnClearCache.setOnClickListener { confirmClearCache() }
    }

    private fun wireCredentialFields() {
        binding.editBaiduAppid.onFocusLostSaveBaidu()
        binding.editBaiduSecret.onFocusLostSaveBaidu()

        binding.editYoudaoAppkey.onFocusLostSaveYoudao()
        binding.editYoudaoAppsecret.onFocusLostSaveYoudao()

        binding.editTencentSecretid.onFocusLostSaveTencent()
        binding.editTencentSecretkey.onFocusLostSaveTencent()
        binding.editTencentRegion.onFocusLostSaveTencent()

        binding.editMicrosoftKey.onFocusLostSaveMicrosoft()
        binding.editMicrosoftRegion.onFocusLostSaveMicrosoft()
    }

    private fun android.widget.EditText.onFocusLostSaveBaidu() {
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || suppressCallbacks) return@setOnFocusChangeListener
            viewModel.setBaiduCreds(
                BaiduCreds(
                    appId = binding.editBaiduAppid.text?.toString().orEmpty(),
                    secret = binding.editBaiduSecret.text?.toString().orEmpty(),
                ),
            )
        }
    }

    private fun android.widget.EditText.onFocusLostSaveYoudao() {
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || suppressCallbacks) return@setOnFocusChangeListener
            viewModel.setYoudaoCreds(
                YoudaoCreds(
                    appKey = binding.editYoudaoAppkey.text?.toString().orEmpty(),
                    appSecret = binding.editYoudaoAppsecret.text?.toString().orEmpty(),
                ),
            )
        }
    }

    private fun android.widget.EditText.onFocusLostSaveTencent() {
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || suppressCallbacks) return@setOnFocusChangeListener
            val region = binding.editTencentRegion.text?.toString().orEmpty()
                .ifBlank { TranslationCredentialsStore.TENCENT_DEFAULT_REGION }
            viewModel.setTencentCreds(
                TencentCreds(
                    secretId = binding.editTencentSecretid.text?.toString().orEmpty(),
                    secretKey = binding.editTencentSecretkey.text?.toString().orEmpty(),
                    region = region,
                ),
            )
        }
    }

    private fun android.widget.EditText.onFocusLostSaveMicrosoft() {
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || suppressCallbacks) return@setOnFocusChangeListener
            val region = binding.editMicrosoftRegion.text?.toString().orEmpty()
                .ifBlank { TranslationCredentialsStore.MS_DEFAULT_REGION }
            viewModel.setMicrosoftCreds(
                MicrosoftCreds(
                    key = binding.editMicrosoftKey.text?.toString().orEmpty(),
                    region = region,
                ),
            )
        }
    }

    private fun render(s: AppSettings) {
        suppressCallbacks = true
        try {
            modelCards.forEach { (model, card) ->
                val selected = s.model == model
                card.root.isChecked = selected
                card.checkIndicator.isVisible = selected
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
            binding.switchTranslateZh.isChecked = s.translateToChinese

            binding.dropdownProvider.setText(providerLabel(s.translationProvider), false)
            binding.dropdownProviderLayout.isEnabled = s.translateToChinese
            binding.dropdownProvider.isEnabled = s.translateToChinese
            binding.textProviderHint.setText(providerHintRes(s.translationProvider))
            binding.groupBaidu.isVisible =
                s.translateToChinese && s.translationProvider == TranslationProvider.Baidu
            binding.groupYoudao.isVisible =
                s.translateToChinese && s.translationProvider == TranslationProvider.Youdao
            binding.groupTencent.isVisible =
                s.translateToChinese && s.translationProvider == TranslationProvider.Tencent
            binding.groupMicrosoft.isVisible =
                s.translateToChinese && s.translationProvider == TranslationProvider.Microsoft
        } finally {
            suppressCallbacks = false
        }
    }

    private fun renderCredentials(snap: CredentialsSnapshot) {
        suppressCallbacks = true
        try {
            snap.baidu?.let {
                if (!binding.editBaiduAppid.hasFocus()) binding.editBaiduAppid.setText(it.appId)
                if (!binding.editBaiduSecret.hasFocus()) binding.editBaiduSecret.setText(it.secret)
            }
            snap.youdao?.let {
                if (!binding.editYoudaoAppkey.hasFocus()) binding.editYoudaoAppkey.setText(it.appKey)
                if (!binding.editYoudaoAppsecret.hasFocus()) binding.editYoudaoAppsecret.setText(it.appSecret)
            }
            snap.tencent?.let {
                if (!binding.editTencentSecretid.hasFocus()) binding.editTencentSecretid.setText(it.secretId)
                if (!binding.editTencentSecretkey.hasFocus()) binding.editTencentSecretkey.setText(it.secretKey)
                if (!binding.editTencentRegion.hasFocus()) binding.editTencentRegion.setText(it.region)
            }
            snap.microsoft?.let {
                if (!binding.editMicrosoftKey.hasFocus()) binding.editMicrosoftKey.setText(it.key)
                if (!binding.editMicrosoftRegion.hasFocus()) binding.editMicrosoftRegion.setText(it.region)
            }
        } finally {
            suppressCallbacks = false
        }
    }

    private fun providerLabel(provider: TranslationProvider): String = getString(
        when (provider) {
            TranslationProvider.MlKit -> R.string.settings_translate_provider_mlkit
            TranslationProvider.Baidu -> R.string.settings_translate_provider_baidu
            TranslationProvider.Youdao -> R.string.settings_translate_provider_youdao
            TranslationProvider.Tencent -> R.string.settings_translate_provider_tencent
            TranslationProvider.Microsoft -> R.string.settings_translate_provider_microsoft
        },
    )

    private fun providerHintRes(provider: TranslationProvider): Int = when (provider) {
        TranslationProvider.MlKit -> R.string.settings_translate_provider_mlkit_hint
        TranslationProvider.Baidu -> R.string.settings_translate_provider_baidu_hint
        TranslationProvider.Youdao -> R.string.settings_translate_provider_youdao_hint
        TranslationProvider.Tencent -> R.string.settings_translate_provider_tencent_hint
        TranslationProvider.Microsoft -> R.string.settings_translate_provider_microsoft_hint
    }

    private fun renderModelStatus(map: Map<WhisperModel, ModelCardStatus>) {
        modelCards.forEach { (model, card) ->
            val status = map[model] ?: ModelCardStatus.Missing
            when (status) {
                ModelCardStatus.Missing -> {
                    card.textStatus.text = getString(R.string.settings_model_status_missing)
                    card.btnAction.isVisible = true
                    card.btnAction.text = getString(R.string.settings_model_action_download)
                    card.progress.isVisible = false
                }
                is ModelCardStatus.Downloading -> {
                    card.textStatus.text = getString(
                        R.string.settings_model_status_downloading,
                        status.percent,
                        formatBytes(status.downloaded),
                        formatBytes(status.total),
                    )
                    card.btnAction.isVisible = true
                    card.btnAction.text = getString(R.string.settings_model_action_cancel)
                    card.progress.isVisible = true
                    if (status.total > 0 && status.percent > 0) {
                        card.progress.isIndeterminate = false
                        card.progress.setProgressCompat(status.percent, true)
                    } else {
                        card.progress.isIndeterminate = true
                    }
                }
                ModelCardStatus.Ready -> {
                    card.textStatus.text = getString(R.string.settings_model_status_ready)
                    card.btnAction.isVisible = false
                    card.progress.isVisible = false
                }
                is ModelCardStatus.Failed -> {
                    card.textStatus.text = getString(R.string.settings_model_status_failed, status.reason)
                    card.btnAction.isVisible = true
                    card.btnAction.text = getString(R.string.settings_model_action_retry)
                    card.progress.isVisible = false
                }
            }
        }
    }

    private fun modelNameRes(model: WhisperModel): Int = when (model) {
        WhisperModel.Tiny -> R.string.settings_model_tiny_name
        WhisperModel.Base -> R.string.settings_model_base_name
        WhisperModel.Small -> R.string.settings_model_small_name
    }

    private fun modelDescRes(model: WhisperModel): Int = when (model) {
        WhisperModel.Tiny -> R.string.settings_model_tiny_desc
        WhisperModel.Base -> R.string.settings_model_base_desc
        WhisperModel.Small -> R.string.settings_model_small_desc
    }

    private fun dots(filled: Int, on: String, off: String, total: Int = 3): String =
        on.repeat(filled.coerceIn(0, total)) + off.repeat((total - filled).coerceAtLeast(0))

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${bytes} B" else "%.1f %s".format(v, units[i])
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
