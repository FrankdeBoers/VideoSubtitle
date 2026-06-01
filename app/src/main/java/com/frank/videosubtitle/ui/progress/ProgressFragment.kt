package com.frank.videosubtitle.ui.progress

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.navigation.fragment.findNavController
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

    private var navigatedToEditor = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStart.setOnClickListener { viewModel.startPipeline() }
        binding.btnCancel.setOnClickListener { viewModel.cancel() }
        binding.btnDownloadModel.setOnClickListener { viewModel.downloadModel() }
        binding.btnOpenPlayer.setOnClickListener {
            val outputPath = (viewModel.uiState.value.task?.stage as? TaskStage.Done)?.outputPath
                ?: return@setOnClickListener
            openInPlayer(outputPath)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: ProgressUiState) {
        val task = state.task ?: return
        if (task.stage is TaskStage.Editing && !navigatedToEditor) {
            navigatedToEditor = true
            findNavController().navigate(
                ProgressFragmentDirections.actionProgressToEditor(args.taskId)
            )
            return
        }
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
        binding.btnOpenPlayer.isVisible = task.stage is TaskStage.Done

        renderModel(state.model)
    }

    private fun openInPlayer(outputPath: String) {
        val uri = runCatching { Uri.parse(outputPath) }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.progress_burn_failed_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderModel(status: ModelStatus) {
        val ctx = requireContext()
        when (status) {
            ModelStatus.Unknown -> {
                binding.modelLabel.isVisible = false
                binding.modelProgress.isVisible = false
                binding.btnDownloadModel.isVisible = false
            }
            is ModelStatus.Missing -> {
                binding.modelLabel.isVisible = true
                binding.modelLabel.text = ctx.getString(
                    R.string.model_missing,
                    status.modelName,
                    formatBytes(status.sizeBytes),
                )
                binding.modelProgress.isVisible = false
                binding.btnDownloadModel.isVisible = true
            }
            is ModelStatus.Downloading -> {
                binding.modelLabel.isVisible = true
                binding.modelLabel.text = ctx.getString(
                    R.string.model_downloading,
                    status.percent,
                    formatBytes(status.downloaded),
                    formatBytes(status.total),
                )
                binding.modelProgress.isVisible = true
                binding.modelProgress.setProgressCompat(status.percent, true)
                binding.btnDownloadModel.isVisible = false
            }
            ModelStatus.Ready -> {
                binding.modelLabel.isVisible = true
                binding.modelLabel.text = ctx.getString(R.string.model_ready)
                binding.modelProgress.isVisible = false
                binding.btnDownloadModel.isVisible = false
            }
            is ModelStatus.Failed -> {
                binding.modelLabel.isVisible = true
                binding.modelLabel.text = ctx.getString(R.string.model_failed, status.reason)
                binding.modelProgress.isVisible = false
                binding.btnDownloadModel.isVisible = true
            }
        }
    }

    private fun stageLabel(state: ProgressUiState): String {
        val ctx = requireContext()
        return when (val s = state.task?.stage) {
            null, TaskStage.Idle -> ctx.getString(R.string.task_stage_idle)
            is TaskStage.Extracting -> if (s.percent >= 100) ctx.getString(R.string.progress_audio_ready)
                else ctx.getString(R.string.task_stage_extracting, s.percent)
            is TaskStage.Transcribing -> ctx.getString(R.string.task_stage_transcribing, s.percent)
            TaskStage.Editing -> ctx.getString(R.string.progress_subtitle_ready)
            is TaskStage.Burning -> ctx.getString(R.string.task_stage_burning, s.percent)
            is TaskStage.Done -> ctx.getString(R.string.task_stage_done)
            is TaskStage.Failed -> ctx.getString(R.string.task_stage_failed, s.reason)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${bytes} B" else "%.1f %s".format(v, units[i])
    }
}
