package com.iips.launcher.pocket.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import com.iips.launcher.databinding.ItemAppPocketBinding
import com.iips.launcher.pocket.data.AppPocketEntity
import java.text.SimpleDateFormat
import java.util.*

class AppPocketAdapter(
    private val onPrimaryAction: (AppPocketEntity) -> Unit,
    private val onSecondaryAction: (AppPocketEntity) -> Unit
) : RecyclerView.Adapter<AppPocketAdapter.ViewHolder>() {

    private var items = emptyList<AppPocketEntity>()

    fun submitList(newItems: List<AppPocketEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPocketBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAppPocketBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppPocketEntity) {
            val context = binding.root.context
            binding.txtAppName.text = app.appName
            binding.txtPackageName.text = app.packageName
            binding.txtVersion.text = "Version: ${app.versionName} (${app.versionCode})"

            binding.txtStatusBadge.text = app.status
            when (app.status) {
                "INSTALLED" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.success))
                }
                "PENDING" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.info))
                }
                "AWAITING_APPROVAL" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.warning))
                }
                "APPROVED" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.success))
                }
                "REJECTED" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.error))
                }
                "REMOVED" -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.text_secondary))
                }
                else -> {
                    binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.text_secondary))
                }
            }

            val mb = app.storageUsageBytes / (1024 * 1024)
            binding.txtStorage.text = "Storage: ${mb} MB"

            val lastUsed = app.lastUsedTimestamp
            if (lastUsed != null && lastUsed > 0) {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                binding.txtLastUsed.text = "Used: ${sdf.format(Date(lastUsed))}"
            } else {
                binding.txtLastUsed.text = "Used: Never"
            }

            binding.txtHealth.text = app.healthStatus
            when (app.healthStatus) {
                "HEALTHY" -> binding.txtHealth.setTextColor(context.getColor(R.color.success))
                "WARNING" -> binding.txtHealth.setTextColor(context.getColor(R.color.warning))
                "CRITICAL" -> binding.txtHealth.setTextColor(context.getColor(R.color.error))
                else -> binding.txtHealth.setTextColor(context.getColor(R.color.text_secondary))
            }

            binding.btnActionPrimary.visibility = View.GONE
            binding.btnActionSecondary.visibility = View.GONE

            when (app.status) {
                "PENDING" -> {
                    binding.btnActionPrimary.text = "Submit Approval"
                    binding.btnActionPrimary.visibility = View.VISIBLE
                    binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }
                }
                "AWAITING_APPROVAL" -> {
                    binding.btnActionPrimary.text = "Approve"
                    binding.btnActionPrimary.visibility = View.VISIBLE
                    binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }

                    binding.btnActionSecondary.text = "Reject"
                    binding.btnActionSecondary.visibility = View.VISIBLE
                    binding.btnActionSecondary.setOnClickListener { onSecondaryAction(app) }
                }
                "APPROVED" -> {
                    binding.btnActionPrimary.text = "Install Now"
                    binding.btnActionPrimary.visibility = View.VISIBLE
                    binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }
                }
                "INSTALLED" -> {
                    if (!app.isRequired) {
                        binding.btnActionSecondary.text = "Uninstall"
                        binding.btnActionSecondary.visibility = View.VISIBLE
                        binding.btnActionSecondary.setOnClickListener { onSecondaryAction(app) }
                    }
                }
                "REJECTED" -> {
                    binding.btnActionPrimary.text = "Retry Approval"
                    binding.btnActionPrimary.visibility = View.VISIBLE
                    binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }
                }
                "REMOVED" -> {
                    binding.btnActionPrimary.text = "Reinstall"
                    binding.btnActionPrimary.visibility = View.VISIBLE
                    binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }
                }
            }
        }
    }
}
