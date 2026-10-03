package com.jeremy.pdanet.bridge

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class BridgeVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tunInterface != null) return

        try {
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setSession("PdaNetBridgeVPN")

            // Prevent loopback interception so internal socks/tsnet sockets route unimpeded
            builder.allowFamily(android.system.OsConstants.AF_INET)

            tunInterface = builder.establish()
            val fd = tunInterface?.fd ?: -1

            if (fd != -1) {
                Log.d(TAG, "VPN TUN Interface established with file descriptor: $fd")
                // FD passed to Rust socket forwarder engine
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        try {
            tunInterface?.close()
            tunInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN descriptor", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val TAG = "BridgeVpnService"
        const val ACTION_STOP = "com.jeremy.pdanet.bridge.STOP_VPN"
    }
}
