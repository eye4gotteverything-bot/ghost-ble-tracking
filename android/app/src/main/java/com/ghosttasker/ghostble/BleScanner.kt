package com.ghosttasker.ghostble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build

/**
 * REAL Bluetooth Low Energy scanning.
 *
 * Uses the phone's actual Bluetooth radio through BluetoothLeScanner.
 * Every ScanResult delivered by Android becomes/updates a DeviceRecord:
 * MAC address, advertised name, RSSI, service UUIDs, manufacturer data,
 * connectable flag, GAP appearance, and a distance estimate in feet.
 *
 * Callers must hold BLUETOOTH_SCAN (Android 12+) / location (older) at
 * runtime before calling [start]; the @SuppressLint is only safe because
 * MainActivity checks permissions first and this class re-checks adapter
 * state defensively.
 */
@SuppressLint("MissingPermission")
class BleScanner(
    context: Context,
    private val onResult: (DeviceRecord, ScanResult) -> Unit,
    private val onError: (String) -> Unit
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter
    private var leScanner: BluetoothLeScanner? = null

    @Volatile
    var isScanning: Boolean = false
        private set

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onError("BLE scan failed (Android error code $errorCode). " +
                "Check Bluetooth is on and scan permission is granted.")
        }
    }

    private fun handleResult(result: ScanResult) {
        val record = result.toRecord() ?: return
        onResult(record, result)
    }

    fun start(): Boolean {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null) {
            onError("This device has no Bluetooth adapter.")
            return false
        }
        if (!adapter.isEnabled) {
            onError("Bluetooth is OFF. Turn Bluetooth on, then start the scan again.")
            return false
        }
        if (scanner == null) {
            onError("Bluetooth LE scanner is unavailable right now.")
            return false
        }
        leScanner = scanner
        val settings = ScanSettings.Builder()
            // LOW_LATENCY = continuous, most responsive real-time scanning.
            // It uses more battery; that trade-off is stated in the README.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
            }
            .build()
        return try {
            // No ScanFilter: we want every BLE advertiser around, on all
            // three advertising channels (37/38/39) — the radio rotates
            // through them automatically; Android does not expose a
            // per-channel filter to apps.
            scanner.startScan(null, settings, callback)
            isScanning = true
            true
        } catch (e: SecurityException) {
            onError("Bluetooth scan permission was denied. Grant it in Settings > Apps > Ghost BLE Tracking > Permissions.")
            false
        } catch (e: IllegalStateException) {
            onError("Bluetooth is off or unavailable.")
            false
        }
    }

    fun stop() {
        if (!isScanning) return
        isScanning = false
        try {
            leScanner?.stopScan(callback)
        } catch (e: Exception) {
            // Adapter may already be off; stopping is best-effort.
        }
    }

    // ------------------------------------------------------------------

    private fun ScanResult.toRecord(): DeviceRecord? {
        val address = device?.address ?: return null
        val scanRecord = scanRecord
        val advertisedName = scanRecord?.deviceName
        val bondedName = try { device.name } catch (e: SecurityException) { null }
        val name = advertisedName ?: bondedName ?: "Unknown"

        val uuids = scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
        val shortUuids = uuids.map { DeviceType.shortUuid(it) }
        val serviceNames = shortUuids.mapNotNull { DeviceType.friendlyServiceName(it) }

        val manufacturerData = mutableMapOf<Int, String>()
        val manufacturerNames = mutableListOf<String>()
        scanRecord?.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                val id = sparse.keyAt(i)
                val bytes = sparse.valueAt(i)
                manufacturerData[id] = bytes.joinToString(" ") { "%02X".format(it) }
                DeviceType.KNOWN_MANUFACTURERS[id]?.let { manufacturerNames.add(it) }
            }
        }

        val appearance = parseAppearance(scanRecord?.bytes)
        val txPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ScanResult.txPower is the received TX power where available;
            // ScanRecord.txPowerLevel is the advertised value.
            scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        } else {
            scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        }

        return DeviceRecord(
            address = address,
            source = ScanSource.BLE,
            name = name,
            deviceType = DeviceType.guessBleType(
                name = name,
                serviceShortUuids = shortUuids,
                appearance = appearance,
                manufacturerIds = manufacturerData.keys
            ),
            rssi = rssi,
            serviceUuids = uuids.map { it.toString() },
            serviceNames = serviceNames,
            manufacturerData = manufacturerData,
            manufacturerNames = manufacturerNames,
            connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isConnectable else false,
            appearance = appearance,
            txPowerAdvertised = txPower
        )
    }

    /**
     * Parse the GAP Appearance (AD type 0x19) out of raw advertisement bytes.
     * Advertisement format: [length][type][data...] repeated.
     */
    private fun parseAppearance(bytes: ByteArray?): Int? {
        if (bytes == null) return null
        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + len >= bytes.size + 1) break
            val type = bytes[i + 1].toInt() and 0xFF
            if (type == 0x19 && len >= 3) {
                val lo = bytes[i + 2].toInt() and 0xFF
                val hi = bytes[i + 3].toInt() and 0xFF
                return lo or (hi shl 8)
            }
            i += len + 1
        }
        return null
    }
}
