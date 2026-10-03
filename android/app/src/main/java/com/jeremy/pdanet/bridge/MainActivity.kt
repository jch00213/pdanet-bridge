package com.jeremy.pdanet.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    // Wi-Fi Direct Manager handles
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startBridgeServices()
        } else {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // Initialize Wi-Fi Direct P2P Framework
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(this, mainLooper, null)

        // Mode Radio Group
        val radioShizuku = RadioButton(this).apply { text = "Shizuku System Hotspot (SoftAP)" }
        val radioWifiDirect = RadioButton(this).apply { text = "Wi-Fi Direct Group (P2P)" }
        val radioVpn = RadioButton(this).apply { text = "VPN Mode (Local Proxy/TUN)" }

        val modeRadioGroup = RadioGroup(this).apply {
            addView(radioShizuku)
            addView(radioWifiDirect)
            addView(radioVpn)
        }

        // Tailscale Controls
        val chkTailscale = CheckBox(this).apply {
            text = "Enable Tailscale Mesh (tsnet)"
            isChecked = prefs.enableTailscale
        }

        val edtAuthKey = EditText(this).apply {
            hint = "Tailscale Auth Key (tskey-...)"
            setText(prefs.tailscaleAuthKey)
            isEnabled = prefs.enableTailscale
        }

        // Action Buttons
        val btnStart = Button(this).apply { text = "Start Service & Tethering" }
        val btnStop = Button(this).apply { text = "Stop Service" }

        // Set radio state from preferences
        when (prefs.networkMode) {
            NetworkMode.SHIZUKU -> radioShizuku.isChecked = true
            NetworkMode.WIFI_DIRECT -> radioWifiDirect.isChecked = true
            NetworkMode.VPN -> radioVpn.isChecked = true
        }

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.networkMode = when (checkedId) {
                radioShizuku.id -> NetworkMode.SHIZUKU
                radioWifiDirect.id -> NetworkMode.WIFI_DIRECT
                else -> NetworkMode.VPN
            }
        }

        chkTailscale.setOnCheckedChangeListener { _, isChecked ->
            prefs.enableTailscale = isChecked
            edtAuthKey.isEnabled = isChecked
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(modeRadioGroup)
            addView(chkTailscale)
            addView(edtAuthKey)
            addView(btnStart)
            addView(btnStop)
        }
        setContentView(layout)

        btnStart.setOnClickListener {
            prefs.tailscaleAuthKey = edtAuthKey.text.toString().trim()

            when (prefs.networkMode) {
                NetworkMode.SHIZUKU -> {
                    if (!ShizukuBridge.isShizukuReady()) {
                        requestShizukuPermission()
                        return@setOnClickListener
                    }
                    if (ShizukuBridge.enableSystemTethering()) {
                        startBridgeServices()
                    } else {
                        Toast.makeText(this, "Failed to start SoftAP via Shizuku", Toast.LENGTH_SHORT).show()
                    }
                }
                NetworkMode.WIFI_DIRECT -> {
                    if (checkP2pPermissions()) {
                        startWifiDirectGroup()
                    }
                }
                NetworkMode.VPN -> {
                    val vpnIntent = VpnService.prepare(this)
                    if (vpnIntent != null) {
                        vpnLauncher.launch(vpnIntent)
                    } else {
                        startBridgeServices()
                    }
                }
            }
        }

        btnStop.setOnClickListener {
            stopBridgeServices()
        }
    }

    private fun startWifiDirectGroup() {
        if (p2pManager == null || p2pChannel == null) {
            Toast.makeText(this, "Wi-Fi Direct unavailable on this device", Toast.LENGTH_SHORT).show()
            return
        }

        p2pManager?.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Wi-Fi Direct Group Created!", Toast.LENGTH_SHORT).show()
                startBridgeServices()
            }

            override fun onFailure(reason: Int) {
                val errorMsg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
                    WifiP2pManager.BUSY -> "Framework Busy"
                    WifiP2pManager.ERROR -> "Internal Error"
                    else -> "Unknown Error ($reason)"
                }
                Log.e("WifiDirect", "Failed to create group: $errorMsg")
                Toast.makeText(this@MainActivity, "Wi-Fi Direct Error: $errorMsg", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun stopBridgeServices() {
        // Tear down Wi-Fi Direct Group if active
        if (prefs.networkMode == NetworkMode.WIFI_DIRECT && p2pManager != null && p2pChannel != null) {
            p2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Wi-Fi Direct Group Removed")
                }
                override fun onFailure(reason: Int) {
                    Log.e("WifiDirect", "Failed to remove group ($reason)")
                }
            })
        }

        stopService(Intent(this, ProxyService::class.java))
        stopService(Intent(this, BridgeVpnService::class.java))
        Toast.makeText(this, "Bridge Services Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startBridgeServices() {
        val proxyIntent = Intent(this, ProxyService::class.java)
        startForegroundService(proxyIntent)

        if (prefs.networkMode == NetworkMode.VPN) {
            val vpnIntent = Intent(this, BridgeVpnService::class.java)
            startService(vpnIntent)
        }

        Toast.makeText(this, "Proxy Engine & Services Active", Toast.LENGTH_SHORT).show()
    }

    private fun checkP2pPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), P2P_PERM_REQUEST_CODE)
                return false
            }
        } else if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), P2P_PERM_REQUEST_CODE)
            return false
        }
        return true
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku v11+ required", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku manager is not running", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001
        private const val P2P_PERM_REQUEST_CODE = 7002
    }
}
