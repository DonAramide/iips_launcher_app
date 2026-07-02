package com.iips.launcher.ui.aai.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import com.iips.launcher.aai.viewmodel.AaiDashboardViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentAaiDashboardBinding
import com.iips.launcher.databinding.ItemAaiLiveFeedBinding
import com.iips.launcher.network.GuardAaiWebSocketManager
import com.iips.launcher.network.models.AaiLiveFeedEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class AaiDashboardFragment : Fragment() {

    private var _binding: FragmentAaiDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AaiDashboardViewModel by viewModels()
    private lateinit var feedAdapter: LiveFeedAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAaiDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupSwipeRefresh()
        observeViewModels()
        viewModel.loadLiveFeed()
    }

    private fun setupRecycler() {
        feedAdapter = LiveFeedAdapter()
        binding.recyclerLiveFeed.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = feedAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadDashboard()
            viewModel.loadLiveFeed()
        }
    }

    private fun observeViewModels() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Dashboard KPI summary
                launch {
                    viewModel.summary.collect { state ->
                        binding.swipeRefresh.isRefreshing = false
                        when (state) {
                            is UiState.Loading -> showShimmer()
                            is UiState.Success -> {
                                hideShimmer()
                                val s = state.data
                                binding.tvActiveDevices.text = s.activeDevices.toString()
                                binding.tvActiveSessions.text = s.activeSessions.toString()
                                binding.tvActiveApps.text = s.activeApplications.toString()
                                binding.tvComplianceAlerts.text = s.complianceViolations.toString()
                                showContent()
                            }
                            is UiState.Error -> {
                                hideShimmer()
                                binding.tvErrorMessage.text = state.message
                                binding.errorState.visibility = View.VISIBLE
                                binding.recyclerLiveFeed.visibility = View.GONE
                            }
                            else -> {}
                        }
                    }
                }

                // Live Feed list
                launch {
                    viewModel.liveFeed.collect { events ->
                        feedAdapter.submitList(events)
                        binding.recyclerLiveFeed.visibility = if (events.isNotEmpty()) View.VISIBLE else View.GONE
                        binding.emptyState.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                // WebSocket state
                launch {
                    viewModel.streamState.collect { state ->
                        when (state) {
                            GuardAaiWebSocketManager.ConnectionState.CONNECTED -> {
                                binding.viewStreamDot.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.aai_stream_connected))
                                binding.tvStreamStatus.text = "Live Stream: Connected"
                            }
                            GuardAaiWebSocketManager.ConnectionState.CONNECTING -> {
                                binding.viewStreamDot.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.aai_stream_connecting))
                                binding.tvStreamStatus.text = "Live Stream: Connecting…"
                            }
                            GuardAaiWebSocketManager.ConnectionState.DISCONNECTED,
                            GuardAaiWebSocketManager.ConnectionState.ERROR -> {
                                binding.viewStreamDot.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.aai_stream_offline))
                                binding.tvStreamStatus.text = "Live Stream: Offline"
                            }
                        }
                    }
                }
            }
        }

        // Retry button
        binding.btnRetry.setOnClickListener {
            binding.errorState.visibility = View.GONE
            viewModel.loadDashboard()
        }
    }

    private fun showShimmer() {
        binding.shimmerContainer.visibility = View.VISIBLE
        binding.recyclerLiveFeed.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.errorState.visibility = View.GONE
    }

    private fun hideShimmer() {
        binding.shimmerContainer.visibility = View.GONE
    }

    private fun showContent() {
        binding.errorState.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RecyclerView Adapter
// ─────────────────────────────────────────────────────────────────────────────

class LiveFeedAdapter : ListAdapter<AaiLiveFeedEvent, LiveFeedAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAaiLiveFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemAaiLiveFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(event: AaiLiveFeedEvent) {
            binding.tvAppName.text = event.appName ?: event.packageName
            binding.tvEventType.text = event.eventType.replace("_", " ")
            binding.tvDeviceName.text = event.deviceName ?: event.deviceId
            binding.tvTimestamp.text = formatTimestamp(event.timestamp)

            // Color event dot by event type
            val dotColor = when (event.eventType) {
                "APP_FOREGROUND", "SESSION_START" -> ContextCompat.getColor(binding.root.context, R.color.aai_event_foreground)
                "COMPLIANCE_BREACH" -> ContextCompat.getColor(binding.root.context, R.color.aai_event_breach)
                "APP_BACKGROUND", "SESSION_END" -> ContextCompat.getColor(binding.root.context, R.color.aai_event_background_end)
                else -> ContextCompat.getColor(binding.root.context, R.color.aai_event_info)
            }
            binding.viewEventDot.setBackgroundColor(dotColor)

            // Risk tag
            event.riskLevel?.let { risk ->
                binding.tvRiskTag.visibility = View.VISIBLE
                binding.tvRiskTag.text = risk
                val riskColor = when (risk) {
                    "CRITICAL" -> ContextCompat.getColor(binding.root.context, R.color.aai_severity_critical)
                    "HIGH"     -> ContextCompat.getColor(binding.root.context, R.color.aai_severity_high)
                    "MEDIUM"   -> ContextCompat.getColor(binding.root.context, R.color.aai_severity_medium)
                    else       -> ContextCompat.getColor(binding.root.context, R.color.aai_severity_low)
                }
                binding.tvRiskTag.setBackgroundColor(riskColor)
            } ?: run { binding.tvRiskTag.visibility = View.GONE }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AaiLiveFeedEvent>() {
            override fun areItemsTheSame(a: AaiLiveFeedEvent, b: AaiLiveFeedEvent) = a.eventId == b.eventId
            override fun areContentsTheSame(a: AaiLiveFeedEvent, b: AaiLiveFeedEvent) = a == b
        }

        fun formatTimestamp(ts: String?): String {
            return try {
                val instant = Instant.parse(ts)
                DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(instant)
            } catch (e: Exception) {
                ts ?: ""
            }
        }
    }
}
