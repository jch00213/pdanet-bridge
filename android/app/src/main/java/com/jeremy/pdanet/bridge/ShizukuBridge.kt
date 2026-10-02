package com.jeremy.pdanet.bridge

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuBridge {

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun execCommand(command: String): String {
        if (!isShizukuReady()) return ""

        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            process.waitFor()
            result.trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun enableSystemTethering(): Boolean {
        val output = execCommand("cmd connectivity start-tethering wifi")
        return !output.contains("Error", ignoreCase = true) && !output.contains("failed", ignoreCase = true)
    }

    fun getUpstreamInterface(): String {
        val routeOutput = execCommand("ip route show | grep rmnet")
        val tokens = routeOutput.split("\\s+".toRegex())
        val index = tokens.indexOf("dev")
        return if (index != -1 && index + 1 < tokens.size) {
            tokens[index + 1]
        } else {
            "rmnet_data0"
        }
    }
}
