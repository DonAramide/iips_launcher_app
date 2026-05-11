package com.iips.launcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iips.launcher.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WifiSetupActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WifiAdapter
    private lateinit var progressScan: View
    
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                scanSuccess()
            } else {
                scanFailure()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_setup)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        recyclerView = findViewById(R.id.rv_wifi_networks)
        progressScan = findViewById(R.id.wifi_scan_progress)
        
        adapter = WifiAdapter { scanResult ->
            showPasswordDialog(scanResult)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_wifi_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_wifi_refresh).setOnClickListener { startScan() }

        startScan()
    }

    private fun startScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        progressScan.visibility = View.VISIBLE
        wifiManager.startScan()
    }

    private fun scanSuccess() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        val results = wifiManager.scanResults
        val sortedResults = results.filter { it.SSID.isNotEmpty() }
            .distinctBy { it.SSID }
            .sortedByDescending { it.level }
        
        adapter.submitList(sortedResults)
        progressScan.visibility = View.GONE
    }

    private fun scanFailure() {
        progressScan.visibility = View.GONE
        Toast.makeText(this, "Wifi scan failed. Try again.", Toast.LENGTH_SHORT).show()
    }

    private fun showPasswordDialog(scanResult: ScanResult) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connect to ${scanResult.SSID}")
        
        val input = EditText(this)
        input.hint = "Password"
        input.setPadding(48, 32, 48, 32)
        builder.setView(input)

        builder.setPositiveButton("Connect") { _, _ ->
            val password = input.text.toString()
            connectToWifi(scanResult.SSID, password)
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun connectToWifi(ssid: String, password: String) {
        Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()
        
        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = String.format("\"%s\"", ssid)
        wifiConfig.preSharedKey = String.format("\"%s\"", password)

        val netId = wifiManager.addNetwork(wifiConfig)
        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
        
        // Post-connection check (briefly wait and finish if successful)
        recyclerView.postDelayed({
            if (wifiManager.connectionInfo.networkId != -1) {
                Toast.makeText(this, "Connected successfully", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wifiScanReceiver) } catch (e: Exception) {}
    }

    inner class WifiAdapter(private val onClick: (ScanResult) -> Unit) : RecyclerView.Adapter<WifiAdapter.ViewHolder>() {
        private var items = listOf<ScanResult>()

        fun submitList(newItems: List<ScanResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi_network, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ssid.text = item.SSID
            
            val resId = android.R.drawable.ic_menu_directions
            holder.signalIcon.setImageResource(resId)
            
            val capabilities = item.capabilities
            holder.lockIcon.visibility = if (capabilities.contains("WPA") || capabilities.contains("WEP")) View.VISIBLE else View.GONE
            
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ssid: TextView = view.findViewById(R.id.wifi_ssid)
            val signalIcon: ImageView = view.findViewById(R.id.wifi_signal_icon)
            val lockIcon: ImageView = view.findViewById(R.id.wifi_lock_icon)
        }
    }
}
