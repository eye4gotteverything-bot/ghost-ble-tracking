package com.ghosttasker.ghostble

import java.util.UUID

/**
 * Best-effort device-type guessing from REAL advertised data only:
 * advertised service UUIDs, the GAP appearance value, and the device name.
 * It is a guess — the UI presents it as the detected type, and the detail
 * view shows the underlying services/name it was guessed from.
 */
object DeviceType {

    // Well-known Bluetooth SIG 16-bit service UUIDs -> friendly names.
    val KNOWN_SERVICES: Map<String, String> = mapOf(
        "1800" to "Generic Access",
        "1801" to "Generic Attribute",
        "1802" to "Immediate Alert",
        "1803" to "Link Loss",
        "1804" to "Tx Power",
        "1805" to "Current Time",
        "1808" to "Glucose",
        "1809" to "Health Thermometer",
        "180a" to "Device Information",
        "180d" to "Heart Rate",
        "180e" to "Phone Alert Status",
        "180f" to "Battery",
        "1810" to "Blood Pressure",
        "1812" to "Human Interface Device (keyboard/mouse/controller)",
        "1816" to "Cycling Speed and Cadence",
        "1818" to "Cycling Power",
        "181a" to "Environmental Sensing",
        "181d" to "Weight Scale",
        "1826" to "Fitness Machine",
        "184e" to "Audio Input Control",
        "184f" to "Volume Control",
        "1853" to "Telephony and Media Audio",
        "110a" to "Audio Sink (speaker/headphones)",
        "110b" to "Audio Source",
        "110e" to "A/V Remote Control (TV/media)",
        "111e" to "Hands-Free (headset/car)",
        "1200" to "PnP Information",
        "fef3" to "Google Fast Pair",
        "fd44" to "Google Nearby / Fast Pair",
        "fe9f" to "Google Eddystone beacon",
        "feaa" to "Eddystone beacon",
        "fed8" to "Microsoft beacon / Swift Pair",
        "fd6f" to "Exposure Notification (contact tracing)"
    )

