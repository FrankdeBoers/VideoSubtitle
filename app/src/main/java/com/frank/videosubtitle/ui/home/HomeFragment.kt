package com.frank.videosubtitle.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private val viewModel: HomeViewModel by viewModel()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = TaskListAdapter(onClick = { task ->
            findNavController().navigate(
                HomeFragmentDirections.actionHomeToProgress(task.id),
            )
        })
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.setHasFixedSize(true)
        binding.list.adapter = adapter

        binding.fabAdd.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.tasks)
                    binding.empty.isVisible = state.tasks.isEmpty() && !state.isLoading
                    state.errorMessage?.let {
                        Toast.makeText(requireContext(), getString(R.string.import_failed, it), Toast.LENGTH_LONG).show()
                        viewModel.consumeError()
                    }
                }
            }
        }
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
