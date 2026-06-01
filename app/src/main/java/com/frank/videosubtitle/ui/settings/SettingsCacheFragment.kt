package com.frank.videosubtitle.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsCacheBinding
import com.frank.videosubtitle.ui.common.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsCacheFragment :
    BaseFragment<FragmentSettingsCacheBinding>(FragmentSettingsCacheBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnClearCache.setOnClickListener { confirmClearCache() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.anyTaskRunning.collect { running ->
                        binding.btnClearCache.isEnabled = !running
                    }
                }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }

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