    // Common manufacturer company IDs (Bluetooth SIG) -> names.
    val KNOWN_MANUFACTURERS: Map<Int, String> = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x00E0 to "Google",
        0x0075 to "Samsung",
        0x0046 to "Sony",
        0x000F to "Broadcom",
        0x0002 to "Intel",
        0x001D to "Qualcomm",
        0x0059 to "Nordic Semiconductor",
        0x0087 to "Garmin",
        0x0157 to "Fitbit / Google",
        0x0499 to "Ruuvi (beacon)",
        0x02E5 to "Estimote (beacon)"
    )

    fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        // Standard Bluetooth base UUID: 0000XXXX-0000-1000-8000-00805f9b34fb
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            s.substring(4, 8)
        } else s
    }

    fun friendlyServiceName(shortOrFullUuid: String): String? {
        val key = shortOrFullUuid.lowercase()
        return KNOWN_SERVICES[key] ?: KNOWN_SERVICES[shortUuidKey(key)]
    }

    private fun shortUuidKey(uuidString: String): String =
        if (uuidString.length == 36 && uuidString.startsWith("0000")) uuidString.substring(4, 8)
        else uuidString

    /**
     * GAP appearance (AD type 0x19) high bits describe the category.
     * Values per Bluetooth SIG Appearance Values.
     */
    private fun typeFromAppearance(appearance: Int?): String? {
        if (appearance == null) return null
        return when (appearance and 0xFFC0) { // category is the top 10 bits
            0x0040 -> "Phone"
            0x0080 -> "Computer"
            0x00C0 -> "Smartwatch / wearable"
            0x0100 -> "Clock"
            0x0140 -> "Display"
            0x0180 -> "Remote control"
            0x01C0 -> "Eye-glasses"
            0x0200 -> "Tag / tracker"
            0x0240 -> "Keyring"
            0x0280 -> "Media player"
            0x02C0 -> "Barcode scanner"
            0x0300 -> "Thermometer"
            0x0340 -> "Heart-rate sensor"
            0x0380 -> "Blood pressure"
            0x03C0 -> "Human interface device"
            0x0400 -> "Glucose meter"
            0x0440 -> "Running/walking sensor"
            0x0480 -> "Cycling sensor"
            0x0500 -> "Pulse oximeter"
            0x0540 -> "Weight scale"
            0x0580 -> "Personal mobility"
            0x05C0 -> "Continuous glucose monitor"
            0x0600 -> "Insulin pump"
            0x0640 -> "Medication delivery"
            0x0780 -> "Outdoor sports activity"
            else -> when (appearance) {
                0x03C1 -> "Keyboard"
                0x03C2 -> "Mouse"
                0x03C3, 0x03C4 -> "Game controller / joystick"
                0x03C9, 0x03CA -> "Game controller"
                else -> null
            }
        }
    }

    private val NAME_HINTS: List<Pair<String, List<String>>> = listOf(
        "Headphones / earbuds" to listOf(
            "airpods", "buds", "headphone", "headset", "earbud", "wh-", "wf-",
            "soundcore", "beats", "bose", "jbl tune", "pixel buds"
        ),
        "Smartwatch / wearable" to listOf(
            "watch", "galaxy watch", "apple watch", "fitbit", "versa", "charge ",
            "garmin", "fenix", "forerunner", "mi band", "band "
        ),
        "Fitness tracker" to listOf("fitbit", "tracker", "heart", "hr band"),
        "Smart TV" to listOf("tv", "television", "bravia", "roku", "fire tv", "chromecast", "samsung tv", "lg tv"),
        "Speaker" to listOf("speaker", "echo", "homepod", "nest audio", "sonos", "jbl flip", "jbl charge", "boom"),
        "Game controller" to listOf("controller", "dualsense", "dualshock", "xbox", "joy-con", "pro controller", "gamepad"),
        "Keyboard" to listOf("keyboard", "kbd"),
        "Mouse" to listOf("mouse"),
        "Phone" to listOf("iphone", "galaxy s", "galaxy z", "pixel", "phone"),
        "Beacon" to listOf("beacon", "ibeacon", "eddystone", "tile", "airtag", "smarttag")
    )

    /**
     * Guess a device type from advertised services first (most reliable),
     * then appearance, then name patterns (least reliable).
     */
    fun guessBleType(
        name: String?,
        serviceShortUuids: List<String>,
        appearance: Int?,
        manufacturerIds: Collection<Int>
    ): String {
        val services = serviceShortUuids.map { it.lowercase() }.toSet()
        when {
            "180d" in services -> return "Heart-rate monitor"
            "1812" in services -> return "Keyboard / mouse / controller (HID)"
            "1826" in services -> return "Fitness equipment"
            "1816" in services || "1818" in services -> return "Cycling / fitness sensor"
            "1810" in services -> return "Blood pressure monitor"
            "1808" in services -> return "Health thermometer"
            "110a" in services || "184e" in services || "184f" in services ||
                "1853" in services -> return "Headphones / speaker (audio)"
            "111e" in services -> return "Headset / hands-free audio"
            "110e" in services -> return "Smart TV / media device"
            "feaa" in services || "fe9f" in services -> return "Beacon (Eddystone)"
        }
        if (manufacturerIds.contains(0x004C) &&
            (services.isEmpty() || "180a" !in services)
        ) {
            // Apple continuity advertisements usually come from a phone/watch/
            // Mac nearby. Not proof — fall through to name/appearance first.
        }
        typeFromAppearance(appearance)?.let { return it }

        val n = (name ?: "").lowercase()
        if (n.isNotBlank()) {
            for ((type, hints) in NAME_HINTS) {
                if (hints.any { n.contains(it) }) return type
            }
        }
        if (manufacturerIds.contains(0x0499) || manufacturerIds.contains(0x02E5)) {
            return "Beacon"
        }
        return "Unknown"
    }
}
