package com.jeremy.pdanet.bridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
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

        // Mode Radio Buttons
        val radioShizuku = RadioButton(this).apply { text = "Shizuku Mode (System Hotspot)" }
        val radioVpn = RadioButton(this).apply { text = "VPN Mode (Local Proxy/TUN)" }
        val modeRadioGroup = RadioGroup(this).apply {
            addView(radioShizuku)
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

        // Restore UI state from preferences
        if (prefs.networkMode == NetworkMode.SHIZUKU) {
            radioShizuku.isChecked = true
        } else {
            radioVpn.isChecked = true
        }

        // Listeners
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.networkMode = if (checkedId == radioShizuku.id) {
                NetworkMode.SHIZUKU
            } else {
                NetworkMode.VPN
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

            if (prefs.networkMode == NetworkMode.SHIZUKU) {
                if (!ShizukuBridge.isShizukuReady()) {
                    requestShizukuPermission()
                    return@setOnClickListener
                }
                val tetherSuccess = ShizukuBridge.enableSystemTethering()
                if (tetherSuccess) {
                    startBridgeServices()
                } else {
                    Toast.makeText(this, "Failed to start SoftAP via Shizuku", Toast.LENGTH_SHORT).show()
                }
            } else {
                // VPN Mode
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnLauncher.launch(vpnIntent)
                } else {
                    startBridgeServices()
                }
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
            stopService(Intent(this, BridgeVpnService::class.java))
            Toast.makeText(this, "Bridge Services Stopped", Toast.LENGTH_SHORT).show()
        }
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
    }
}
