package com.jeremy.pdanet.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class ProxyService : Service() {

    companion object {
        init {
            System.loadLibrary("socks_bridge")
        }

        private external fun start_proxy_engine(bindIp: String, port: Int, interfaceName: String): Boolean
        private external fun stop_proxy_engine(): Boolean
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, "proxy_channel")
            .setContentTitle("PdaNet Bridge Active")
            .setContentText("SOCKS5 engine running on port 8080")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()

        startForeground(1001, notification)

        val activeInterface = ShizukuBridge.getUpstreamInterface()
        Thread {
            start_proxy_engine("0.0.0.0", 8080, activeInterface)
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        stop_proxy_engine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "proxy_channel",
            "Proxy Background Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}
