package com.iips.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.data.AppUsageLog
import com.iips.launcher.databinding.ItemAppUsageLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppUsageLogAdapter : ListAdapter<AppUsageLog, AppUsageLogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemAppUsageLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(private val binding: ItemAppUsageLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val dateTimeFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())

        fun bind(log: AppUsageLog) {
            binding.appNameText.text = log.appName
            binding.packageNameText.text = log.packageName
            
            val dateTime = dateTimeFormat.format(Date(log.startTime))
            binding.dateTimeText.text = dateTime
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<AppUsageLog>() {
        override fun areItemsTheSame(oldItem: AppUsageLog, newItem: AppUsageLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AppUsageLog, newItem: AppUsageLog): Boolean {
            return oldItem == newItem
        }
    }
}




