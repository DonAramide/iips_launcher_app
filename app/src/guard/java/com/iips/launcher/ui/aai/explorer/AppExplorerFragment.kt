package com.iips.launcher.ui.aai.explorer

import android.content.Intent
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
import com.iips.launcher.aai.viewmodel.AaiAppExplorerViewModel
import com.iips.launcher.aai.viewmodel.UiState
import com.iips.launcher.databinding.FragmentAaiListBinding
import com.iips.launcher.databinding.ItemAaiAppBinding
import com.iips.launcher.network.models.AaiInstalledApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AppExplorerFragment : Fragment() {

    private var _binding: FragmentAaiListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AaiAppExplorerViewModel by viewModels()
    private lateinit var adapter: AppAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAaiListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupSearch()
        observeState()
    }

    private fun setupRecycler() {
        adapter = AppAdapter { app ->
            startActivity(
                Intent(requireContext(), AppDetailActivity::class.java).apply {
                    putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, app.packageName)
                    putExtra(AppDetailActivity.EXTRA_DEVICE_ID, app.deviceId)
                }
            )
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // Load more on scroll end
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    viewModel.loadApps(loadMore = true)
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Filter locally on the current list
                val query = s?.toString()?.trim() ?: ""
                adapter.filter(query)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.apps.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    when (state) {
                        is UiState.Loading -> {
                            binding.shimmerContainer.visibility = View.VISIBLE
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.GONE
                        }
                        is UiState.Success -> {
                            binding.shimmerContainer.visibility = View.GONE
                            binding.recyclerView.visibility = View.VISIBLE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.GONE
                            adapter.setFullList(state.data.data)
                        }
                        is UiState.Empty -> {
                            binding.shimmerContainer.visibility = View.GONE
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.VISIBLE
                            binding.tvEmptyMessage.text = "No applications found"
                        }
                        is UiState.Error -> {
                            binding.shimmerContainer.visibility = View.GONE
                            binding.recyclerView.visibility = View.GONE
                            binding.emptyState.visibility = View.GONE
                            binding.errorState.visibility = View.VISIBLE
                            binding.tvErrorMessage.text = state.message
                        }
                    }
                }
            }
        }
        binding.btnRetry.setOnClickListener { viewModel.refresh() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App List Adapter with local filter support
// ─────────────────────────────────────────────────────────────────────────────

class AppAdapter(private val onClick: (AaiInstalledApp) -> Unit) :
    ListAdapter<AaiInstalledApp, AppAdapter.VH>(DIFF) {

    private var fullList: List<AaiInstalledApp> = emptyList()

    fun setFullList(list: List<AaiInstalledApp>) {
        fullList = list
        submitList(list)
    }

    fun filter(query: String) {
        val filtered = if (query.isBlank()) fullList
        else fullList.filter {
            it.appName.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
        submitList(filtered)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAaiAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemAaiAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AaiInstalledApp) {
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName
            binding.tvCategory.text = app.category ?: "Unknown"
            binding.tvDeviceName.text = app.deviceName ?: app.deviceId
            binding.tvRunningBadge.visibility = if (app.isRunning) View.VISIBLE else View.GONE

            app.riskLevel?.let { risk ->
                binding.tvRiskTag.text = risk
                val color = when (risk) {
                    "CRITICAL" -> ContextCompat.getColor(binding.root.context, com.iips.launcher.R.color.aai_severity_critical)
                    "HIGH"     -> ContextCompat.getColor(binding.root.context, com.iips.launcher.R.color.aai_severity_high)
                    "MEDIUM"   -> ContextCompat.getColor(binding.root.context, com.iips.launcher.R.color.aai_severity_medium)
                    else       -> ContextCompat.getColor(binding.root.context, com.iips.launcher.R.color.aai_severity_low)
                }
                binding.tvRiskTag.setBackgroundColor(color)
            }

            binding.root.setOnClickListener { onClick(app) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AaiInstalledApp>() {
            override fun areItemsTheSame(a: AaiInstalledApp, b: AaiInstalledApp) = a.id == b.id
            override fun areContentsTheSame(a: AaiInstalledApp, b: AaiInstalledApp) = a == b
        }
    }
}
