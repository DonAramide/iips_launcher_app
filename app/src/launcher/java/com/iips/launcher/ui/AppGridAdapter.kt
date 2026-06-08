package com.iips.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import com.iips.launcher.data.AppInfo

class AppGridAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppGridAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_grid, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.app_icon)
        private val nameView: TextView = itemView.findViewById(R.id.app_name)

        fun bind(appInfo: AppInfo) {
            iconView.setImageDrawable(appInfo.icon)
            nameView.text = appInfo.name
            itemView.setOnClickListener {
                onAppClick(appInfo)
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem == newItem
        }
    }
}





