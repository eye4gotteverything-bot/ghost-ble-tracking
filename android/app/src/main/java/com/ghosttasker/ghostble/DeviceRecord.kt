package com.ghosttasker.ghostble

/**
 * One real device/network heard by a real scan. There is no demo data in
 * this app: a DeviceRecord is only ever created from an actual
 * BluetoothLeScanner ScanResult or an actual WifiManager ScanResult.
 */
enum class ScanSource { BLE, WIFI }

data class DeviceRecord(
    /** BLE: device MAC address. Wi-Fi: BSSID. This is the unique key. */
    val address: String,
    val source: ScanSource,
    var name: String = "Unknown",
    var deviceType: String = "Unknown",
    var rssi: Int = 0,
    /** Log-distance path-loss estimate. An estimate, never exact. */
    var distanceFeet: Double = Double.NaN,
    var firstSeenMs: Long = System.currentTimeMillis(),
    var lastSeenMs: Long = System.currentTimeMillis(),
    var seenCount: Int = 1,
    var tracked: Boolean = false,
    /** True once a proximity notification has fired for the current approach,
     *  so the user is not spammed every second. Reset when it moves away. */
    var alertFired: Boolean = false,

    // ---- BLE-only fields ----
    var serviceUuids: List<String> = emptyList(),
    /** Friendly names matched from [serviceUuids], e.g. "Heart Rate". */
    var serviceNames: List<String> = emptyList(),
    /** Manufacturer company id -> hex payload, from the advertisement. */
    var manufacturerData: Map<Int, String> = emptyMap(),
    var manufacturerNames: List<String> = emptyList(),
    var connectable: Boolean = false,
    /** GAP appearance value from the advertisement, if present. */
    var appearance: Int? = null,
    var txPowerAdvertised: Int? = null,

    // ---- Wi-Fi-only fields ----
    var ssid: String = "",
    var frequencyMhz: Int = 0,
    var channel: Int = 0,
    var band: String = "",          // "2.4 GHz" / "5 GHz" / "6 GHz"
    var capabilities: String = "",  // raw security/feature string from Android
    var securityLabel: String = "", // plain label, e.g. "WPA2", "Open"

    /** Targeting-pattern flags attached by [TargetingDetector]. */
    var flags: MutableList<String> = mutableListOf(),

    /** (timestampMs, distanceFeet) samples for this session, oldest first. */
    val distanceHistory: MutableList<Pair<Long, Double>> = mutableListOf()
) {
    fun displayName(): String =
        if (source == ScanSource.WIFI) {
            if (ssid.isNotBlank()) ssid else "(hidden network)"
        } else {
            if (name.isNotBlank()) name else "Unknown"
        }

    fun bandLabel(): String = when (source) {
        ScanSource.BLE -> "2.4 GHz BLE"
        ScanSource.WIFI -> band
    }
}
