package com.iips.launcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.iips.launcher.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HotspotSetupActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var switchHotspot: SwitchCompat
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvHotspotDesc: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvPassword: TextView
    private lateinit var btnTogglePassword: MaterialButton
    private lateinit var btnCopyPassword: MaterialButton
    private lateinit var ivHotspotIcon: ImageView

    private var hotspotReservation: Any? = null // WifiManager.LocalOnlyHotspotReservation
    private var currentPassword: String = ""
    private var isPasswordVisible: Boolean = false
    private var isProgrammaticChange: Boolean = false

    private fun setSwitchCheckedSilently(checked: Boolean) {
        if (switchHotspot.isChecked == checked) return
        isProgrammaticChange = true
        switchHotspot.isChecked = checked
        isProgrammaticChange = false
    }

    private val hotspotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", 0)
                handleApState(state)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("HotspotSetupActivity", "onCreate() started")
        applyImmersiveMode()
        setContentView(R.layout.activity_hotspot_setup)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        initViews()
        setupListeners()
        loadInitialState()

        val filter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        registerReceiver(hotspotStateReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        if (com.iips.launcher.policy.DeviceAdminReceiver.isDeviceOwner(this)) {
            com.iips.launcher.policy.DeviceController.setStatusBarLocked(this, true)
        }
        updateHotspotStatusUi()
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
        switchHotspot = findViewById(R.id.switch_hotspot)
        tvStatusBadge = findViewById(R.id.tv_hotspot_status_badge)
        tvHotspotDesc = findViewById(R.id.tv_hotspot_desc)
        tvSsid = findViewById(R.id.tv_hotspot_ssid)
        tvPassword = findViewById(R.id.tv_hotspot_password)
        btnTogglePassword = findViewById(R.id.btn_toggle_password)
        btnCopyPassword = findViewById(R.id.btn_copy_password)
        ivHotspotIcon = findViewById(R.id.iv_hotspot_icon)

        findViewById<View>(R.id.btn_hotspot_back).setOnClickListener { returnToHome() }
        findViewById<View>(R.id.btn_hotspot_back_top).setOnClickListener { returnToHome() }
    }

    private fun setupListeners() {
        switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (isChecked) {
                startHotspot()
            } else {
                stopHotspot()
            }
        }

        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            renderPassword()
        }

        btnCopyPassword.setOnClickListener {
            if (currentPassword.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Hotspot Password", currentPassword)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Password copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No password available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadInitialState() {
        // Read system configured SSID/Password if available
        readSystemApConfig()

        val active = isHotspotActive()
        setSwitchCheckedSilently(active)
        updateUiForState(active)
    }

    private fun readSystemApConfig() {
        try {
            val method = wifiManager.javaClass.getDeclaredMethod("getWifiApConfiguration")
            val config = method.invoke(wifiManager) as? WifiConfiguration
            if (config != null) {
                if (!config.SSID.isNullOrEmpty()) {
                    tvSsid.text = config.SSID.removeSurrounding("\"")
                }
                if (!config.preSharedKey.isNullOrEmpty()) {
                    currentPassword = config.preSharedKey.removeSurrounding("\"")
                    renderPassword()
                }
            }
        } catch (_: Exception) {
            // Non-accessible on some Android 10+ devices without reservation
        }

        if (currentPassword.isEmpty()) {
            currentPassword = "iips" + (1000..9999).random()
            renderPassword()
        }
    }

    private fun renderPassword() {
        if (isPasswordVisible) {
            tvPassword.text = currentPassword
            btnTogglePassword.text = "Hide"
        } else {
            tvPassword.text = if (currentPassword.isNotEmpty()) "••••••••" else "None"
            btnTogglePassword.text = "Show"
        }
    }

    private fun startHotspot() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 201)
            setSwitchCheckedSilently(false)
            return
        }

        tvHotspotDesc.text = "Starting hotspot..."
        tvStatusBadge.text = "STARTING..."
        tvStatusBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
        tvStatusBadge.setTextColor(Color.parseColor("#B45309"))

        var started = false

        // Attempt official LocalOnlyHotspot (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        super.onStarted(reservation)
                        hotspotReservation = reservation
                        extractReservationCredentials(reservation)
                        updateUiForState(true)
                    }

                    override fun onStopped() {
                        super.onStopped()
                        hotspotReservation = null
                        updateUiForState(false)
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        hotspotReservation = null
                        // If local hotspot failed, attempt reflection fallback
                        tryReflectionStartAp()
                    }
                }, Handler(Looper.getMainLooper()))
                started = true
            } catch (e: Exception) {
                started = false
            }
        }

        if (!started) {
            tryReflectionStartAp()
        }
    }

    private fun tryReflectionStartAp() {
        try {
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wifiManager, null, true)
            updateUiForState(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to activate hotspot on this device", Toast.LENGTH_SHORT).show()
            setSwitchCheckedSilently(false)
            updateUiForState(false)
        }
    }

    private fun stopHotspot() {
        tvHotspotDesc.text = "Stopping hotspot..."

        // Close LocalOnlyHotspot reservation if active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hotspotReservation != null) {
            try {
                val res = hotspotReservation as? WifiManager.LocalOnlyHotspotReservation
                res?.close()
            } catch (_: Exception) {}
            hotspotReservation = null
        }

        // Try reflection stop
        try {
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wifiManager, null, false)
        } catch (_: Exception) {}

        updateUiForState(false)
    }

    private fun extractReservationCredentials(reservation: Any?) {
        if (reservation == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val res = reservation as? WifiManager.LocalOnlyHotspotReservation
                val softApConfig = res?.softApConfiguration
                if (softApConfig != null) {
                    val ssid = softApConfig.ssid
                    val pass = softApConfig.passphrase
                    if (!ssid.isNullOrEmpty()) tvSsid.text = ssid
                    if (!pass.isNullOrEmpty()) {
                        currentPassword = pass
                        renderPassword()
                    }
                    return
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val res = reservation as? WifiManager.LocalOnlyHotspotReservation
                val wifiConfig = res?.wifiConfiguration
                if (wifiConfig != null) {
                    if (!wifiConfig.SSID.isNullOrEmpty()) {
                        tvSsid.text = wifiConfig.SSID.removeSurrounding("\"")
                    }
                    if (!wifiConfig.preSharedKey.isNullOrEmpty()) {
                        currentPassword = wifiConfig.preSharedKey.removeSurrounding("\"")
                        renderPassword()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleApState(state: Int) {
        when (state) {
            13 -> { // WIFI_AP_STATE_ENABLED
                setSwitchCheckedSilently(true)
                updateUiForState(true)
            }
            12 -> { // WIFI_AP_STATE_ENABLING
                tvHotspotDesc.text = "Starting hotspot..."
                tvStatusBadge.text = "STARTING"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                tvStatusBadge.setTextColor(Color.parseColor("#B45309"))
            }
            11 -> { // WIFI_AP_STATE_DISABLED
                setSwitchCheckedSilently(false)
                updateUiForState(false)
            }
            10 -> { // WIFI_AP_STATE_DISABLING
                tvHotspotDesc.text = "Stopping hotspot..."
                tvStatusBadge.text = "STOPPING"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#F1F5F9"))
                tvStatusBadge.setTextColor(Color.parseColor("#64748B"))
            }
            14 -> { // WIFI_AP_STATE_FAILED
                setSwitchCheckedSilently(false)
                updateUiForState(false)
                Toast.makeText(this, "Hotspot state failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isHotspotActive(): Boolean {
        if (hotspotReservation != null) return true
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun updateHotspotStatusUi() {
        val active = isHotspotActive()
        setSwitchCheckedSilently(active)
        updateUiForState(active)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setSwitchCheckedSilently(true)
            startHotspot()
        }
    }

    private fun updateUiForState(active: Boolean) {
        if (active) {
            tvHotspotDesc.text = "Hotspot is active and discoverable"
            tvStatusBadge.text = "ACTIVE"
            tvStatusBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
            tvStatusBadge.setTextColor(Color.parseColor("#15803D"))
            ivHotspotIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary))
        } else {
            tvHotspotDesc.text = "Hotspot is turned off"
            tvStatusBadge.text = "INACTIVE"
            tvStatusBadge.setBackgroundColor(Color.parseColor("#F1F5F9"))
            tvStatusBadge.setTextColor(Color.parseColor("#64748B"))
            ivHotspotIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    override fun onDestroy() {
        android.util.Log.i("HotspotSetupActivity", "onDestroy() isFinishing=$isFinishing")
        super.onDestroy()
        try {
            unregisterReceiver(hotspotStateReceiver)
        } catch (_: Exception) {}

        if (hotspotReservation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                (hotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close()
            } catch (_: Exception) {}
            hotspotReservation = null
        }
    }
}
