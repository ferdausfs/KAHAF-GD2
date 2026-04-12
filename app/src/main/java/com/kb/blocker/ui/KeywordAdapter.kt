package com.kb.blocker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kb.blocker.data.db.KeywordEntity
import com.kb.blocker.databinding.ItemKeywordBinding

class KeywordAdapter(
    private val onDelete: (KeywordEntity) -> Unit
) : ListAdapter<KeywordEntity, KeywordAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemKeywordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entity: KeywordEntity) {
            b.tvKeyword.text = entity.keyword
            b.btnDelete.setOnClickListener { onDelete(entity) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemKeywordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<KeywordEntity>() {
            override fun areItemsTheSame(a: KeywordEntity, b: KeywordEntity) = a.id == b.id
            override fun areContentsTheSame(a: KeywordEntity, b: KeywordEntity) = a == b
        }
    }
}
