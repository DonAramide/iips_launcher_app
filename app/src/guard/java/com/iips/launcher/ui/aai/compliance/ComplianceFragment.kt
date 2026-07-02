package com.iips.launcher.ui.aai.compliance

import android.content.Context
import android.os.Bundle
import android.view.*
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
import com.google.android.material.tabs.TabLayout
import com.iips.launcher.aai.viewmodel.AaiComplianceViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentComplianceBinding
import com.iips.launcher.databinding.ItemAaiComplianceBinding
import com.iips.launcher.network.models.AaiComplianceFinding
import com.iips.launcher.network.models.AaiPolicyViolation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class ComplianceFragment : Fragment() {

    private var _binding: FragmentComplianceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AaiComplianceViewModel by viewModels()

    private lateinit var findingsAdapter: FindingsAdapter
    private lateinit var violationsAdapter: ViolationsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentComplianceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findingsAdapter = FindingsAdapter()
        violationsAdapter = ViolationsAdapter()

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = findingsAdapter

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.btnRetry.setOnClickListener { viewModel.refresh() }

        // Tab switching between Findings and Violations
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> binding.recyclerView.adapter = findingsAdapter
                    1 -> binding.recyclerView.adapter = violationsAdapter
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.findings.collect { state ->
                        binding.swipeRefresh.isRefreshing = false
                        binding.shimmerContainer.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
                        binding.errorState.visibility = if (state is UiState.Error) View.VISIBLE else View.GONE
                        binding.emptyState.visibility = if (state is UiState.Empty) View.VISIBLE else View.GONE
                        if (state is UiState.Success) {
                            binding.shimmerContainer.visibility = View.GONE
                            findingsAdapter.submitList(state.data.data)
                        }
                        if (state is UiState.Error) binding.tvErrorMessage.text = state.message
                    }
                }
                launch {
                    viewModel.violations.collect { state ->
                        if (state is UiState.Success) violationsAdapter.submitList(state.data.data)
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

// ─────────────────────────────────────────────────────────────────────────────
// Findings Adapter
// ─────────────────────────────────────────────────────────────────────────────

class FindingsAdapter : ListAdapter<AaiComplianceFinding, FindingsAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAaiComplianceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAaiComplianceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(f: AaiComplianceFinding) {
            b.tvFindingType.text = f.findingType.replace("_", " ")
            b.tvDeviceName.text = f.deviceName ?: f.deviceId
            b.tvDescription.text = f.description ?: ""
            b.tvSeverityTag.text = f.severity
            b.tvDetectedAt.text = formatTs(f.detectedAt)

            val color = severityColor(b.root.context, f.severity)
            b.viewSeverityBar.setBackgroundColor(color)
            b.tvSeverityTag.setBackgroundColor(color)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AaiComplianceFinding>() {
            override fun areItemsTheSame(a: AaiComplianceFinding, b: AaiComplianceFinding) = a.findingId == b.findingId
            override fun areContentsTheSame(a: AaiComplianceFinding, b: AaiComplianceFinding) = a == b
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Violations Adapter (re-uses same item layout)
// ─────────────────────────────────────────────────────────────────────────────

class ViolationsAdapter : ListAdapter<AaiPolicyViolation, ViolationsAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAaiComplianceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAaiComplianceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: AaiPolicyViolation) {
            b.tvFindingType.text = "${v.policyName ?: "Policy"}: ${v.violationType.replace("_", " ")}"
            b.tvDeviceName.text = v.deviceName ?: v.deviceId
            b.tvDescription.text = v.description ?: ""
            b.tvSeverityTag.text = v.severity
            b.tvDetectedAt.text = formatTs(v.detectedAt)

            val color = severityColor(b.root.context, v.severity)
            b.viewSeverityBar.setBackgroundColor(color)
            b.tvSeverityTag.setBackgroundColor(color)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AaiPolicyViolation>() {
            override fun areItemsTheSame(a: AaiPolicyViolation, b: AaiPolicyViolation) = a.violationId == b.violationId
            override fun areContentsTheSame(a: AaiPolicyViolation, b: AaiPolicyViolation) = a == b
        }
    }
}

private fun formatTs(ts: String?): String {
    return try {
        DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.parse(ts))
    } catch (e: Exception) { ts ?: "" }
}

private fun severityColor(context: Context, severity: String): Int {
    return when (severity.uppercase()) {
        "CRITICAL" -> ContextCompat.getColor(context, com.iips.launcher.R.color.aai_severity_critical)
        "HIGH"     -> ContextCompat.getColor(context, com.iips.launcher.R.color.aai_severity_high)
        "MEDIUM"   -> ContextCompat.getColor(context, com.iips.launcher.R.color.aai_severity_medium)
        else       -> ContextCompat.getColor(context, com.iips.launcher.R.color.aai_severity_low)
    }
}
