package com.jeremy.pdanet.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private lateinit var prefs: AppPreferences

    // Wi-Fi Direct Handles
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: WifiP2pBroadcastReceiver? = null
    private lateinit var intentFilter: IntentFilter

    // Dynamic UI Elements for P2P Info
    private lateinit var p2pInfoContainer: LinearLayout
    private lateinit var tvSsid: TextView
    private lateinit var tvPassphrase: TextView
    private lateinit var tvGatewayIp: TextView

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

        // Initialize P2P Framework
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2pManager?.initialize(this, mainLooper, null)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        // Mode Radio Buttons
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

        // P2P Info Display Container
        tvSsid = TextView(this).apply { text = "SSID: --" }
        tvPassphrase = TextView(this).apply { text = "Passphrase: --" }
        tvGatewayIp = TextView(this).apply { text = "Proxy Gateway IP: 192.168.49.1:8080" }

        p2pInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
            addView(tvSsid)
            addView(tvPassphrase)
            addView(tvGatewayIp)
        }

        // Action Buttons
        val btnStart = Button(this).apply { text = "Start Service & Tethering" }
        val btnStop = Button(this).apply { text = "Stop Service" }

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
            p2pInfoContainer.visibility = if (prefs.networkMode == NetworkMode.WIFI_DIRECT) View.VISIBLE else View.GONE
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
            addView(p2pInfoContainer)
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

    override fun onResume() {
        super.onResume()
        if (p2pManager != null && p2pChannel != null) {
            p2pReceiver = WifiP2pBroadcastReceiver(p2pManager!!, p2pChannel!!, this)
            registerReceiver(p2pReceiver, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        p2pReceiver?.let { unregisterReceiver(it) }
    }

    private fun startWifiDirectGroup() {
        if (p2pManager == null || p2pChannel == null) {
            Toast.makeText(this, "Wi-Fi Direct unavailable on this device", Toast.LENGTH_SHORT).show()
            return
        }

        p2pManager?.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Wi-Fi Direct Group Initialized", Toast.LENGTH_SHORT).show()
                p2pInfoContainer.visibility = View.VISIBLE
                startBridgeServices()
            }

            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "Failed to create group: $reason")
                Toast.makeText(this@MainActivity, "Wi-Fi Direct Error: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // Called when Wi-Fi Direct connection state changes
    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        info?.let {
            if (it.groupFormed && it.isGroupOwner) {
                tvGatewayIp.text = "Proxy Gateway IP: ${it.groupOwnerAddress.hostAddress}:8080"
                
                // Request group credentials (SSID & Passphrase)
                if (checkP2pPermissions()) {
                    p2pManager?.requestGroupInfo(p2pChannel, this)
                }
            }
        }
    }

    // Retrieves network credentials once Group Info is available
    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        group?.let {
            tvSsid.text = "SSID: ${it.networkName}"
            tvPassphrase.text = "Passphrase: ${it.passphrase}"
            Log.d("WifiDirect", "P2P Network Active - SSID: ${it.networkName}, Passphrase: ${it.passphrase}")
        }
    }

    private fun stopBridgeServices() {
        if (prefs.networkMode == NetworkMode.WIFI_DIRECT && p2pManager != null && p2pChannel != null) {
            p2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirect", "Wi-Fi Direct Group Cleared")
                    tvSsid.text = "SSID: --"
                    tvPassphrase.text = "Passphrase: --"
                }
                override fun onFailure(reason: Int) {
                    Log.e("WifiDirect", "Failed to remove group: $reason")
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
