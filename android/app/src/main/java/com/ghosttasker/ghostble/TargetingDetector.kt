package com.ghosttasker.ghostble

/**
 * Targeting-pattern detector.
 *
 * IMPORTANT HONESTY RULE: these are HEURISTICS — patterns that *can* appear
 * in targeting/spoofing setups, but also appear in perfectly normal places
 * (every coffee shop has an open hotspot; big venues have many access points
 * sharing one SSID). A flag here is a PATTERN TO LOOK AT, never proof of an
 * attack, an IMSI catcher, or anything aimed at a specific person. The UI
 * labels every flag exactly that way.
 *
 * Implemented patterns:
 *  1. Evil-twin candidate — same SSID broadcast by 2+ BSSIDs where the
 *     security differs (e.g. a real WPA2 network plus an OPEN copy), or an
 *     open copy of a normally-secured SSID seen in this session.
 *  2. Open generic hotspot — an open network with a generic lure-style name
 *     ("Free WiFi", "Free Internet", a phone-carrier or hotel name with no
 *     security), the classic captive-portal lure shape.
 *  3. BLE advertisement flooding — the same BLE address advertising at an
 *     abnormally high rate (many advertisements inside a short window),
 *     which can be spam, a broken device, or an ad-flooding tool.
 *  4. Follower candidate — the same address seen across 2+ separate scan
 *     sessions at a similar signal strength, i.e. something that keeps
 *     showing up near you at a similar distance. (Also completely normal
 *     for e.g. a coworker's phone or your own other devices.)
 */
class TargetingDetector {

    companion object {
        const val FLAG_EVIL_TWIN = "Pattern: possible evil twin — same network name, different access point/security"
        const val FLAG_OPEN_HOTSPOT = "Pattern: open generic hotspot name (no password)"
        const val FLAG_BLE_FLOOD = "Pattern: BLE advertisement flooding (very rapid ads from one address)"
        const val FLAG_FOLLOWER = "Pattern: seen in multiple sessions at a similar distance (follower candidate)"

        private const val FLOOD_WINDOW_MS = 5_000L
        private const val FLOOD_MIN_ADS = 40 // >8 ads/sec sustained is abnormal for most devices
        private const val FOLLOWER_RSSI_TOLERANCE_DB = 10
    }

    // address -> timestamps of recent advertisements (BLE flooding)
    private val adTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val floodingNow = mutableSetOf<String>()

    // address -> set of session ids it was seen in, and last RSSI per session
    private val sessionsSeen = mutableMapOf<String, MutableSet<Int>>()
    private val sessionRssi = mutableMapOf<String, MutableMap<Int, Int>>()

    /** Call for every BLE advertisement. Returns true if flooding is active. */
    fun onBleAdvertisement(address: String, nowMs: Long): Boolean {
        val deque = adTimes.getOrPut(address) { ArrayDeque() }
        deque.addLast(nowMs)
        while (deque.isNotEmpty() && nowMs - deque.first() > FLOOD_WINDOW_MS) {
            deque.removeFirst()
        }
        val flooding = deque.size >= FLOOD_MIN_ADS
        if (flooding) floodingNow.add(address) else floodingNow.remove(address)
        return flooding
    }

    fun isFlooding(address: String): Boolean = address in floodingNow

    /** Call once per session when a device is first seen in that session. */
    fun onSeenInSession(address: String, sessionId: Int, rssi: Int) {
        sessionsSeen.getOrPut(address) { mutableSetOf() }.add(sessionId)
        sessionRssi.getOrPut(address) { mutableMapOf() }[sessionId] = rssi
    }

    /**
     * Follower candidate: seen in >= 2 sessions AND the RSSI in those
     * sessions stayed within [FOLLOWER_RSSI_TOLERANCE_DB] dB — i.e. it kept
     * appearing at roughly the same distance, not just once in passing.
     */
    fun isFollowerCandidate(address: String): Boolean {
        val sessions = sessionsSeen[address] ?: return false
        if (sessions.size < 2) return false
        val rssis = sessionRssi[address]?.values ?: return false
        if (rssis.size < 2) return false
        return (rssis.max() - rssis.min()) <= FOLLOWER_RSSI_TOLERANCE_DB
    }

    private val GENERIC_OPEN_NAMES = listOf(
        "free wifi", "freewifi", "free internet", "freewi-fi", "guest wifi",
        "public wifi", "open wifi", "airport wifi", "hotel wifi", "starbucks",
        "mcdonalds", "xfinitywifi", "attwifi", "free", "guest", "welcome"
    )

    /**
     * Recompute Wi-Fi flags for the whole current result set (evil-twin
     * detection needs to compare networks against each other).
     * Mutates the flags of the given Wi-Fi records in place.
     */
    fun evaluateWifi(records: Collection<DeviceRecord>) {
        val wifi = records.filter { it.source == ScanSource.WIFI }
        val bySsid = wifi.filter { it.ssid.isNotBlank() }.groupBy { it.ssid }

        for (record in wifi) {
            record.flags.remove(FLAG_EVIL_TWIN)
            record.flags.remove(FLAG_OPEN_HOTSPOT)

            // --- Evil-twin candidate ---
            val twins = bySsid[record.ssid].orEmpty()
            if (twins.size > 1) {
                val bssids = twins.map { it.address }.toSet()
                val securities = twins.map { it.securityLabel }.toSet()
                // Same name on multiple access points is NORMAL for real
                // venues. It only becomes a pattern worth flagging when the
                // security differs between the copies — the classic setup
                // is one open copy next to the real secured network.
                if (bssids.size > 1 && securities.size > 1) {
                    record.flags.add(FLAG_EVIL_TWIN)
                }
            }

            // --- Open generic hotspot ---
            if (record.securityLabel == "Open") {
                val name = record.ssid.lowercase()
                if (GENERIC_OPEN_NAMES.any { name.contains(it) } || name.isBlank()) {
                    record.flags.add(FLAG_OPEN_HOTSPOT)
                }
            }
        }
    }

    /** Recompute BLE flags for one record from detector state. */
    fun evaluateBle(record: DeviceRecord) {
        record.flags.remove(FLAG_BLE_FLOOD)
        record.flags.remove(FLAG_FOLLOWER)
        if (isFlooding(record.address)) record.flags.add(FLAG_BLE_FLOOD)
        if (isFollowerCandidate(record.address)) record.flags.add(FLAG_FOLLOWER)
    }

    fun resetSessionState() {
        // Flooding windows are short-lived; session/follower history is kept
        // on purpose so cross-session patterns can be detected.
        adTimes.clear()
        floodingNow.clear()
    }
}
