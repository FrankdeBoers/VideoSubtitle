package com.frank.videosubtitle.ui.progress

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import coil3.load
import coil3.request.crossfade
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentProgressBinding
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.io.File

class ProgressFragment : BaseFragment<FragmentProgressBinding>(FragmentProgressBinding::inflate) {

    private val args: ProgressFragmentArgs by navArgs()

    private val viewModel: ProgressViewModel by viewModel { parametersOf(args.taskId) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStart.setOnClickListener { viewModel.startExtraction() }
        binding.btnCancel.setOnClickListener { viewModel.cancel() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: ProgressUiState) {
        val task = state.task ?: return
        binding.title.text = task.video.displayName
        val thumb = task.video.thumbnailPath?.let(::File)?.takeIf { it.exists() }
        if (thumb != null) {
            binding.thumbnail.load(thumb) { crossfade(true) }
        }
        binding.progress.isVisible = state.percent in 1..99 || state.running
        binding.progress.setProgressCompat(state.percent, true)
        binding.stageLabel.text = stageLabel(state)
        binding.btnStart.isEnabled = state.canStart
        binding.btnCancel.isEnabled = state.canCancel
    }

    private fun stageLabel(state: ProgressUiState): String {
        val ctx = requireContext()
        return when (val s = state.task?.stage) {
            null, TaskStage.Idle -> ctx.getString(R.string.task_stage_idle)
            is TaskStage.Extracting -> if (s.percent >= 100) ctx.getString(R.string.progress_audio_ready)
                else ctx.getString(R.string.task_stage_extracting, s.percent)
            is TaskStage.Transcribing -> ctx.getString(R.string.task_stage_transcribing, s.percent)
            TaskStage.Editing -> ctx.getString(R.string.task_stage_editing)
            is TaskStage.Burning -> ctx.getString(R.string.task_stage_burning, s.percent)
            is TaskStage.Done -> ctx.getString(R.string.task_stage_done)
            is TaskStage.Failed -> ctx.getString(R.string.task_stage_failed, s.reason)
        }
    }
}
