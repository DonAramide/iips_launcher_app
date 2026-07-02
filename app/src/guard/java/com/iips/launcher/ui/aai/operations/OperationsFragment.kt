package com.iips.launcher.ui.aai.operations

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iips.launcher.aai.viewmodel.AaiOperationsViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentOperationsBinding
import com.iips.launcher.network.GuardAaiWebSocketManager
import com.iips.launcher.network.models.AaiExportJob
import com.iips.launcher.network.models.AaiExportRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OperationsFragment : Fragment() {

    private var _binding: FragmentOperationsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AaiOperationsViewModel by viewModels()

    private val streamLog = StringBuilder()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOperationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupExportButtons()
        observeState()
    }

    private fun setupExportButtons() {
        binding.btnExportCsv.setOnClickListener {
            requestExport("CSV", "SESSIONS")
        }
        binding.btnExportJson.setOnClickListener {
            requestExport("JSON", "EVENTS")
        }
        binding.btnExportPdf.setOnClickListener {
            requestExport("PDF", "COMPLIANCE")
        }
    }

    private fun requestExport(format: String, dataType: String) {
        binding.tvExportStatus.text = "Creating $format export…"
        val request = AaiExportRequest(
            exportType = format,
            dataType = dataType,
            filters = null,
            dateFrom = null,
            dateTo = null
        )
        viewModel.createExportJob(request)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // WebSocket stream connection status
                launch {
                    viewModel.streamState.collect { state ->
                        val statusText = when (state) {
                            GuardAaiWebSocketManager.ConnectionState.CONNECTED -> "● Connected"
                            GuardAaiWebSocketManager.ConnectionState.CONNECTING -> "○ Connecting…"
                            GuardAaiWebSocketManager.ConnectionState.DISCONNECTED -> "✕ Disconnected"
                            GuardAaiWebSocketManager.ConnectionState.ERROR -> "✕ Error"
                        }
                        binding.tvStreamConnectionStatus.text = statusText
                    }
                }

                // Live stream frames → log view
                launch {
                    viewModel.streamFrames.collect { frame ->
                        val entry = "[${frame.timestamp?.take(19)?.replace("T", " ")}] ${frame.type}"
                        streamLog.insert(0, "$entry\n")
                        if (streamLog.length > 5000) streamLog.setLength(5000)
                        binding.tvStreamLog.text = streamLog.toString()
                    }
                }

                // Notifications
                launch {
                    viewModel.notifications.collect { state ->
                        if (state is UiState.Success) {
                            val unread = state.data.data.count { !it.isRead }
                            binding.tvNotificationBadge.text = if (unread > 0) "$unread unread" else "All read"
                        }
                    }
                }

                // Export job completion
                launch {
                    viewModel.exportCreated.collect { job ->
                        job ?: return@collect
                        when (job.status) {
                            "COMPLETED" -> {
                                binding.tvExportStatus.text = "Export ready (${job.exportType})"
                                job.downloadUrl?.let { url -> triggerDownload(url, job) }
                            }
                            "FAILED" -> binding.tvExportStatus.text = "Export failed: ${job.errorMessage}"
                            "PROCESSING", "PENDING" -> {
                                binding.tvExportStatus.text = "Processing… (${job.status})"
                                viewModel.pollExportJob(job.jobId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerDownload(url: String, job: AaiExportJob) {
        try {
            val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("AAI Export — ${job.dataType}.${job.exportType.lowercase()}")
                setDescription("Dotroid Guard Intelligence Export")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "aai_export_${job.jobId.take(8)}.${job.exportType.lowercase()}"
                )
            }
            dm.enqueue(request)
            Toast.makeText(requireContext(), "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
