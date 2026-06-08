package com.iips.launcher.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import com.iips.launcher.data.PairedDeviceEntity
import com.iips.launcher.databinding.ItemPairedDeviceBinding
import com.iips.launcher.utils.RelativeTimeUtils

class PairedDeviceAdapter(
    private var devices: List<PairedDeviceEntity>,
    private val onItemClick: (PairedDeviceEntity) -> Unit
) : RecyclerView.Adapter<PairedDeviceAdapter.ViewHolder>() {

    fun updateData(newDevices: List<PairedDeviceEntity>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPairedDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(
        private val binding: ItemPairedDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: PairedDeviceEntity) {
            binding.tvDeviceName.text = device.deviceName
            binding.tvMerchantBranch.text = "${device.merchantName} • ${device.branchName}"
            binding.tvLastSeen.text = RelativeTimeUtils.formatRelativeTime(device.lastSeen)

            // Connectivity Badge tint
            val connColor = if (device.connectivityStatus == "ONLINE") {
                ContextCompat.getColor(binding.root.context, R.color.success)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.text_secondary)
            }
            binding.tvConnectivityBadge.text = device.connectivityStatus
            binding.tvConnectivityBadge.backgroundTintList = ColorStateList.valueOf(connColor)

            // Security Badge tint
            val secColor = when (device.securityStatus) {
                "NORMAL" -> ContextCompat.getColor(binding.root.context, R.color.success)
                "LOCKED" -> ContextCompat.getColor(binding.root.context, R.color.error)
                "PENDING" -> ContextCompat.getColor(binding.root.context, R.color.warning)
                "SUSPENDED" -> ContextCompat.getColor(binding.root.context, R.color.accent)
                else -> ContextCompat.getColor(binding.root.context, R.color.primary)
            }
            binding.tvSecurityBadge.text = device.securityStatus
            binding.tvSecurityBadge.backgroundTintList = ColorStateList.valueOf(secColor)

            // Health Badge tint
            val healthColor = when (device.deviceHealthStatus) {
                "HEALTHY" -> ContextCompat.getColor(binding.root.context, R.color.success)
                "WARNING" -> ContextCompat.getColor(binding.root.context, R.color.warning)
                "CRITICAL" -> ContextCompat.getColor(binding.root.context, R.color.error)
                else -> ContextCompat.getColor(binding.root.context, R.color.success)
            }
            binding.tvHealthBadge.text = device.deviceHealthStatus
            binding.tvHealthBadge.backgroundTintList = ColorStateList.valueOf(healthColor)

            // Unread Alerts Count
            if (device.unreadAlertCount > 0) {
                binding.tvUnreadAlerts.text = if (device.unreadAlertCount == 1) "1 Alert" else "${device.unreadAlertCount} Alerts"
                binding.tvUnreadAlerts.visibility = View.VISIBLE
            } else {
                binding.tvUnreadAlerts.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onItemClick(device)
            }
        }
    }
}
