package com.iips.launcher.ui.aai.investigation

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.iips.launcher.aai.viewmodel.AaiInvestigationViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentInvestigationBinding
import com.iips.launcher.network.models.AaiTimelineEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InvestigationFragment : Fragment() {

    private var _binding: FragmentInvestigationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AaiInvestigationViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInvestigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSearch.setOnClickListener {
            val corrId = binding.etCorrelationId.text?.toString()?.trim()
            if (!corrId.isNullOrBlank()) {
                viewModel.lookupByCorrelationId(corrId)
            } else {
                Toast.makeText(requireContext(), "Enter a Correlation ID", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.timeline.collect { state ->
                    binding.progressBar.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
                    binding.timelineCard.visibility = if (state is UiState.Success) View.VISIBLE else View.GONE
                    binding.tvError.visibility = if (state is UiState.Error) View.VISIBLE else View.GONE

                    if (state is UiState.Success) {
                        val t = state.data
                        binding.tvCorrelationIdValue.text = t.correlationId
                        binding.tvEventCount.text = "${t.events.size} events"
                        binding.tvRelatedSessions.text = "${t.relatedSessions.size} sessions"
                        binding.tvDeviceCount.text = "${t.events.map { it.deviceId }.distinct().size} devices"

                        // Populate timeline events list
                        val summaryText = t.events.joinToString("\n") { ev ->
                            "[${ev.timestamp.take(19).replace("T", " ")}] ${ev.eventType} — ${ev.packageName} (${ev.deviceId.take(8)}…)"
                        }
                        binding.tvTimelineEvents.text = summaryText.ifBlank { "No events" }
                    }

                    if (state is UiState.Error) {
                        binding.tvError.text = state.message
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
