package com.iips.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.iips.launcher.network.ConfigService
import com.iips.launcher.network.models.GeofenceRule
import com.iips.launcher.network.models.MdmEventRequest
import com.iips.launcher.databinding.ActivityGeofenceEnrollmentBinding
import com.iips.launcher.databinding.ItemProposedZoneBinding
import com.iips.launcher.storage.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeofenceEnrollmentBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val proposedZones = mutableListOf<GeofenceRule>()
    private lateinit var zoneAdapter: ProposedZoneAdapter
    private var currentLocation: Location? = null

    @Inject
    lateinit var configService: ConfigService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeofenceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupRecyclerView()
        startLocationUpdates()

        binding.btnAddLocation.setOnClickListener {
            addCurrentLocation()
        }

        binding.btnSubmitProposal.setOnClickListener {
            submitProposal()
        }

        val savedProposals = SecurePreferences.getProposedZones(this)
        proposedZones.addAll(savedProposals)
        zoneAdapter.notifyDataSetChanged()
        updateSubmitButton()
    }

    private fun setupRecyclerView() {
        zoneAdapter = ProposedZoneAdapter(proposedZones) { index ->
            proposedZones.removeAt(index)
            zoneAdapter.notifyDataSetChanged()
            updateSubmitButton()
        }
        binding.proposedZonesRecycler.layoutManager = LinearLayoutManager(this)
        binding.proposedZonesRecycler.adapter = zoneAdapter
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                currentLocation = result.lastLocation
                currentLocation?.let {
                    binding.coordinatesText.text = "Lat: ${String.format("%.6f", it.latitude)}, Lng: ${String.format("%.6f", it.longitude)}"
                }
            }
        }, mainLooper)
    }

    private fun addCurrentLocation() {
        val loc = currentLocation
        binding.zoneNameLayout.error = null
        binding.zoneRadiusLayout.error = null

        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
            return
        }

        val name = binding.zoneNameEdit.text.toString().trim()
        if (name.isEmpty()) {
            binding.zoneNameLayout.error = "Please enter a name"
            return
        }

        val radiusStr = binding.zoneRadiusEdit.text.toString().trim()
        val radius = radiusStr.toFloatOrNull() ?: 0f
        if (radius < 50) {
            binding.zoneRadiusLayout.error = "Minimum radius is 50m"
            return
        }

        if (proposedZones.size >= 3) {
            Toast.makeText(this, "Maximum 3 zones allowed", Toast.LENGTH_SHORT).show()
            return
        }

        val newZone = GeofenceRule(
            id = "proposed-${System.currentTimeMillis()}",
            name = name,
            lat = loc.latitude,
            lng = loc.longitude,
            radius_m = radius.toDouble()
        )

        proposedZones.add(newZone)
        zoneAdapter.notifyDataSetChanged()
        binding.zoneNameEdit.text?.clear()
        binding.zoneRadiusEdit.setText("150")
        updateSubmitButton()
    }

    private fun updateSubmitButton() {
        binding.btnSubmitProposal.isEnabled = proposedZones.isNotEmpty()
    }

    private fun submitProposal() {
        if (proposedZones.isEmpty()) return

        lifecycleScope.launch {
            try {
                binding.btnSubmitProposal.isEnabled = false
                binding.btnSubmitProposal.text = "Submitting..."
                
                val token = SecurePreferences.getDeviceToken(this@GeofenceEnrollmentActivity)
                if (token != null) {
                    val payload = mapOf("zones" to proposedZones)
                    val request = MdmEventRequest(type = "GEOFENCE_PROPOSAL", payload = payload)
                    
                    val response = configService.sendEvent("Bearer $token", request)
                    if (response.isSuccessful) {
                        SecurePreferences.setProposedZones(this@GeofenceEnrollmentActivity, proposedZones)
                        Toast.makeText(this@GeofenceEnrollmentActivity, "Proposal submitted for admin approval", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        throw Exception("Server error: \${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@GeofenceEnrollmentActivity, "Failed to submit: \${e.message}", Toast.LENGTH_LONG).show()
                binding.btnSubmitProposal.isEnabled = true
                binding.btnSubmitProposal.text = "Submit for Approval"
            }
        }
    }

    inner class ProposedZoneAdapter(
        private val zones: List<GeofenceRule>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<ProposedZoneAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemProposedZoneBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProposedZoneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val zone = zones[position]
            holder.binding.zoneName.text = zone.name
            holder.binding.zoneDetails.text = "Lat: \${String.format(\"%.4f\", zone.lat)}, Lng: \${String.format(\"%.4f\", zone.lng)} (\${zone.radius_m.toInt()}m)"
            holder.binding.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = zones.size
    }
}
