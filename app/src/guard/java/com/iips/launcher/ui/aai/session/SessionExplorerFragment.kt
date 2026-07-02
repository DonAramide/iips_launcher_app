package com.iips.launcher.ui.aai.session

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.iips.launcher.aai.viewmodel.AaiSessionViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentAaiListBinding
import com.iips.launcher.databinding.ItemAaiSessionBinding
import com.iips.launcher.network.models.AaiSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class SessionExplorerFragment : Fragment() {

    private var _binding: FragmentAaiListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AaiSessionViewModel by viewModels()
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAaiListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SessionAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadSessions(loadMore = true)
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { adapter.filter(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.btnRetry.setOnClickListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessions.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    binding.shimmerContainer.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
                    binding.recyclerView.visibility = if (state is UiState.Success) View.VISIBLE else View.GONE
                    binding.emptyState.visibility = if (state is UiState.Empty) View.VISIBLE else View.GONE
                    binding.errorState.visibility = if (state is UiState.Error) View.VISIBLE else View.GONE
                    if (state is UiState.Success) adapter.setFullList(state.data.data)
                    if (state is UiState.Error) binding.tvErrorMessage.text = state.message
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class SessionAdapter : ListAdapter<AaiSession, SessionAdapter.VH>(DIFF) {
    private var fullList: List<AaiSession> = emptyList()

    fun setFullList(list: List<AaiSession>) {
        fullList = list
        submitList(list)
    }

    fun filter(q: String) {
        val f = if (q.isBlank()) fullList
        else fullList.filter {
            it.appName?.contains(q, true) == true || it.packageName.contains(q, true)
        }
        submitList(f)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAaiSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAaiSessionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: AaiSession) {
            b.tvAppName.text = s.appName ?: s.packageName
            b.tvDeviceName.text = s.deviceName ?: s.deviceId
            b.tvFocusTime.text = formatSecs(s.focusTimeSeconds)
            b.tvIdleTime.text = formatSecs(s.idleTimeSeconds)
            b.tvDuration.text = formatSecs(s.durationSeconds)
            b.tvTimeRange.text = buildString {
                append(formatTs(s.startTime))
                s.endTime?.let { append(" → ${formatTs(it)}") } ?: append(" → ACTIVE")
            }
            val (statusColor, statusText) = when (s.status) {
                "ACTIVE"    -> ContextCompat.getColor(b.root.context, com.iips.launcher.R.color.aai_session_active) to "ACTIVE"
                "COMPLETED" -> ContextCompat.getColor(b.root.context, com.iips.launcher.R.color.aai_session_completed) to "DONE"
                "ABORTED"   -> ContextCompat.getColor(b.root.context, com.iips.launcher.R.color.aai_session_aborted) to "ABORTED"
                else        -> ContextCompat.getColor(b.root.context, com.iips.launcher.R.color.aai_session_unknown) to s.status
            }
            b.tvStatusBadge.text = statusText
            b.tvStatusBadge.setBackgroundColor(statusColor)
        }

        private fun formatSecs(secs: Long?): String {
            if (secs == null) return "—"
            val m = secs / 60; val s = secs % 60
            return if (m > 0) "${m}m ${s}s" else "${s}s"
        }

        private fun formatTs(ts: String?): String {
            return try {
                DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(ts))
            } catch (e: Exception) { ts ?: "" }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AaiSession>() {
            override fun areItemsTheSame(a: AaiSession, b: AaiSession) = a.sessionId == b.sessionId
            override fun areContentsTheSame(a: AaiSession, b: AaiSession) = a == b
        }
    }
}
