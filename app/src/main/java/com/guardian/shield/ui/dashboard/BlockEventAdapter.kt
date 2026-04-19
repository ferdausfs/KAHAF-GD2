package com.guardian.shield.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.guardian.shield.databinding.ItemBlockEventBinding
import com.guardian.shield.domain.model.BlockEvent
import com.guardian.shield.domain.model.BlockReason
import java.text.SimpleDateFormat
import java.util.*

class BlockEventAdapter : ListAdapter<BlockEvent, BlockEventAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlockEvent>() {
            override fun areItemsTheSame(a: BlockEvent, b: BlockEvent) = a.id == b.id
            override fun areContentsTheSame(a: BlockEvent, b: BlockEvent) = a == b
        }
        private val TIME_FORMAT = SimpleDateFormat("HH:mm, MMM dd", Locale.getDefault())
    }

    inner class ViewHolder(private val binding: ItemBlockEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: BlockEvent) {
            binding.tvAppName.text  = event.appName
            binding.tvTime.text     = TIME_FORMAT.format(Date(event.timestamp))
            binding.tvReason.text   = when (event.reason) {
                BlockReason.APP_BLOCKED       -> "📵 App blocked"
                BlockReason.KEYWORD_DETECTED  -> "🔤 Keyword: ${event.detail}"
                BlockReason.AI_DETECTED       -> "🤖 AI: ${event.detail}"
            }
            val icon = when (event.reason) {
                BlockReason.APP_BLOCKED       -> "📵"
                BlockReason.KEYWORD_DETECTED  -> "🔤"
                BlockReason.AI_DETECTED       -> "🤖"
            }
            binding.tvIcon.text = icon
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
