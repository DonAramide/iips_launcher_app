package com.iips.launcher.ui.taskbar

import android.content.Context
import com.iips.launcher.storage.SecurePreferences

class WorkspaceStateRepository(private val context: Context) {

    companion object {
        private const val KEY_PINNED_APPS = "taskbar_pinned_apps"
        private const val KEY_RECENT_APPS = "taskbar_recent_apps"
        private const val KEY_REMOVED_APPS = "taskbar_removed_apps"
        private const val MAX_RECENT_APPS = 10
    }

    fun getPinnedApps(): Set<String> {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        return prefs.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet()
    }

    fun pinApp(packageName: String) {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val pinned = getPinnedApps().toMutableSet()
        if (pinned.add(packageName)) {
            prefs.edit().putStringSet(KEY_PINNED_APPS, pinned).apply()
        }
    }

    fun unpinApp(packageName: String) {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val pinned = getPinnedApps().toMutableSet()
        if (pinned.remove(packageName)) {
            prefs.edit().putStringSet(KEY_PINNED_APPS, pinned).apply()
        }
    }

    fun isPinned(packageName: String): Boolean {
        return getPinnedApps().contains(packageName)
    }

    fun getRecentApps(): List<String> {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val raw = prefs.getString(KEY_RECENT_APPS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun addRecentApp(packageName: String) {
        // Do not add if already pinned, since pinned apps are already displayed
        if (isPinned(packageName)) return

        val recents = getRecentApps().toMutableList()
        recents.remove(packageName) // Move to top / end
        recents.add(packageName)

        if (recents.size > MAX_RECENT_APPS) {
            recents.removeAt(0)
        }

        val prefs = SecurePreferences.getEncryptedPrefs(context)
        prefs.edit().putString(KEY_RECENT_APPS, recents.joinToString(",")).apply()
    }

    fun removeRecentApp(packageName: String) {
        val recents = getRecentApps().toMutableList()
        if (recents.remove(packageName)) {
            val prefs = SecurePreferences.getEncryptedPrefs(context)
            prefs.edit().putString(KEY_RECENT_APPS, recents.joinToString(",")).apply()
        }
    }

    fun removeApp(packageName: String) {
        // Remove from recent apps
        removeRecentApp(packageName)
        // Add to removed set so it stays hidden until re-launched
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val removed = getRemovedApps().toMutableSet()
        removed.add(packageName)
        prefs.edit().putStringSet(KEY_REMOVED_APPS, removed).apply()
    }

    fun getRemovedApps(): Set<String> {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        return prefs.getStringSet(KEY_REMOVED_APPS, emptySet()) ?: emptySet()
    }

    fun clearRemovedApp(packageName: String) {
        val prefs = SecurePreferences.getEncryptedPrefs(context)
        val removed = getRemovedApps().toMutableSet()
        if (removed.remove(packageName)) {
            prefs.edit().putStringSet(KEY_REMOVED_APPS, removed).apply()
        }
    }
}
