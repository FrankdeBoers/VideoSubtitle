package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsModelBinding
import com.frank.videosubtitle.databinding.ItemModelCardBinding
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsModelFragment :
    BaseFragment<FragmentSettingsModelBinding>(FragmentSettingsModelBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

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
            card.textName.text = getString(modelNameRes(model), formatBytes(model.sizeBytes))
            card.textDesc.text = getString(modelDescRes(model))
            card.textSpeed.text = getString(R.string.settings_model_speed, dots(model.speedTier, "⚡", "·"))
            card.textQuality.text = getString(R.string.settings_model_quality, dots(model.qualityTier, "★", "☆"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { renderSelection(it) } }
                launch { viewModel.modelStatus.collect { renderModelStatus(it) } }
            }
        }
    }

    private fun renderSelection(s: AppSettings) {
        suppressCallbacks = true
        try {
            modelCards.forEach { (model, card) ->
                val selected = s.model == model
                card.root.isChecked = selected
//                card.checkIndicator.isVisible = selected
            }
        } finally {
            suppressCallbacks = false
        }
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
}
