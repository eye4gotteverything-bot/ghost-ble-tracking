package com.ghosttasker.ghostble

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

/**
 * REAL Wi-Fi scanning with WifiManager.
 *
 * HONEST LIMIT — Android throttles Wi-Fi scans: a foreground app gets at
 * most 4 scans per 2 minutes (and background apps far fewer). startScan()
 * simply returns false when throttled. This class surfaces that honestly
 * through [onThrottled] instead of pretending to scan continuously.
 * BLE scanning has no such throttle and is the real-time part of the app.
 *
 * 6 GHz results only appear on phones with Wi-Fi 6E/7 hardware scanning a
 * 6 GHz network nearby; on other phones that band simply stays empty.
 */
@SuppressLint("MissingPermission") // MainActivity checks runtime permissions first.
class WifiScanner(
    private val context: Context,
    private val onResults: (List<DeviceRecord>) -> Unit,
    private val onThrottled: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var receiverRegistered = false

    private val resultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                readResults()
                if (!updated) {
                    // Results are cached ones — a fresh scan was throttled.
                    onThrottled()
                }
            }
        }
    }

    fun isWifiOn(): Boolean = wifiManager?.isWifiEnabled == true

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(resultsReceiver, filter)
        }
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(resultsReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        receiverRegistered = false
    }

    /**
     * Ask Android for one Wi-Fi scan. Returns false if Wi-Fi is off, the
     * manager is missing, or Android refused (usually throttling).
     */
    fun requestScan(): Boolean {
        val manager = wifiManager
        if (manager == null) {
            onError("This device has no Wi-Fi manager available.")
            return false
        }
        if (!manager.isWifiEnabled) {
            onError("Wi-Fi is OFF. Turn Wi-Fi on to scan 2.4 / 5 / 6 GHz networks.")
            return false
        }
        return try {
            @Suppress("DEPRECATION") // startScan is the only public scan trigger API.
            val started = manager.startScan()
            if (!started) {
                // Most common cause: Android's 4-scans-per-2-minutes
                // foreground throttle. Show the last known results anyway.
                onThrottled()
                readResults()
            }
            started
        } catch (e: SecurityException) {
            onError("Wi-Fi scan permission was denied. Grant location / Nearby Wi-Fi permission in Settings.")
            false
        }
    }

    private fun readResults() {
        val manager = wifiManager ?: return
        val results: List<ScanResult> = try {
            manager.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            onError("Android blocked reading Wi-Fi results — the location / Nearby Wi-Fi permission is required by Android for Wi-Fi scans.")
            return
        }
        onResults(results.map { it.toRecord() })
    }

    private fun ScanResult.toRecord(): DeviceRecord {
        val ssid = SSID?.removeSurrounding("\"") ?: ""
        val caps = capabilities ?: ""
        return DeviceRecord(
            address = BSSID ?: "",
            source = ScanSource.WIFI,
            name = ssid,
            deviceType = "Wi-Fi access point / router",
            rssi = level,
            ssid = ssid,
            frequencyMhz = frequency,
            channel = DistanceUtil.wifiChannel(frequency),
            band = DistanceUtil.wifiBand(frequency),
            capabilities = caps,
            securityLabel = DistanceUtil.wifiSecurityLabel(caps)
        )
    }
}
