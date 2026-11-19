package com.ghosttasker.ghostble

import kotlin.math.pow

/**
 * Distance estimation from signal strength.
 *
 * Log-distance path-loss model:
 *     distance_m = 10 ^ ((txPowerAt1m - rssi) / (10 * n))
 *
 * txPowerAt1m defaults to -59 dBm (a common measured value at 1 metre for
 * BLE beacons/phones). n is the path-loss exponent for the environment:
 * outdoor (open) 2.0, indoor 2.7, crowded (lots of bodies/walls) 3.3.
 *
 * This is an ESTIMATE. Walls, pockets, body blocking and antenna orientation
 * can easily make it off by several feet. The UI always labels it as such.
 */
object DistanceUtil {

    const val DEFAULT_TX_POWER_AT_1M = -59

    enum class Environment(val n: Double, val label: String) {
        OUTDOOR(2.0, "Outdoor (open)"),
        INDOOR(2.7, "Indoor"),
        CROWDED(3.3, "Crowded / obstructed");

        companion object {
            fun fromLabel(label: String): Environment =
                values().firstOrNull { it.label == label } ?: INDOOR
        }
    }

    fun distanceMeters(rssi: Int, txPowerAt1m: Int, n: Double): Double {
        if (rssi == 0) return Double.NaN // 0 RSSI from Wi-Fi means "no signal info"
        return 10.0.pow((txPowerAt1m - rssi).toDouble() / (10.0 * n))
    }

    fun distanceFeet(rssi: Int, txPowerAt1m: Int, environment: Environment): Double =
        distanceMeters(rssi, txPowerAt1m, environment.n) * 3.28084

    fun formatFeet(feet: Double): String =
        if (feet.isNaN() || feet.isInfinite()) "—"
        else if (feet < 10) String.format("%.1f ft", feet)
        else String.format("%.0f ft", feet)

    // ------------------------------------------------------------------
    // Wi-Fi frequency / channel / band helpers
    // ------------------------------------------------------------------

    /** Band label for a Wi-Fi frequency in MHz: "2.4 GHz", "5 GHz", "6 GHz". */
    fun wifiBand(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2499 -> "2.4 GHz"
        frequencyMhz in 4900..5899 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    /**
     * Wi-Fi channel derived from centre frequency.
     * 2.4 GHz: ch = (freq - 2407) / 5  (ch 14 = 2484 handled specially)
     * 5 GHz:   ch = (freq - 5000) / 5
     * 6 GHz:   ch = (freq - 5950) / 5  (Wi-Fi 6E channel numbering)
     */
    fun wifiChannel(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 4900..5899 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
        else -> 0
    }

    /** Plain security label from a WifiManager capabilities string. */
    fun wifiSecurityLabel(capabilities: String): String {
        val caps = capabilities.uppercase()
        return when {
            caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
            caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            caps.contains("ESS") && !caps.contains("WPA") && !caps.contains("WEP") -> "Open"
            else -> "Unknown"
        }
    }
}
