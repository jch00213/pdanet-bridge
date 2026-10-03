package com.jeremy.pdanet.bridge

import android.util.Log

object TailscaleBridge {

    private const val TAG = "TailscaleBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("tailscale")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libtailscale.so native library", e)
        }
    }

    // Native Go exports from libtailscale
    private external fun tailscale_start(dir: String, hostname: String, authkey: String): Int
    private external fun tailscale_stop(): Int

    fun startNode(dataDir: String, authKey: String = "", hostname: String = "pdanet-bridge"): Boolean {
        if (!isLoaded) {
            Log.e(TAG, "Tailscale native library is not loaded")
            return false
        }
        return try {
            val result = tailscale_start("$dataDir/tailscale", hostname, authKey)
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error starting embedded tsnet node", e)
            false
        }
    }

    fun stopNode() {
        if (!isLoaded) return
        try {
            tailscale_stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping embedded tsnet node", e)
        }
    }
}
