package com.frank.videosubtitle.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentHomeBinding
import com.frank.videosubtitle.ui.common.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private val viewModel: HomeViewModel by viewModel()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    private lateinit var backCallback: OnBackPressedCallback

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = TaskListAdapter(
            onClick = { task ->
                findNavController().navigate(
                    HomeFragmentDirections.actionHomeToProgress(task.id),
                )
            },
            onLongClick = { task -> viewModel.enterSelection(task.id) },
            onToggleSelect = { task -> viewModel.toggleSelection(task.id) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.setHasFixedSize(true)
        binding.list.adapter = adapter

        binding.fabAdd.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        binding.empty.showEmpty(headline = getString(R.string.home_empty))

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewModel.clearSelection()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        applyToolbar(selectionMode = false, selectedCount = 0)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.tasks)
                    adapter.setSelection(state.selectionMode, state.selectedIds)
                    binding.empty.isVisible = state.tasks.isEmpty() && !state.isLoading && !state.selectionMode
                    binding.fabAdd.isVisible = !state.selectionMode
                    backCallback.isEnabled = state.selectionMode
                    applyToolbar(state.selectionMode, state.selectedIds.size)
                    state.errorMessage?.let {
                        Toast.makeText(requireContext(), getString(R.string.import_failed, it), Toast.LENGTH_LONG).show()
                        viewModel.consumeError()
                    }
                }
            }
        }
    }

    private fun applyToolbar(selectionMode: Boolean, selectedCount: Int) {
        val toolbar = binding.toolbar
        toolbar.menu.clear()
        if (selectionMode) {
            toolbar.title = getString(R.string.home_selection_title, selectedCount)
            toolbar.setNavigationIcon(R.drawable.close)
            toolbar.setNavigationOnClickListener { viewModel.clearSelection() }
            toolbar.inflateMenu(R.menu.menu_home_selection)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_start -> { viewModel.startSelected(); true }
                    R.id.action_select_all -> { viewModel.selectAll(); true }
                    R.id.action_delete -> { confirmDelete(selectedCount); true }
                    else -> false
                }
            }
        } else {
            toolbar.title = getString(R.string.home_title)
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            toolbar.inflateMenu(R.menu.menu_home)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_settings -> {
                        findNavController().navigate(HomeFragmentDirections.actionHomeToSettings())
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun confirmDelete(count: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_delete_confirm_title)
            .setMessage(getString(R.string.home_delete_confirm_message, count))
            .setNegativeButton(R.string.home_delete_cancel, null)
            .setPositiveButton(R.string.home_delete_confirm_ok) { _, _ -> viewModel.deleteSelected() }
            .show()
    }

    private fun onVideoPicked(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "takePersistableUriPermission failed for $uri") }
        viewModel.importVideo(uri)
    }
}
