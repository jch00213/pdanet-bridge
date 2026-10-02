package com.jeremy.pdanet.bridge

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btnStart = Button(this).apply { text = "Start Service & Tethering" }
        val btnStop = Button(this).apply { text = "Stop Service" }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btnStart)
            addView(btnStop)
        }
        setContentView(layout)

        btnStart.setOnClickListener {
            if (ShizukuBridge.isShizukuReady()) {
                val tetherSuccess = ShizukuBridge.enableSystemTethering()
                if (tetherSuccess) {
                    startForegroundService(Intent(this, ProxyService::class.java))
                    Toast.makeText(this, "Tethering & SOCKS Proxy Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to start SoftAP via Shizuku", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Shizuku authorization missing!", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
            Toast.makeText(this, "Proxy Service Stopped", Toast.LENGTH_SHORT).show()
        }
    }
}
