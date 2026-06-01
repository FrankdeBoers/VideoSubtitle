package com.frank.videosubtitle.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.ItemTaskBinding
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import java.io.File

class TaskListAdapter(
    private val onClick: (TaskState) -> Unit,
    private val onLongClick: (TaskState) -> Unit,
    private val onToggleSelect: (TaskState) -> Unit,
) : ListAdapter<TaskState, TaskListAdapter.VH>(DIFF) {

    private var selectionMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()

    fun setSelection(selectionMode: Boolean, selectedIds: Set<String>) {
        val changed = this.selectionMode != selectionMode || this.selectedIds != selectedIds
        this.selectionMode = selectionMode
        this.selectedIds = selectedIds
        if (changed) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(private val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(task: TaskState) {
            val ctx = binding.root.context
            binding.title.text = task.video.displayName
            binding.duration.text = formatDuration(ctx, task.video.durationMs)
            binding.stage.text = formatStage(ctx, task.stage)
            val thumb = task.video.thumbnailPath?.let(::File)
            if (thumb != null && thumb.exists()) {
                binding.thumbnail.load(thumb) { crossfade(true) }
            } else {
                binding.thumbnail.setImageDrawable(null)
            }
            binding.root.setOnClickListener {
                if (selectionMode) onToggleSelect(task) else onClick(task)
            }
            binding.root.setOnLongClickListener {
                onLongClick(task)
                true
            }
            bindSelection(task)
        }

        fun bindSelection(task: TaskState) {
            val selected = task.id in selectedIds
            binding.root.isActivated = selected
            binding.checkmark.isVisible = selectionMode && selected
        }
    }

    private fun formatDuration(ctx: android.content.Context, ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) ctx.getString(R.string.duration_hms, h, m, s)
        else ctx.getString(R.string.duration_ms, m, s)
    }

    private fun formatStage(ctx: android.content.Context, stage: TaskStage): String = when (stage) {
        TaskStage.Idle -> ctx.getString(R.string.task_stage_idle)
        is TaskStage.Extracting -> ctx.getString(R.string.task_stage_extracting, stage.percent)
        is TaskStage.Transcribing -> ctx.getString(R.string.task_stage_transcribing, stage.percent)
        is TaskStage.Translating -> ctx.getString(R.string.task_stage_translating, stage.percent)
        TaskStage.Editing -> ctx.getString(R.string.task_stage_editing)
        is TaskStage.Burning -> ctx.getString(R.string.task_stage_burning, stage.percent)
        is TaskStage.Done -> ctx.getString(R.string.task_stage_done)
        is TaskStage.Failed -> ctx.getString(R.string.task_stage_failed, stage.reason)
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
        private val DIFF = object : DiffUtil.ItemCallback<TaskState>() {
            override fun areItemsTheSame(oldItem: TaskState, newItem: TaskState) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: TaskState, newItem: TaskState) = oldItem == newItem
        }
    }
}
