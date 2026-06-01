package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.data.source.local.BaiduCreds
import com.frank.videosubtitle.data.source.local.CredentialsSnapshot
import com.frank.videosubtitle.data.source.local.MicrosoftCreds
import com.frank.videosubtitle.data.source.local.TencentCreds
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.data.source.local.YoudaoCreds
import com.frank.videosubtitle.databinding.FragmentSettingsTranslateBinding
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsTranslateFragment :
    BaseFragment<FragmentSettingsTranslateBinding>(FragmentSettingsTranslateBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val providerLabels = TranslationProvider.entries.map { requireContext().providerLabel(it) }.toTypedArray()
        binding.dropdownProvider.setSimpleItems(providerLabels)

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.credentials.collect { renderCredentials(it) } }
            }
        }
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
            binding.switchTranslateZh.isChecked = s.translateToChinese
            binding.dropdownProvider.setText(requireContext().providerLabel(s.translationProvider), false)
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
}
