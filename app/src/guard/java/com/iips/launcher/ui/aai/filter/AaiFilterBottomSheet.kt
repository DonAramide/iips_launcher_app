package com.iips.launcher.ui.aai.filter

import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.iips.launcher.aai.filter.AaiFilterPreferences
import com.iips.launcher.databinding.BottomSheetAaiFilterBinding
import com.iips.launcher.network.models.AaiFilterParams
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AaiFilterBottomSheet : BottomSheetDialogFragment() {

    @Inject lateinit var filterPrefs: AaiFilterPreferences

    private var _binding: BottomSheetAaiFilterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAaiFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill last-used filters
        val current = filterPrefs.loadFilters()
        binding.etDeviceId.setText(current.deviceId ?: "")
        binding.etUserId.setText(current.userId ?: "")        // R2
        binding.etPackageName.setText(current.packageName ?: "")
        binding.etRiskLevel.setText(current.riskLevel ?: "")
        binding.etCategory.setText(current.category ?: "")
        binding.etCorrelationId.setText(current.correlationId ?: "")
        binding.etSessionId.setText(current.sessionId ?: "")
        binding.etDateFrom.setText(current.dateFrom ?: "")
        binding.etDateTo.setText(current.dateTo ?: "")

        // Trust score seekbar (R2) — stored as 0.0–1.0, seekbar max=100
        val initialTrust = current.trustMin ?: 0f
        val initialProgress = (initialTrust * 100).toInt()
        binding.seekTrustMin.progress = initialProgress
        updateTrustLabel(initialProgress)

        binding.seekTrustMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateTrustLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.btnApply.setOnClickListener {
            val trustProgress = binding.seekTrustMin.progress
            val trustMin: Float? = if (trustProgress > 0) trustProgress / 100f else null

            val params = AaiFilterParams(
                deviceId      = binding.etDeviceId.text?.toString()?.takeIf { it.isNotBlank() },
                userId        = binding.etUserId.text?.toString()?.takeIf { it.isNotBlank() },   // R2
                packageName   = binding.etPackageName.text?.toString()?.takeIf { it.isNotBlank() },
                riskLevel     = binding.etRiskLevel.text?.toString()?.uppercase()?.takeIf { it.isNotBlank() },
                category      = binding.etCategory.text?.toString()?.takeIf { it.isNotBlank() },
                trustMin      = trustMin,                                                          // R2
                correlationId = binding.etCorrelationId.text?.toString()?.takeIf { it.isNotBlank() },
                sessionId     = binding.etSessionId.text?.toString()?.takeIf { it.isNotBlank() },
                dateFrom      = binding.etDateFrom.text?.toString()?.takeIf { it.isNotBlank() },
                dateTo        = binding.etDateTo.text?.toString()?.takeIf { it.isNotBlank() }
            )
            filterPrefs.saveFilters(params)
            dismiss()
        }

        binding.btnClear.setOnClickListener {
            filterPrefs.clearFilters()
            binding.seekTrustMin.progress = 0
            updateTrustLabel(0)
            dismiss()
        }
    }

    private fun updateTrustLabel(progress: Int) {
        binding.tvTrustValue.text = if (progress == 0) "Any" else "≥ ${progress}%"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
