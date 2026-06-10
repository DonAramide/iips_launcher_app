package com.iips.launcher.ui.taskbar

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R

data class WorkspaceItem(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val isRunning: Boolean,
    val isPinned: Boolean,
    val isOverflow: Boolean = false,
    val overflowCount: Int = 0
)

class WorkspaceAdapter(
    private val onAppClick: (WorkspaceItem) -> Unit,
    private val onAppLongClick: (WorkspaceItem, View) -> Unit,
    private val onOverflowClick: (View) -> Unit
) : ListAdapter<WorkspaceItem, WorkspaceAdapter.WorkspaceViewHolder>(WorkspaceItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkspaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workspace_app, parent, false)
        return WorkspaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorkspaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class WorkspaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.app_icon)
        private val overflowView: TextView = itemView.findViewById(R.id.txt_overflow)
        private val indicatorDot: View = itemView.findViewById(R.id.indicator_dot)

        fun bind(item: WorkspaceItem) {
            if (item.isOverflow) {
                iconView.visibility = View.GONE
                overflowView.visibility = View.VISIBLE
                overflowView.text = "+${item.overflowCount}"
                indicatorDot.visibility = View.GONE
                itemView.alpha = 1.0f

                itemView.setOnClickListener {
                    onOverflowClick(itemView)
                }
                itemView.setOnLongClickListener(null)
            } else {
                iconView.visibility = View.VISIBLE
                overflowView.visibility = View.GONE
                
                if (item.icon != null) {
                    iconView.setImageDrawable(item.icon)
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                if (item.isRunning) {
                    indicatorDot.visibility = View.VISIBLE
                    itemView.alpha = 1.0f
                } else {
                    indicatorDot.visibility = View.GONE
                    itemView.alpha = 0.5f // Translucent if not currently running
                }

                itemView.setOnClickListener {
                    onAppClick(item)
                }

                itemView.setOnLongClickListener { view ->
                    onAppLongClick(item, view)
                    true
                }
            }
        }
    }

    class WorkspaceItemDiffCallback : DiffUtil.ItemCallback<WorkspaceItem>() {
        override fun areItemsTheSame(oldItem: WorkspaceItem, newItem: WorkspaceItem): Boolean {
            if (oldItem.isOverflow && newItem.isOverflow) return true
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: WorkspaceItem, newItem: WorkspaceItem): Boolean {
            return oldItem == newItem
        }
    }
}
