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
import com.iips.launcher.config.GeofenceZone
import com.iips.launcher.databinding.ActivityGeofenceEnrollmentBinding
import com.iips.launcher.databinding.ItemProposedZoneBinding
import com.iips.launcher.device.GeofenceManager
import com.iips.launcher.utils.SecurePreferences
import kotlinx.coroutines.launch

class GeofenceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeofenceEnrollmentBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val proposedZones = mutableListOf<GeofenceZone>()
    private lateinit var zoneAdapter: ProposedZoneAdapter
    private var currentLocation: Location? = null

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

        // Load existing proposals if any
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
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
            return
        }

        val name = binding.zoneNameEdit.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a name for this zone", Toast.LENGTH_SHORT).show()
            return
        }

        if (proposedZones.size >= 3) {
            Toast.makeText(this, "Maximum 3 zones allowed", Toast.LENGTH_SHORT).show()
            return
        }

        val newZone = GeofenceZone(
            name = name,
            lat = loc.latitude,
            lng = loc.longitude,
            radius = 150f,
            status = "pending"
        )

        proposedZones.add(newZone)
        zoneAdapter.notifyDataSetChanged()
        binding.zoneNameEdit.text?.clear()
        updateSubmitButton()
    }

    private fun updateSubmitButton() {
        binding.btnSubmitProposal.isEnabled = proposedZones.isNotEmpty()
    }

    private fun submitProposal() {
        lifecycleScope.launch {
            binding.btnSubmitProposal.isEnabled = false
            binding.btnSubmitProposal.text = "Submitting..."
            
            try {
                GeofenceManager.submitProposal(this@GeofenceEnrollmentActivity, proposedZones)
                Toast.makeText(this@GeofenceEnrollmentActivity, "Proposal submitted for admin approval", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@GeofenceEnrollmentActivity, "Failed to submit: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.btnSubmitProposal.isEnabled = true
                binding.btnSubmitProposal.text = "Submit for Approval"
            }
        }
    }

    inner class ProposedZoneAdapter(
        private val zones: List<GeofenceZone>,
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
            holder.binding.zoneDetails.text = "Lat: ${String.format("%.4f", zone.lat)}, Lng: ${String.format("%.4f", zone.lng)} (150m)"
            holder.binding.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = zones.size
    }
}
