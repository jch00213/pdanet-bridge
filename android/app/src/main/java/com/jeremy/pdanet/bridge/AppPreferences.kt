package com.jeremy.pdanet.bridge

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pdanet_bridge_prefs", Context.MODE_PRIVATE)

    var networkMode: NetworkMode
        get() {
            val mode = prefs.getString("KEY_NETWORK_MODE", NetworkMode.SHIZUKU.name)
            return try {
                NetworkMode.valueOf(mode!!)
            } catch (e: Exception) {
                NetworkMode.SHIZUKU
            }
        }
        set(value) {
            prefs.edit().putString("KEY_NETWORK_MODE", value.name).apply()
        }

    var enableTailscale: Boolean
        get() = prefs.getBoolean("KEY_ENABLE_TAILSCALE", false)
        set(value) = prefs.edit().putBoolean("KEY_ENABLE_TAILSCALE", value).apply()

    var tailscaleAuthKey: String
        get() = prefs.getString("KEY_TAILSCALE_AUTH_KEY", "") ?: ""
        set(value) = prefs.edit().putString("KEY_TAILSCALE_AUTH_KEY", value).apply()
}
