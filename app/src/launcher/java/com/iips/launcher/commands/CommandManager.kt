package com.iips.launcher.commands

import android.content.Context
import android.util.Log
import com.iips.launcher.network.models.CommandAcknowledgement
import com.iips.launcher.network.models.MdmCommand
import com.iips.launcher.policy.PolicyEnforcer
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandHandler: MdmCommandHandler
) {
    companion object {
        private const val TAG = "CommandManager"
    }

    private val commandChannel = Channel<MdmCommand>(Channel.UNLIMITED)
    private var isProcessorRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startProcessor(ackCallback: suspend (CommandAcknowledgement) -> Unit) {
        if (isProcessorRunning) return
        isProcessorRunning = true

        scope.launch {
            Log.i(TAG, "Command Processor started")
            
            val pending = SecurePreferences.getPendingCommands(context)
            if (pending.isNotEmpty()) {
                pending.forEach { commandChannel.send(it) }
            }

            for (command in commandChannel) {
                try {
                    commandHandler.handleCommand(command) { ack ->
                        ackCallback(ack)

                        if (ack.status == "SUCCESS" || ack.status == "FAILED") {
                            updatePendingQueue(command.id)
                            
                            if (ack.status == "SUCCESS") {
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
                    Log.e(TAG, "Unexpected error processing command \${command.id}", e)
                }
            }
        }
    }

    fun enqueueCommand(command: MdmCommand) {
        scope.launch {
            if (!SecurePreferences.isCommandExecuted(context, command.id)) {
                val pending = SecurePreferences.getPendingCommands(context).toMutableList()
                if (pending.none { it.id == command.id }) {
                    pending.add(command)
                    SecurePreferences.setPendingCommands(context, pending)
                    commandChannel.send(command)
                }
            }
        }
    }

    private fun updatePendingQueue(commandId: String) {
        val pending = SecurePreferences.getPendingCommands(context).toMutableList()
        if (pending.removeAll { it.id == commandId }) {
            SecurePreferences.setPendingCommands(context, pending)
        }
    }

    fun stopProcessor() {
        isProcessorRunning = false
        scope.cancel()
    }
}
