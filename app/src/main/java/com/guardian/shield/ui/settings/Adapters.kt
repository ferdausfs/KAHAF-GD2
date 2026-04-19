package com.guardian.shield.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.guardian.shield.databinding.ItemKeywordBinding
import com.guardian.shield.databinding.ItemAppRuleBinding
import com.guardian.shield.domain.model.AppRule
import com.guardian.shield.domain.model.KeywordRule

// ─────────────────────────────────────────────────────────────────────
// Keyword Adapter
// ─────────────────────────────────────────────────────────────────────

class KeywordAdapter(
    private val onRemove: (Long) -> Unit
) : ListAdapter<KeywordRule, KeywordAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<KeywordRule>() {
            override fun areItemsTheSame(a: KeywordRule, b: KeywordRule) = a.id == b.id
            override fun areContentsTheSame(a: KeywordRule, b: KeywordRule) = a == b
        }
    }

    inner class VH(private val binding: ItemKeywordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(rule: KeywordRule) {
            binding.tvKeyword.text = rule.keyword
            binding.btnRemove.setOnClickListener { onRemove(rule.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemKeywordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}

// ─────────────────────────────────────────────────────────────────────
// App Rule Adapter (used for both blocked and whitelisted lists)
// ─────────────────────────────────────────────────────────────────────

class AppRuleAdapter(
    private val onRemove: (String) -> Unit
) : ListAdapter<AppRule, AppRuleAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppRule>() {
            override fun areItemsTheSame(a: AppRule, b: AppRule) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppRule, b: AppRule) = a == b
        }
    }

    inner class VH(private val binding: ItemAppRuleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(rule: AppRule) {
            binding.tvAppName.text    = rule.appName
            binding.tvPackage.text    = rule.packageName
            binding.ivBadge.setImageResource(
                if (rule.isWhitelisted) com.guardian.shield.R.drawable.ic_check_circle
                else com.guardian.shield.R.drawable.ic_block
            )
            binding.btnRemove.setOnClickListener { onRemove(rule.packageName) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
