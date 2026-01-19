package com.iips.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import com.iips.launcher.data.AppInfo

class AppSelectionAdapter(
    private val onAppSelected: (AppInfo, Boolean) -> Unit
) : ListAdapter<AppInfo, AppSelectionAdapter.AppViewHolder>(AppDiffCallback()) {

    private val selectedPackages = mutableSetOf<String>()

    fun setSelectedPackages(packages: Set<String>) {
        selectedPackages.clear()
        selectedPackages.addAll(packages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_selection, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.app_icon)
        private val nameView: TextView = itemView.findViewById(R.id.app_name)
        private val packageView: TextView = itemView.findViewById(R.id.app_package)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkbox)
        private var isBinding = false

        fun bind(appInfo: AppInfo) {
            isBinding = true
            
            // Remove listeners before binding to prevent triggering
            checkBox.setOnCheckedChangeListener(null)
            
            iconView.setImageDrawable(appInfo.icon)
            nameView.text = appInfo.name
            packageView.text = appInfo.packageName
            
            // Compare using lowercase for consistency
            val packageNameKey = appInfo.packageName.trim().lowercase()
            val isSelected = selectedPackages.contains(packageNameKey)
            checkBox.isChecked = isSelected

            // Set up click listener for the entire item
            itemView.setOnClickListener {
                val newCheckedState = !checkBox.isChecked
                checkBox.isChecked = newCheckedState
                // This will trigger the checkbox listener below
            }

            // Set up checkbox listener - this handles the actual selection
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (!isBinding) {
                    // Only trigger callback if not during binding
                    onAppSelected(appInfo, isChecked)
                }
            }
            
            isBinding = false
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

