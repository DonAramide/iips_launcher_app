package com.iips.launcher.utils

import java.text.SimpleDateFormat
import java.util.*

object RelativeTimeUtils {
    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 0 -> "Now"
            diff < 60000 -> "Now"
            diff < 3600000 -> {
                val mins = diff / 60000
                if (mins == 1L) "1 min ago" else "$mins mins ago"
            }
            diff < 86400000 -> {
                val hours = diff / 3600000
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
            diff < 172800000 -> "Yesterday"
            else -> {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}
