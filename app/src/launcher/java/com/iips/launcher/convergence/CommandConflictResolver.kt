package com.iips.launcher.convergence

import android.util.Log
import com.iips.launcher.network.models.MdmCommand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and resolves conflicts between pending MDM commands.
 */
@Singleton
class CommandConflictResolver @Inject constructor() {
    companion object {
        private const val TAG = "CommandConflict"
    }

    /**
     * Filters a list of pending commands to remove conflicts.
     */
    fun resolveConflicts(commands: List<MdmCommand>): List<MdmCommand> {
        val resolved = mutableListOf<MdmCommand>()
        
        // Priority logic: OTA > Policy Sync > Reboot > Others
        // If an OTA is pending, we might defer a Reboot until OTA completes.
        
        val hasOta = commands.any { it.type == "OTA_UPDATE" || it.type == "INSTALL_APP" }
        
        for (cmd in commands) {
            if (cmd.type == "REBOOT" && hasOta) {
                Log.w(TAG, "Deferring REBOOT command ${cmd.id} due to pending INSTALL/OTA")
                continue
            }
            resolved.add(cmd)
        }
        
        return resolved
    }
}
