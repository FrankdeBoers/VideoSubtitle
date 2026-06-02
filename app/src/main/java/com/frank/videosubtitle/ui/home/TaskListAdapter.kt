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
import com.frank.videosubtitle.domain.model.isInProgress
import com.frank.videosubtitle.ui.common.label
import java.io.File

class TaskListAdapter(
    private val onClick: (TaskState) -> Unit,
    private val onLongClick: (TaskState) -> Unit,
    private val onToggleSelect: (TaskState) -> Unit,
) : ListAdapter<TaskState, TaskListAdapter.VH>(DIFF) {

    private var selectionMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()
    private var nowMs: Long = System.currentTimeMillis()

    fun setSelection(selectionMode: Boolean, selectedIds: Set<String>) {
        val changed = this.selectionMode != selectionMode || this.selectedIds != selectedIds
        this.selectionMode = selectionMode
        this.selectedIds = selectedIds
        if (changed) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun tick(now: Long) {
        nowMs = now
        if (itemCount == 0) return
        if (currentList.any { it.stage.isInProgress() }) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_TICK)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.isEmpty() -> super.onBindViewHolder(holder, position, payloads)
            payloads.contains(PAYLOAD_SELECTION) -> holder.bindSelection(getItem(position))
            payloads.contains(PAYLOAD_TICK) -> holder.bindCostTime(getItem(position))
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(private val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(task: TaskState) {
            val ctx = binding.root.context
            binding.title.text = task.video.displayName
            binding.duration.text = ctx.getString(
                R.string.task_video_duration,
                formatDuration(ctx, task.video.durationMs),
            )
            binding.stage.text = task.stage.label(ctx)
            bindCostTime(task)
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

        fun bindCostTime(task: TaskState) {
            val ctx = binding.root.context
            val start = task.processingStartedAt
            val elapsed = when {
                start == null -> 0L
                task.stage.isInProgress() -> (nowMs - start).coerceAtLeast(0L)
                else -> (task.updatedAt - start).coerceAtLeast(0L)
            }
            val showCost = start != null && task.stage !is TaskStage.Idle && elapsed >= 1_000L
            binding.costTime.isVisible = showCost
            if (showCost) {
                binding.costTime.text = ctx.getString(
                    R.string.task_cost_time,
                    formatDuration(ctx, elapsed),
                )
            }
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

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_TICK = "tick"
        private val DIFF = object : DiffUtil.ItemCallback<TaskState>() {
            override fun areItemsTheSame(oldItem: TaskState, newItem: TaskState) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: TaskState, newItem: TaskState) = oldItem == newItem
        }
    }
}
