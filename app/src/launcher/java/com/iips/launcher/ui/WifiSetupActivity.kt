package com.iips.launcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.iips.launcher.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WifiSetupActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WifiAdapter
    private lateinit var progressScan: View
    private lateinit var switchWifiPower: SwitchCompat
    private lateinit var tvWifiPowerDesc: TextView
    private lateinit var cardConnectedWifi: View
    private lateinit var tvConnectedSsid: TextView
    private lateinit var tvConnectedIp: TextView
    private lateinit var tvWifiEmpty: TextView
    private lateinit var ivWifiPowerIcon: ImageView

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                scanSuccess()
            } else {
                scanFailure()
            }
            updateConnectedNetworkUi()
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == WifiManager.WIFI_STATE_CHANGED_ACTION ||
                action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                updateWifiPowerStateUi()
                updateConnectedNetworkUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("WifiSetupActivity", "onCreate() started")
        applyImmersiveMode()
        setContentView(R.layout.activity_wifi_setup)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        initViews()
        setupListeners()
        updateWifiPowerStateUi()
        updateConnectedNetworkUi()

        val scanFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, scanFilter)

        val stateFilter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        registerReceiver(wifiStateReceiver, stateFilter)

        if (wifiManager.isWifiEnabled) {
            startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (com.iips.launcher.policy.DeviceAdminReceiver.isDeviceOwner(this)) {
            com.iips.launcher.policy.DeviceController.setStatusBarLocked(this, true)
        }
        updateWifiPowerStateUi()
        updateConnectedNetworkUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            if (com.iips.launcher.policy.DeviceAdminReceiver.isDeviceOwner(this)) {
                com.iips.launcher.policy.DeviceController.setStatusBarLocked(this, true)
            }
        } else {
            try {
                val statusBarService = getSystemService("statusbar")
                val statusBarManager = Class.forName("android.app.StatusBarManager")
                val collapse = statusBarManager.getMethod("collapsePanels")
                collapse.invoke(statusBarService)
            } catch (_: Exception) {}
            try {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) {}
        }
    }

    private fun returnToHome() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        returnToHome()
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.rv_wifi_networks)
        progressScan = findViewById(R.id.wifi_scan_progress)
        switchWifiPower = findViewById(R.id.switch_wifi_power)
        tvWifiPowerDesc = findViewById(R.id.tv_wifi_power_desc)
        cardConnectedWifi = findViewById(R.id.card_connected_wifi)
        tvConnectedSsid = findViewById(R.id.tv_connected_ssid)
        tvConnectedIp = findViewById(R.id.tv_connected_ip)
        tvWifiEmpty = findViewById(R.id.tv_wifi_empty)
        ivWifiPowerIcon = findViewById(R.id.iv_wifi_power_icon)

        adapter = WifiAdapter { scanResult ->
            val isSecured = isNetworkSecured(scanResult)
            if (isSecured) {
                showPasswordDialog(scanResult)
            } else {
                connectToOpenWifi(scanResult.SSID)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btn_wifi_back).setOnClickListener { returnToHome() }
        findViewById<View>(R.id.btn_wifi_back_top).setOnClickListener { returnToHome() }
        findViewById<View>(R.id.btn_wifi_refresh).setOnClickListener { startScan() }
    }

    private var isProgrammaticChange = false

    private fun setupListeners() {
        switchWifiPower.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            setWifiPower(isChecked)
        }
    }

    private fun setWifiPower(enable: Boolean) {
        try {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
        } catch (_: Exception) {
            // In Android Q+, if regular call restricted, try reflection
            try {
                val method = wifiManager.javaClass.getDeclaredMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
                method.invoke(wifiManager, enable)
            } catch (_: Exception) {}
        }

        recyclerView.postDelayed({
            updateWifiPowerStateUi()
            if (enable) {
                startScan()
            } else {
                adapter.submitList(emptyList())
                tvWifiEmpty.visibility = View.VISIBLE
                tvWifiEmpty.text = "Wi-Fi is turned off.\nTurn on Wi-Fi to see available networks."
                cardConnectedWifi.visibility = View.GONE
            }
        }, 500)
    }

    private fun updateWifiPowerStateUi() {
        val enabled = wifiManager.isWifiEnabled
        if (switchWifiPower.isChecked != enabled) {
            isProgrammaticChange = true
            switchWifiPower.isChecked = enabled
            isProgrammaticChange = false
        }

        if (enabled) {
            tvWifiPowerDesc.text = "Scanning for available networks"
            ivWifiPowerIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary))
        } else {
            tvWifiPowerDesc.text = "Wi-Fi is turned off"
            ivWifiPowerIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan()
        }
    }

    private fun updateConnectedNetworkUi() {
        if (!wifiManager.isWifiEnabled) {
            cardConnectedWifi.visibility = View.GONE
            return
        }

        val info = wifiManager.connectionInfo
        val ssid = info?.ssid?.removeSurrounding("\"")
        if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>" && info.networkId != -1) {
            cardConnectedWifi.visibility = View.VISIBLE
            tvConnectedSsid.text = ssid
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(info.ipAddress)
            tvConnectedIp.text = if (ip != "0.0.0.0") "IP: $ip" else "Obtaining IP address..."
        } else {
            cardConnectedWifi.visibility = View.GONE
        }
    }

    private fun startScan() {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Please enable Wi-Fi first", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        progressScan.visibility = View.VISIBLE
        tvWifiEmpty.visibility = View.GONE
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

        if (sortedResults.isEmpty()) {
            tvWifiEmpty.visibility = View.VISIBLE
            tvWifiEmpty.text = "No Wi-Fi networks found.\nTap 'Scan' to refresh."
        } else {
            tvWifiEmpty.visibility = View.GONE
        }
    }

    private fun scanFailure() {
        progressScan.visibility = View.GONE
        if (adapter.itemCount == 0) {
            tvWifiEmpty.visibility = View.VISIBLE
            tvWifiEmpty.text = "Wi-Fi scan failed or is throttled.\nTap 'Scan' to retry."
        }
    }

    private fun isNetworkSecured(scanResult: ScanResult): Boolean {
        val cap = scanResult.capabilities
        return cap.contains("WPA") || cap.contains("WEP") || cap.contains("PSK") || cap.contains("EAP")
    }

    private fun showPasswordDialog(scanResult: ScanResult) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connect to ${scanResult.SSID}")

        val input = EditText(this)
        input.hint = "Password"
        input.setPadding(48, 32, 48, 32)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        builder.setView(input)

        builder.setPositiveButton("Connect") { _, _ ->
            val password = input.text.toString()
            connectToSecuredWifi(scanResult.SSID, password)
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun connectToSecuredWifi(ssid: String, password: String) {
        Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()

        // 1. Android 10+ Suggestions API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()
                wifiManager.addNetworkSuggestions(listOf(suggestion))
            } catch (_: Exception) {}
        }

        // 2. Legacy / Device Owner WifiConfiguration
        try {
            val wifiConfig = WifiConfiguration()
            wifiConfig.SSID = String.format("\"%s\"", ssid)
            wifiConfig.preSharedKey = String.format("\"%s\"", password)

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
            }
        } catch (_: Exception) {}

        // Check connection after delay
        recyclerView.postDelayed({
            updateConnectedNetworkUi()
        }, 3000)
    }

    private fun connectToOpenWifi(ssid: String) {
        Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .build()
                wifiManager.addNetworkSuggestions(listOf(suggestion))
            } catch (_: Exception) {}
        }

        try {
            val wifiConfig = WifiConfiguration()
            wifiConfig.SSID = String.format("\"%s\"", ssid)
            wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
            }
        } catch (_: Exception) {}

        recyclerView.postDelayed({
            updateConnectedNetworkUi()
        }, 3000)
    }

    override fun onDestroy() {
        android.util.Log.i("WifiSetupActivity", "onDestroy() isFinishing=$isFinishing")
        super.onDestroy()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wifiStateReceiver) } catch (_: Exception) {}
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

            holder.signalIcon.setImageResource(R.drawable.ic_qs_wifi)
            holder.signalIcon.setColorFilter(ContextCompat.getColor(this@WifiSetupActivity, R.color.primary))

            val capabilities = item.capabilities
            val isSecured = capabilities.contains("WPA") || capabilities.contains("WEP") || capabilities.contains("PSK")
            holder.lockIcon.visibility = if (isSecured) View.VISIBLE else View.GONE

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
