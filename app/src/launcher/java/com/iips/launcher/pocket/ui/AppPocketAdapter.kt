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
            if (app.status == "INSTALLED") {
                binding.txtVersion.text = "Version: ${app.versionName} (${app.versionCode})"
                val mb = app.storageUsageBytes / (1024 * 1024)
                binding.txtStorage.text = "Storage: ${mb} MB"
            } else {
                binding.txtVersion.text = "Version: ${app.versionName}"
                binding.txtStorage.text = "Storage: Pending"
            }

            if (app.status == "INSTALLED") {
                binding.downloadProgressContainer.visibility = View.GONE
                binding.txtStatusBadge.text = "INSTALLED"
                binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.success))
            } else if (app.downloadStatus == "DOWNLOADING") {
                binding.txtStatusBadge.text = "DOWNLOADING"
                binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.info))
                binding.downloadProgressContainer.visibility = View.VISIBLE
                binding.downloadProgressBar.progress = app.downloadProgress
                binding.txtDownloadProgress.text = "Downloading: ${app.downloadProgress}%"
            } else if (app.downloadStatus == "COMPLETED") {
                binding.txtStatusBadge.text = "INSTALLING"
                binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.warning))
                binding.downloadProgressContainer.visibility = View.VISIBLE
                binding.downloadProgressBar.progress = 100
                binding.txtDownloadProgress.text = "Installing package..."
            } else if (app.downloadStatus == "FAILED") {
                binding.txtStatusBadge.text = "DOWNLOAD FAILED"
                binding.txtStatusBadge.setBackgroundColor(context.getColor(R.color.error))
                binding.downloadProgressContainer.visibility = View.VISIBLE
                binding.downloadProgressBar.progress = app.downloadProgress
                binding.txtDownloadProgress.text = "Failed at ${app.downloadProgress}%"
            } else {
                binding.downloadProgressContainer.visibility = View.GONE
                binding.txtStatusBadge.text = app.status
                when (app.status) {
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
            }

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

            if (app.status == "INSTALLED") {
                if (!app.isRequired) {
                    binding.btnActionSecondary.text = "Uninstall"
                    binding.btnActionSecondary.visibility = View.VISIBLE
                    binding.btnActionSecondary.setOnClickListener { onSecondaryAction(app) }
                }
            } else if (app.downloadStatus == "DOWNLOADING") {
                // No action button during active download
            } else if (app.downloadStatus == "FAILED") {
                binding.btnActionPrimary.text = "Resume Download"
                binding.btnActionPrimary.visibility = View.VISIBLE
                binding.btnActionPrimary.setOnClickListener { onPrimaryAction(app) }
            } else {
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
}
