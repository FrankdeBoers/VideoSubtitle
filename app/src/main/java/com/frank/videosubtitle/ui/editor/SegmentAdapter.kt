package com.frank.videosubtitle.ui.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.frank.videosubtitle.R
import com.frank.videosubtitle.data.source.local.SrtSerializer
import com.frank.videosubtitle.databinding.ItemSegmentBinding
import com.frank.videosubtitle.domain.model.SubtitleSegment

class SegmentAdapter(
    private val onTextClick: (position: Int) -> Unit,
    private val onTimeClick: (position: Int) -> Unit,
) : ListAdapter<SubtitleSegment, SegmentAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemSegmentBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onTextClick(pos)
            }
            binding.timeRange.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onTimeClick(pos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemSegmentBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val seg = getItem(position)
        val ctx = holder.itemView.context
        holder.binding.index.text = ctx.getString(R.string.editor_segment_index, position + 1)
        holder.binding.timeRange.text =
            "${SrtSerializer.formatTimestamp(seg.startMs)} → ${SrtSerializer.formatTimestamp(seg.endMs)}"
        holder.binding.text.text = seg.text
        holder.binding.costTime.text = ctx.getString(
            R.string.segment_duration,
            formatSegmentDuration(ctx, (seg.endMs - seg.startMs).coerceAtLeast(0L)),
        )
    }

    private fun formatSegmentDuration(ctx: android.content.Context, ms: Long): String {
        val totalSec = ms / 1000
        val millis = (ms % 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = (totalSec % 60).toInt()
        return if (h > 0) ctx.getString(R.string.duration_hms, h, m, s)
        else ctx.getString(R.string.duration_ms_millis, m, s, millis)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SubtitleSegment>() {
            override fun areItemsTheSame(old: SubtitleSegment, new: SubtitleSegment): Boolean =
                old.index == new.index

            override fun areContentsTheSame(old: SubtitleSegment, new: SubtitleSegment): Boolean =
                old == new
        }
    }
}
