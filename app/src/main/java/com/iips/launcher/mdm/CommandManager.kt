package com.iips.launcher.mdm

import android.content.Context
import android.util.Log
import com.iips.launcher.config.MdmCommand
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Manages the sequential execution of MDM commands.
 * Decouples command reception (WS/Polling) from execution to ensure atomicity.
 */
object CommandManager {
    private const val TAG = "CommandManager"
    private val commandChannel = Channel<MdmCommand>(Channel.UNLIMITED)
    private var isProcessorRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Starts the sequential command processor if not already running.
     */
    fun startProcessor(context: Context, ackCallback: suspend (com.iips.launcher.config.CommandAcknowledgement) -> Unit) {
        if (isProcessorRunning) return
        isProcessorRunning = true

        scope.launch {
            Log.i(TAG, "Command Processor started")
            
            // 1. Load pending commands from persistent storage on startup
            val pending = SecurePreferences.getPendingCommands(context)
            if (pending.isNotEmpty()) {
                Log.d(TAG, "Resuming ${pending.size} pending commands")
                pending.forEach { commandChannel.send(it) }
            }

            // 2. Sequential processing loop
            for (command in commandChannel) {
                try {
                    Log.d(TAG, "Processing command sequentially: ${command.id} (${command.type})")
                    
                    // Execute and SUSPEND until completion (SUCCESS or FAILED)
                    MdmCommandHandler.handleCommand(context, command) { ack ->
                        // Pass ACK up to the service for delivery
                        ackCallback(ack)

                        // If command reached a terminal state, we update persistence
                        if (ack.status == "SUCCESS" || ack.status == "FAILED") {
                            updatePendingQueue(context, command.id)
                            
                            // Trigger immediate policy enforcement to ensure consistency
                            if (ack.status == "SUCCESS") {
                                Log.d(TAG, "Command ${command.id} succeeded. Triggering policy sync.")
                                scope.launch {
                                    val snapshot = SecurePreferences.getDevicePolicySnapshot(context)
                                    if (snapshot != null) {
                                        PolicyEnforcer.enforcePolicy(context, snapshot)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error processing command ${command.id}", e)
                }
            }
        }
    }

    /**
     * Enqueues a command for sequential execution.
     */
    fun enqueueCommand(context: Context, command: MdmCommand) {
        scope.launch {
            // Persist as pending if not already executed
            if (!SecurePreferences.isCommandExecuted(context, command.id)) {
                val pending = SecurePreferences.getPendingCommands(context).toMutableList()
                if (pending.none { it.id == command.id }) {
                    pending.add(command)
                    SecurePreferences.setPendingCommands(context, pending)
                    
                    // Send to channel for sequential processing
                    commandChannel.send(command)
                }
            } else {
                Log.d(TAG, "Command ${command.id} already executed, ignoring.")
            }
        }
    }

    private fun updatePendingQueue(context: Context, commandId: String) {
        val pending = SecurePreferences.getPendingCommands(context).toMutableList()
        if (pending.removeAll { it.id == commandId }) {
            SecurePreferences.setPendingCommands(context, pending)
            Log.d(TAG, "Removed command $commandId from pending queue")
        }
    }

    fun stopProcessor() {
        isProcessorRunning = false
        scope.cancel()
    }
}
